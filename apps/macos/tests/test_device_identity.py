# SPDX-License-Identifier: AGPL-3.0-only
import asyncio
import copy
import sys
from pathlib import Path
import pytest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
from device_identity import enrich_devices, device_title, device_ambiguous
from app import App, ActionSuperseded


def sample():
    devices = [{'id': 'speaker-b', 'name': 'Яндекс Станция'}, {'id': 'speaker-a', 'name': 'Яндекс Станция'}]
    info = {'status': 'ok', 'households': [{'id': 'home', 'name': 'Дом'}],
            'rooms': [{'id': 'k', 'name': 'Кухня', 'household_id': 'home'},
                      {'id': 'r', 'name': 'Спальня', 'household_id': 'home'}],
            'devices': [{'id': 'cloud-a', 'name': 'Яндекс Станция', 'room': 'k', 'household_id': 'home',
                         'quasar_info': {'device_id': 'speaker-a'}},
                        {'id': 'cloud-b', 'name': 'Топаз', 'room': 'r', 'household_id': 'home',
                         'quasar_info': {'device_id': 'speaker-b'}}]}
    return devices, info


def test_rooms_and_custom_names_bind_to_glagol_ids_independent_of_order():
    devices, info = sample()
    a, b = enrich_devices(devices, info)
    assert (a['id'], a['name'], a['room'], a['household']) == ('speaker-b', 'Топаз', 'Спальня', 'Дом')
    assert (b['id'], device_title(b), b['model']) == ('speaker-a', 'Кухня', 'Яндекс Станция')
    assert not device_ambiguous(a, [a, b])
    assert 'room' not in devices[0]  # input remains unchanged


def test_never_join_by_name_ip_or_unrelated_cloud_id():
    devices, info = sample()
    for d in info['devices']:
        d['quasar_info']['device_id'] = 'unrelated-' + d['id']
        d['id'] = devices[0]['id']
    result = enrich_devices(devices, info)
    assert all(d['room'] == '' and d['metadataStatus'] == 'unmatched' for d in result)
    assert device_ambiguous(result[0], result)


def test_failed_missing_conflicting_and_cross_house_rooms_do_not_invent_a_location():
    devices, info = sample()
    failed = enrich_devices(devices)
    assert device_ambiguous(failed[0], failed)
    assert all(d['metadataStatus'] == 'unavailable' and not d['room'] for d in failed)
    info['rooms'][0]['household_id'] = 'another-house'
    result = enrich_devices(devices, info)
    assert result[1]['room'] == ''
    info['devices'].append(copy.deepcopy(info['devices'][0]))
    result = enrich_devices(devices, info)
    assert result[1]['metadataStatus'] == 'conflict' and result[1]['room'] == ''
    info['devices'] = info['devices'][:2]
    info['devices'][0]['room'] = None
    assert enrich_devices(devices, info)[1]['room'] == ''


@pytest.mark.asyncio
async def test_ambiguous_or_changed_selection_is_rejected_before_connecting_or_audio():
    a = App()
    a.devices = enrich_devices(sample()[0])
    a.device_id = 'already-selected'
    with pytest.raises(ValueError, match='различить'):
        await a.connect('speaker-a', '192.168.1.4')
    assert a.device_id == 'already-selected' and a.station is None
    a.devices = enrich_devices(*sample())
    with pytest.raises(ValueError, match='изменились'):
        await a.connect('speaker-a', identity={'name': 'Яндекс Станция', 'room': 'Спальня', 'household': 'Дом'})
    assert a.device_id == 'already-selected' and a.station is None


