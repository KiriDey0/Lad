# SPDX-License-Identifier: AGPL-3.0-only
import sys, time, copy, asyncio
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
import pytest
from app import App
from station import local_host
from test_core import tr


@pytest.fixture
async def engine(monkeypatch):
    import app as module
    monkeypatch.setattr(module, 'emit', lambda *args: None)
    app = App(); await app.setup()
    yield app
    await app.close()


@pytest.mark.asyncio
async def test_failed_station_play_restores_queue_and_does_not_claim_playback(engine):
    engine.output = 'station';engine.connection = 'connected'
    engine.queue.replace([tr(1)])
    class Broken:
        async def command(self,payload): raise ValueError('Connection lost')
        async def play_track(self,*args): raise ValueError('Connection lost')
        async def close(self): pass
    engine.station = Broken()
    with pytest.raises(ValueError):
        await engine.rpc('play',{'tracks':[tr(2)],'index':0})
    assert engine.queue.current['id'] == '1'
    assert engine.playback['track'] is None


@pytest.mark.asyncio
async def test_stale_local_events_cannot_overwrite_station_state(engine):
    engine.output = 'station'
    before = copy.deepcopy(engine.playback)
    await engine.rpc('localState',{'generation':0,'position':9,'playing':True})
    assert engine.playback == before
    engine.output = 'local';engine.generation = 3
    await engine.rpc('localState',{'generation':2,'position':9,'playing':True})
    assert engine.playback == before


@pytest.mark.asyncio
async def test_external_track_releases_managed_queue_without_fighting_user(engine):
    engine.output = 'station';engine.queue.replace([tr(1),tr(2)])
    engine.expected = '1';engine.starting_until = 0
    engine.station_state({'playing':True,'volume':.3,'playerState':{'id':'999','title':'External','subtitle':'Artist','duration':100,'progress':8}})
    assert not engine.queue.owned
    assert engine.playback['track']['id'] == '999'
    assert len(engine.queue.tracks) == 2


@pytest.mark.asyncio
async def test_track_start_ack_does_not_override_real_subsequent_state(engine):
    engine.output = 'station';engine.queue.replace([tr(1)])
    engine.expected='1';engine.starting_until=time.monotonic()+10
    engine.station_state({'playing':False,'playerState':{'id':'9','duration':100,'progress':40}})
    assert engine.queue.owned
    engine.station_state({'playing':True,'playerState':{'id':'1:11','duration':10,'progress':2.4}})
    assert engine.playback['position']==2.4
    assert engine.starting_until==0


@pytest.mark.asyncio
async def test_snapshot_never_exposes_cookies_tokens_or_certificate_keys(engine):
    engine.cookie='DO_NOT_EXPOSE_COOKIE';engine.music_token='DO_NOT_EXPOSE_TOKEN'
    engine.devices=[{'id':'d','name':'Midi','glagol':{'security':{'server_private_key':'DO_NOT_EXPOSE_KEY'}}}]
    import json
    result=json.dumps(engine.snapshot())
    assert 'DO_NOT_EXPOSE' not in result
    assert 'cookie' not in result


@pytest.mark.asyncio
async def test_demo_output_transfer_preserves_track_and_position(engine):
    await engine.rpc('demo',{})
    await engine.rpc('control',{'action':'seek','value':77})
    await engine.rpc('connect',{'id':'demo-midi'})
    await engine.rpc('output',{'output':'station'})
    assert engine.output=='station'
    assert engine.playback['position']==77
    assert not engine.playback['playing']


@pytest.mark.asyncio
async def test_invalid_queue_change_is_rejected(engine):
    with pytest.raises(ValueError):
        await engine.rpc('play',{'tracks':[{'id':'https://evil.test','duration':10}]})


@pytest.mark.parametrize('host',['8.8.8.8','127.0.0.1','0.0.0.0','224.0.0.1','example.org'])
def test_station_address_must_be_local_literal(host):
    with pytest.raises(ValueError): local_host(host)


def test_private_station_address_is_accepted():
    assert local_host('192.168.1.25') == '192.168.1.25'

