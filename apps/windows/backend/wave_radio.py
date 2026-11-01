# SPDX-License-Identifier: AGPL-3.0-only
"""Server-filtered radio sessions with a bounded legacy fallback."""
from yandex_music import SessionEvent
from yandex_music.exceptions import BadRequestError, NotFoundError


def search_limit(block_explicit):
    return 20 if block_explicit else 4


async def session_seeds(client, station):
    info_method = getattr(client, 'rotor_station_info', None)
    if not callable(info_method):
        return [station]
    info = await info_method(station)
    if not info or not info[0].station or not info[0].settings2:
        return None
    current = info[0].settings2
    restrictions = info[0].station.restrictions2
    seeds = [station]
    for key in ('diversity', 'mood_energy', 'language'):
        options = getattr(getattr(restrictions, key, None), 'possible_values', None) or []
        selected = next((v for v in options if v.value == getattr(current, key, None)), None)
        seed = getattr(selected, 'serialized_seed', None)
        if not seed:
            # Preserve existing wave preferences on servers that cannot describe
            # their session seeds instead of silently using default preferences.
            return None
        seeds.append(seed)
    return seeds


async def fetch_batch(client, context, after, block_explicit):
    if context.get('filter') != block_explicit:
        context.update(filter=block_explicit, session=None, legacy=False, history=[], seeds=None)
    modern = callable(getattr(client, 'rotor_session_new', None))
    if block_explicit and modern and not context.get('legacy') and context.get('seeds') is None:
        try:
            context['seeds'] = await session_seeds(client, context['id'])
        except (BadRequestError, NotFoundError):
            context['seeds'] = None
        if context['seeds'] is None:
            context['legacy'] = True
    if block_explicit and modern and not context.get('legacy'):
        history = context['history']
        if after and after not in history:
            history.append(after)
        queue = list(history)
        try:
            if context.get('session'):
                result = await client.rotor_session_tracks(context['session'], queue=queue)
                if result and getattr(result, 'unknown_session', False):
                    context['session'] = None
                    return None
            else:
                result = await client.rotor_session_new(context['seeds'], queue=queue,
                    allow_explicit=False, include_tracks_in_response=True)
                session = getattr(result, 'radio_session_id', None)
                if not session or not getattr(result, 'batch_id', None):
                    context['legacy'] = True
                    return None
                context['session'] = session
        except (BadRequestError, NotFoundError):
            # This failed request consumes one search attempt. Legacy starts on
            # the next attempt, keeping the overall budget at twenty batches.
            context['legacy'] = True
            context['session'] = None
            return None
        if result:
            if result.batch_id:
                sessions = context.setdefault('sessions', {})
                sessions[result.batch_id] = context['session']
                while len(sessions) > 64:
                    del sessions[next(iter(sessions))]
            for item in result.sequence or []:
                if item.track:
                    id_ = str(item.track.id)
                    if id_ not in history:
                        history.append(id_)
            context['history'] = history[-1000:]
            if getattr(result, 'terminated', False):
                context['session'] = None
        return result
    return await client.rotor_station_tracks(context['id'], **({'queue': after} if after else {}))


async def send_feedback(client, context, kind, track_id, played=None):
    batch = context.get('batch')
    session = context.get('sessions', {}).get(batch)
    if session:
        from datetime import datetime, timezone
        event = SessionEvent(kind, datetime.now(timezone.utc).isoformat(), track_id=track_id,
                             total_played_seconds=played)
        await client.rotor_session_feedback(session, event, batch_id=batch, from_='lad-desktop')
    else:
        kwargs = {'track_id': track_id, 'batch_id': batch}
        if played is not None:
            kwargs['total_played_seconds'] = played
        await client.rotor_station_feedback(context['id'], kind, **kwargs)
