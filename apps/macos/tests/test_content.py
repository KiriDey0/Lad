# SPDX-License-Identifier: AGPL-3.0-only
import sys
from pathlib import Path
from types import SimpleNamespace
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
import pytest
from app import App, ContentRestricted
from music import track
from test_core import tr


def metadata(id_, explicit=False, warning=None):
    return SimpleNamespace(id=id_, title='Test', artists=[], albums=[], duration_ms=10000,
                           cover_uri=None, available=True, explicit=explicit, content_warning=warning)


@pytest.mark.parametrize('flag,warning,expected', [(True,None,True),(False,'explicit',True),(False,None,False)])
def test_yandex_explicit_marking_uses_both_reported_fields(flag,warning,expected):
    assert track(metadata('1',flag,warning))['explicit'] is expected


@pytest.fixture
async def content_engine(monkeypatch):
    import app as module
    monkeypatch.setattr(module, 'emit', lambda *args: None)
    engine=App();await engine.setup()
    yield engine
    await engine.close()


@pytest.mark.asyncio
async def test_station_play_and_transfer_check_yandex_metadata_before_any_command(content_engine):
    engine=content_engine;engine.block_explicit=True;engine.connection='connected'
    commands=[]
    class FakeStation:
        async def command(self,payload):commands.append(payload)
        async def close(self):pass
    async def tracks(id_):return [metadata(id_,True)]
    engine.music.client=SimpleNamespace(tracks=tracks)
    engine.station=FakeStation();engine.output='station'
    engine.queue.replace([tr(1)])
    with pytest.raises(ContentRestricted):
        await engine.rpc('play',{'tracks':[tr(2)]})
    assert not commands
    assert engine.queue.current['id']=='1'
    engine.output='local'
    with pytest.raises(ContentRestricted):
        await engine.rpc('output',{'output':'station'})
    assert engine.output=='local'
    assert not commands


@pytest.mark.asyncio
async def test_automatic_next_skips_explicit_tracks(content_engine):
    engine=content_engine;engine.demo=True;engine.block_explicit=True
    engine.queue.replace([tr(1),{**tr(2),'explicit':True},{**tr(3),'explicit':True},tr(4)])
    await engine.start_track(0)
    await engine.advance(automatic=True)
    assert engine.queue.index==3
    assert engine.playback['track']['id']=='4'


@pytest.mark.asyncio
async def test_all_explicit_with_repeat_all_terminates_without_playback(content_engine):
    import asyncio
    engine=content_engine;engine.demo=True;engine.block_explicit=True
    engine.queue.replace([{**tr(1),'explicit':True},{**tr(2),'explicit':True}])
    engine.queue.repeat='all'
    engine.playback.update(track=engine.queue.current,playing=True)
    await asyncio.wait_for(engine.advance(automatic=True),1)
    assert not engine.playback['playing']


@pytest.mark.asyncio
async def test_enabling_filter_pauses_explicit_and_disabling_allows_it(content_engine):
    engine=content_engine;engine.demo=True
    engine.queue.replace([{**tr(1),'explicit':True}])
    await engine.start_track(0)
    assert engine.playback['playing']
    await engine.rpc('setContentFilter',{'enabled':True})
    assert not engine.playback['playing']
    with pytest.raises(ContentRestricted):await engine.control('play')
    await engine.rpc('setContentFilter',{'enabled':False})
    await engine.control('play')
    assert engine.playback['playing']


@pytest.mark.asyncio
async def test_local_filter_restores_from_saved_settings_without_account_requests(content_engine):
    engine=content_engine
    await engine.rpc('boot',{'saved':{'blockExplicit':True}})
    assert engine.snapshot()['blockExplicit']
    await engine.rpc('setContentFilter',{'enabled':False})
    assert not engine.snapshot()['blockExplicit']


@pytest.mark.asyncio
async def test_wave_starts_from_unmarked_track_when_filter_enabled(content_engine):
    engine=content_engine;engine.block_explicit=True
    class Client:
        async def rotor_station_tracks(self,id_):
            return SimpleNamespace(sequence=[SimpleNamespace(track=metadata('1',True)),
                                             SimpleNamespace(track=metadata('2'))],batch_id='batch')
        async def tracks(self,id_):return [metadata(id_)]
        async def rotor_station_feedback(self,*args,**kwargs):pass
    engine.music.client=Client()
    async def stream(id_):return 'https://example.test/audio'
    engine.stream=stream
    await engine.rpc('wave',{})
    assert engine.queue.current['id']=='2'
    assert len(engine.queue.tracks)==1


def test_track_preserves_each_artist_identifier_for_navigation():
    data = metadata('1')
    data.artists = [SimpleNamespace(id=11, name='First'), SimpleNamespace(id=22, name='Second')]
    data.albums = [SimpleNamespace(id=33, title='Album')]
    result = track(data)
    assert result['artists'] == [{'id': '11', 'name': 'First'}, {'id': '22', 'name': 'Second'}]
    assert result['albumId'] == '33'