@pytest.mark.asyncio
async def test_glagol_device_list_uses_music_oauth_without_browser_cookie(engine, monkeypatch):
    import app as module
    requests = []
    class Response:
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def json(self, **kwargs):
            return {'devices': [{'id':'midi','name':'Миди','platform':'cucumber',
                'glagol':{'security':{'server_certificate':'public-cert','server_private_key':'NEVER_EXPOSE'}}}]}
    def get(url, **kwargs):
        requests.append((url,kwargs))
        return Response()
    async def lan(): return {'midi':{'host':'192.168.50.25','port':1961}}
    monkeypatch.setattr(engine.session, 'get', get)
    monkeypatch.setattr(module, 'discover', lan)
    engine.music_token = 'test-music-token'
    result = await engine.refresh_devices()
    assert requests[0][0] == 'https://quasar.yandex.net/glagol/device_list'
    assert requests[0][1]['headers'] == {'Authorization':'OAuth test-music-token'}
    assert result[0]['host'] == '192.168.50.25'
    assert 'glagol' not in result[0]
    assert 'server_private_key' not in engine.devices[0]['glagol']['security']
    assert engine.snapshot()['hasStationSession']


@pytest.mark.asyncio
async def test_local_to_station_keeps_position_and_applies_confirmed_device_state(engine):
    engine.queue.replace([{**tr(1),'duration':240}])
    engine.output='local';engine.connection='connected'
    engine.playback.update(track=engine.queue.current,position=83,playing=False,duration=240,positionAt=time.time()*1000)
    calls=[]
    class Device:
        async def play_track(self,id_,position,paused):
            calls.append((id_,position,paused))
            engine.station_state({'playing':True,'playerState':{'id':'1','duration':240,'progress':0}})
            return {'playing':False,'playerState':{'id':'1','duration':240,'progress':83}}
        async def close(self):pass
    engine.station=Device()
    await engine.rpc('output',{'output':'station'})
    assert calls==[('1',83,True)]
    assert engine.playback['position']==83
    assert not engine.playback['playing']
    assert not engine.playback['transitioning']


@pytest.mark.asyncio
async def test_failed_transfer_restores_source_playback_position(engine):
    engine.queue.replace([{**tr(1),'duration':240}])
    engine.output='local';engine.connection='connected'
    engine.playback.update(track=engine.queue.current,position=83,playing=True,duration=240,positionAt=time.time()*1000)
    class Device:
        async def play_track(self,*args):
            engine.station_state({'playing':True,'playerState':{'id':'1','duration':240,'progress':0}})
            raise ValueError('Seek was not confirmed')
        async def close(self):pass
    engine.station=Device()
    with pytest.raises(ValueError):await engine.rpc('output',{'output':'station'})
    assert engine.output=='local'
    assert 83 <= engine.playback['position'] < 84
    assert engine.playback['playing']


@pytest.mark.asyncio
async def test_album_likes_use_album_api_and_report_rejected_change(engine):
    from types import SimpleNamespace
    calls=[]
    async def add(id_): calls.append(('add',id_)); return True
    async def remove(id_): calls.append(('remove',id_)); return False
    engine.music.client=SimpleNamespace(users_likes_albums_add=add,users_likes_albums_remove=remove)
    assert await engine.rpc('albumLike',{'id':'123','liked':True}) is True
    with pytest.raises(ValueError,match='не сохранила'):
        await engine.rpc('albumLike',{'id':'123','liked':False})
    assert calls==[('add','123'),('remove','123')]
    with pytest.raises(ValueError,match='Некорректный'):
        await engine.rpc('albumLike',{'id':'123','liked':'false'})
    assert len(calls)==2


@pytest.mark.asyncio
async def test_demo_album_like_is_visible_in_library_without_touching_music_account(engine):
    await engine.rpc('demo',{})
    card={'id':'demo-album','type':'album','title':'Test Album'}
    await engine.rpc('albumLike',{'id':card['id'],'liked':True,'card':card})
    assert (await engine.rpc('library',{}))['albums']==[card]
    await engine.rpc('albumLike',{'id':card['id'],'liked':False})
    assert (await engine.rpc('library',{}))['albums']==[]
