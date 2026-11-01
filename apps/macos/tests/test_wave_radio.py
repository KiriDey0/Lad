# SPDX-License-Identifier: AGPL-3.0-only
import sys
from pathlib import Path
from types import SimpleNamespace as Model
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
from wave_radio import fetch_batch, send_feedback
from yandex_music.exceptions import BadRequestError, UnauthorizedError


def batch(ids, session=None, **kwargs):
    return Model(sequence=[Model(track=Model(id=str(i))) for i in ids],
                 radio_session_id=session, batch_id='batch-' + str(ids), **kwargs)


@pytest.mark.asyncio
async def test_filtered_session_sends_false_and_all_seen_ids_then_session_feedback():
    calls = []
    async def new(seed, **kw):
        calls.append(('new', seed, kw))
        return batch([1, 2], 'session')
    async def tracks(session, **kw):
        calls.append(('tracks', session, kw))
        return batch([3])
    async def feedback(session, event, **kw):
        calls.append(('feedback', session, event, kw))
    client = Model(rotor_session_new=new, rotor_session_tracks=tracks, rotor_session_feedback=feedback)
    context = {'id': 'user:onyourwave'}
    first = await fetch_batch(client, context, 'old', True)
    await fetch_batch(client, context, '2', True)
    # An earlier batch must still be reported to its own session after a top-up.
    context['batch'] = first.batch_id
    await send_feedback(client, context, 'skip', '2', 0)
    assert calls[0] == ('new', ['user:onyourwave'], {'queue': ['old'], 'allow_explicit': False,
                                               'include_tracks_in_response': True})
    assert calls[1] == ('tracks', 'session', {'queue': ['old', '1', '2']})
    assert calls[2][1] == 'session'
    assert calls[2][2].type == 'skip' and calls[2][2].total_played_seconds == 0


@pytest.mark.asyncio
async def test_unsupported_session_consumes_attempt_before_legacy_fallback():
    calls = []
    async def new(*a, **kw):
        calls.append('new')
        raise BadRequestError('unsupported')
    async def legacy(*a, **kw):
        calls.append(('legacy', kw))
        return batch([3])
    client = Model(rotor_session_new=new, rotor_station_tracks=legacy)
    context = {'id': 'user:onyourwave'}
    assert await fetch_batch(client, context, '2', True) is None
    assert calls == ['new']
    assert (await fetch_batch(client, context, '2', True)).sequence[0].track.id == '3'
    assert calls == ['new', ('legacy', {'queue': '2'})]


@pytest.mark.asyncio
async def test_auth_error_is_not_hidden_by_fallback():
    async def new(*a, **kw): raise UnauthorizedError('expired')
    context = {'id': 'user:onyourwave'}
    with pytest.raises(UnauthorizedError):
        await fetch_batch(Model(rotor_session_new=new), context, '', True)
    assert not context['legacy']


@pytest.mark.asyncio
async def test_unfiltered_wave_keeps_original_endpoint_and_reenable_creates_safe_session():
    calls = []
    async def new(*a, **kw): calls.append(kw); return batch([1], 'safe')
    async def legacy(*a, **kw): calls.append('legacy'); return batch([2])
    client = Model(rotor_session_new=new, rotor_station_tracks=legacy)
    context = {'id': 'user:onyourwave'}
    await fetch_batch(client, context, '', True)
    await fetch_batch(client, context, '', False)
    await fetch_batch(client, context, '', True)
    assert calls[1] == 'legacy' and calls[2]['allow_explicit'] is False


@pytest.mark.asyncio
async def test_expired_session_is_recreated_with_seen_ids_and_filter():
    calls = []
    async def new(*a, **kw): calls.append(kw); return batch([1], 'safe')
    async def tracks(*a, **kw): return batch([], unknown_session=True)
    client = Model(rotor_session_new=new, rotor_session_tracks=tracks)
    context = {'id': 'user:onyourwave'}
    await fetch_batch(client, context, '', True)
    assert await fetch_batch(client, context, '1', True) is None
    await fetch_batch(client, context, '1', True)
    assert calls[-1]['allow_explicit'] is False and calls[-1]['queue'] == ['1']


@pytest.mark.asyncio
async def test_saved_wave_preferences_are_carried_into_filtered_session():
    calls=[]
    async def info(*a):
        settings=Model(diversity='favorite',mood_energy='calm',language='russian')
        restrictions=Model(**{key:Model(possible_values=[Model(value=getattr(settings,key),serialized_seed=seed)])
            for key,seed in [('diversity','settingDiversity:favorite'),('mood_energy','settingMoodEnergy:calm'),('language','settingLanguage:russian')]})
        return [Model(settings2=settings,station=Model(restrictions2=restrictions))]
    async def new(seeds,**kw):calls.append(seeds);return batch([1],'safe')
    client=Model(rotor_station_info=info,rotor_session_new=new)
    await fetch_batch(client,{'id':'user:onyourwave'},'',True)
    assert calls==[['user:onyourwave','settingDiversity:favorite','settingMoodEnergy:calm','settingLanguage:russian']]


@pytest.mark.asyncio
async def test_missing_preference_seeds_preserves_legacy_wave():
    calls=[]
    async def info(*a):return [Model(settings2=Model(),station=Model(restrictions2=Model()))]
    async def legacy(*a,**kw):calls.append('legacy');return batch([1])
    client=Model(rotor_station_info=info,rotor_session_new=lambda:None,rotor_station_tracks=legacy)
    await fetch_batch(client,{'id':'user:onyourwave'},'',True)
    assert calls==['legacy']