@pytest.mark.asyncio
async def test_explicit_override_requires_exact_identity_and_does_not_persist(monkeypatch):
    import app as module
    a = App()
    a.devices = enrich_devices(sample()[0])
    for d in a.devices: d['host'] = '192.168.1.4'
    d = a.devices[1]
    identity = {k: d[k] for k in ('name', 'room', 'household')}
    for consent in (False, 'true', 1):
        with pytest.raises(ValueError, match='различить'):
            await a.connect(d['id'], identity=identity, allow_ambiguous=consent)
    with pytest.raises(ValueError, match='различить'):
        await a.connect(d['id'], allow_ambiguous=True)
    with pytest.raises(ValueError, match='изменились'):
        await a.connect(d['id'], identity={**identity, 'room': 'Кухня'}, allow_ambiguous=True)
    calls = []
    class Station:
        def __init__(self, *args, **kwargs): calls.append(args[2]['id'])
        async def connect(self): calls.append('connect')
        async def close(self): pass
    monkeypatch.setattr(module, 'Station', Station)
    monkeypatch.setattr(module, 'emit', lambda *args: None)
    a.session = object()
    a.music_token = 'fixture'
    await a.connect(d['id'], identity=identity, allow_ambiguous=True)
    assert calls == [d['id'], 'connect']
    with pytest.raises(ValueError, match='различить'):
        await a.connect(d['id'], identity=identity)


@pytest.mark.asyncio
async def test_metadata_is_read_only_and_failed_optional_request_does_not_hide_speakers(monkeypatch):
    import app as module
    a = App()
    a.music_token = 'fixture'
    requests = []
    class Response:
        def __init__(self, value, status=200): self.value, self.status = value, status
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def json(self, **kwargs): return self.value
    class Session:
        def get(self, url, **kwargs):
            requests.append((url, kwargs))
            return Response({'devices': sample()[0]}) if '/glagol/' in url else Response(sample()[1])
    async def discover(): return {}
    a.session = Session()
    monkeypatch.setattr(module, 'discover', discover)
    monkeypatch.setattr(module, 'emit', lambda *args: None)
    result = await a.refresh_devices()
    assert result[0]['name'] == 'Топаз' and result[1]['room'] == 'Кухня'
    assert requests[1][0] == 'https://api.iot.yandex.net/v1.0/user/info'
    assert requests[1][1]['headers'] == {'Authorization': 'Bearer fixture'}
    assert requests[1][1]['allow_redirects'] is False
    assert a.station is None and not a.playback['playing']
    class Failed(Session):
        def get(self, url, **kwargs):
            if '/glagol/' not in url: raise asyncio.TimeoutError()
            return super().get(url, **kwargs)
    a.session = Failed()
    result = await a.refresh_devices()
    assert len(result) == 2 and device_ambiguous(result[0], result)


@pytest.mark.asyncio
async def test_logout_during_lookup_cannot_repopulate_the_old_account(monkeypatch):
    import app as module
    a = App()
    a.music_token = 'fixture'
    class Response:
        status = 200
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def json(self, **kwargs): return {'devices': sample()[0]}
    class Session:
        def get(self, *args, **kwargs): return Response()
    async def discover():
        a.operation_epoch += 1
        return {}
    a.session = Session()
    monkeypatch.setattr(module, 'discover', discover)
    with pytest.raises(ActionSuperseded): await a.refresh_devices()
    assert not a.devices


@pytest.mark.asyncio
async def test_old_refresh_cannot_overwrite_new_rooms_and_transfer_waits_for_refresh(monkeypatch):
    import app as module
    a = App()
    a.music_token = 'fixture'
    started, release = asyncio.Event(), asyncio.Event()
    count = 0
    class Response:
        status = 200
        def __init__(self, value): self.value = value
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def json(self, **kwargs): return self.value
    class Session:
        def get(self, url, **kwargs):
            return Response({'devices': sample()[0]} if '/glagol/' in url else sample()[1])
    async def discover():
        nonlocal count
        count += 1
        if count == 1:
            started.set()
            await release.wait()
        return {}
    a.session = Session()
    monkeypatch.setattr(module, 'discover', discover)
    monkeypatch.setattr(module, 'emit', lambda *args: None)
    first = asyncio.create_task(a.refresh_devices())
    await started.wait()
    with pytest.raises(ValueError, match='Дождись'): await a.connect('speaker-a')
    await a.refresh_devices()
    latest = copy.deepcopy(a.devices)
    release.set()
    await first
    assert a.devices == latest and not a.devices_refreshing
