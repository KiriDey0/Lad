# SPDX-License-Identifier: AGPL-3.0-only
import asyncio
import sys
import time
from pathlib import Path
from types import SimpleNamespace

import pytest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
from app import App
from test_core import tr


def model(id_, available=True, explicit=False):
    return SimpleNamespace(id=str(id_),title='Track '+str(id_),artists=[],albums=[],duration_ms=100000,
        cover_uri=None,available=available,explicit=explicit,content_warning=None)


@pytest.fixture
async def wave(monkeypatch):
    import app as module
    notices=[]
    monkeypatch.setattr(module,'emit',lambda event,data:notices.append((event,data)))
    engine=App();await engine.setup()
    for task in list(engine.tasks):task.cancel()
    await asyncio.gather(*list(engine.tasks),return_exceptions=True)
    played=[];feedback=[];requests=[];commands=[]
    async def batch(*args,**kwargs):
        requests.append(kwargs)
        return SimpleNamespace(sequence=[SimpleNamespace(track=model(3)),SimpleNamespace(track=model(4))],batch_id='new')
    async def report(id_,kind,**kwargs):feedback.append((kind,kwargs))
    class Station:
        async def play_track(self,id_,position,paused):
            played.append(str(id_))
            return {'playing':not paused,'playerState':{'id':str(id_),'duration':100,'progress':position}}
        async def command(self,payload):commands.append(payload)
        async def close(self):pass
    engine.music.client=SimpleNamespace(rotor_station_tracks=batch,rotor_station_feedback=report)
    engine.output='station';engine.connection='connected';engine.station=Station()
    engine.queue.replace([tr(1),tr(2)],0,'Моя волна')
    engine.radio={'id':'user:onyourwave','batch':'old'}
    engine.playback.update(track=engine.queue.current,duration=100,position=0,playing=False,positionAt=time.time()*1000)
    engine.test=SimpleNamespace(played=played,feedback=feedback,requests=requests,commands=commands,notices=notices)
    yield engine
    await engine.close()


@pytest.mark.asyncio
async def test_jump_to_last_preserves_wave_tops_up_and_next_plays_managed_track(wave):
    await wave.rpc('queue',{'action':'jump','index':1})
    assert wave.radio['id']=='user:onyourwave'
    assert wave.queue.owned
    assert [t['id'] for t in wave.queue.tracks]==['1','2','3','4']
    assert wave.test.requests[0]['queue']=='2'
    await wave.advance(1)
    assert wave.test.played==['2','3']
    assert wave.queue.current['id']=='3'
    assert wave.queue.owned


@pytest.mark.asyncio
async def test_finished_track_after_last_jump_still_uses_wave(wave):
    await wave.rpc('queue',{'action':'jump','index':1})
    await wave.advance(automatic=True)
    assert wave.queue.current['id']=='3'
    assert wave.queue.owned and wave.radio is not None


@pytest.mark.asyncio
async def test_duplicate_native_reports_during_topup_do_not_release_ownership(wave):
    wave.queue.index=1;wave.expected='2';wave.starting_until=0
    old={'track':wave.queue.current,'playing':True,'position':100,'duration':100,'volume':.4,'positionAt':time.time()*1000,'transitioning':False}
    wave.playback=old.copy();wave.station_playback=old.copy()
    loading=asyncio.Event();release=asyncio.Event()
    async def batch(*args,**kwargs):
        loading.set();await release.wait()
        return SimpleNamespace(sequence=[SimpleNamespace(track=model(3))],batch_id='new')
    wave.music.client.rotor_station_tracks=batch
    native={'playing':True,'playerState':{'id':'999','title':'Native continuation','duration':100,'progress':0}}
    wave.station_state(native)
    await asyncio.wait_for(loading.wait(),1)
    wave.station_state(native)
    assert wave.queue.owned and wave.radio is not None
    assert wave.playback['track']['id']=='2'
    release.set()
    while wave.advancing:await asyncio.sleep(.01)
    assert wave.test.played==['3']
    assert wave.queue.owned
    assert not any('вне' in data.get('message','') for event,data in wave.test.notices if event=='notice')


@pytest.mark.asyncio
async def test_user_switch_to_another_song_during_topup_is_respected(wave):
    wave.queue.index=1;wave.expected='2';wave.starting_until=0
    old={'track':wave.queue.current,'playing':True,'position':100,'duration':100,'volume':.4,'positionAt':time.time()*1000,'transitioning':False}
    wave.playback=old.copy();wave.station_playback=old.copy()
    loading=asyncio.Event();release=asyncio.Event()
    async def batch(*args,**kwargs):
        loading.set();await release.wait()
        return SimpleNamespace(sequence=[SimpleNamespace(track=model(3))],batch_id='new')
    wave.music.client.rotor_station_tracks=batch
    wave.station_state({'playing':True,'playerState':{'id':'999','duration':100,'progress':0}})
    await asyncio.wait_for(loading.wait(),1)
    wave.station_state({'playing':True,'playerState':{'id':'888','duration':100,'progress':0}})
    assert not wave.queue.owned
    release.set()
    while wave.advancing:await asyncio.sleep(.01)
    assert wave.test.played==[]
    assert wave.playback['track']['id']=='888'


@pytest.mark.asyncio
async def test_empty_batch_pauses_without_forgetting_wave_and_can_retry(wave):
    wave.queue.index=1
    async def empty(*args,**kwargs):return SimpleNamespace(sequence=[],batch_id='empty')
    wave.music.client.rotor_station_tracks=empty
    await wave.advance(automatic=True)
    assert wave.queue.owned and wave.radio is not None
    assert {'command':'stop'} in wave.test.commands
    async def next_batch(*args,**kwargs):return SimpleNamespace(sequence=[SimpleNamespace(track=model(3))],batch_id='new')
    wave.music.client.rotor_station_tracks=next_batch
    await wave.advance()
    assert wave.test.played==['3']


@pytest.mark.asyncio
async def test_feedback_uses_previous_batch_before_replenishment(wave):
    wave.queue.index=1
    wave.playback.update(track=wave.queue.current,position=90,duration=100,playing=False,positionAt=time.time()*1000)
    await wave.advance()
    await asyncio.sleep(0)
    skip=[kwargs for kind,kwargs in wave.test.feedback if kind=='skip'][0]
    assert skip['batch_id']=='old' and skip['track_id']=='2'
    assert skip['total_played_seconds']==90
    started=[kwargs for kind,kwargs in wave.test.feedback if kind=='trackStarted'][0]
    assert started['batch_id']=='new' and started['track_id']=='3'


@pytest.mark.asyncio
async def test_explicit_only_tail_fetches_past_multiple_filtered_batches(wave):
    wave.block_explicit=True
    wave.queue.replace([tr(1),*[dict(tr(i),explicit=True) for i in range(2,8)]],0,'Моя волна')
    calls=[]
    async def metadata(id_):return [model(id_,explicit=2<=int(id_)<12)]
    async def batch(*args,**kwargs):
        calls.append(kwargs.get('queue'))
        ids=[8,9] if len(calls)==1 else [10,11] if len(calls)==2 else [12,13]
        return SimpleNamespace(sequence=[SimpleNamespace(track=model(i,explicit=i<12)) for i in ids],batch_id=str(len(calls)))
    wave.music.client.tracks=metadata;wave.music.client.rotor_station_tracks=batch
    await wave.advance()
    assert wave.test.played==['12']
    assert calls==['1','9','11']
    assert wave.queue.owned and wave.radio is not None
    assert not any('В очереди нет' in data.get('message','') for event,data in wave.test.notices if event=='notice')


@pytest.mark.asyncio
async def test_explicit_radio_search_is_bounded_and_next_retries_from_cursor(wave):
    wave.block_explicit=True;wave.queue.index=1
    async def metadata(id_):return [model(id_)]
    count=0;calls=[]
    async def batch(*args,**kwargs):
        nonlocal count
        count+=1;calls.append(kwargs.get('queue'))
        return SimpleNamespace(sequence=[SimpleNamespace(track=model(10+count,explicit=count<=4))],batch_id=str(count))
    wave.music.client.tracks=metadata;wave.music.client.rotor_station_tracks=batch
    await wave.advance(automatic=True)
    assert count==4 and wave.test.played==[]
    assert wave.queue.owned and wave.radio is not None
    assert {'command':'stop'} in wave.test.commands
    await wave.advance()
    assert wave.test.played==['15'] and calls[-1]=='14'


@pytest.mark.asyncio
async def test_start_wave_searches_beyond_initial_explicit_only_batch(wave):
    wave.block_explicit=True
    async def metadata(id_):return [model(id_)]
    calls=[]
    async def batch(*args,**kwargs):
        calls.append(kwargs.get('queue'))
        i=10+len(calls)
        return SimpleNamespace(sequence=[SimpleNamespace(track=model(i,explicit=len(calls)<3))],batch_id=str(i))
    wave.music.client.tracks=metadata;wave.music.client.rotor_station_tracks=batch
    await wave.rpc('wave',{})
    assert wave.test.played==['13']
    assert calls==[None,'11','12']
    assert wave.queue.owned and wave.radio is not None


@pytest.mark.asyncio
async def test_native_pause_after_exhausted_radio_search_keeps_wave_retryable(wave):
    wave.queue.index=1;wave.expected='2';wave.starting_until=0
    old={'track':wave.queue.current,'playing':True,'position':100,'duration':100,'volume':.4,'positionAt':time.time()*1000,'transitioning':False}
    wave.playback=old.copy();wave.station_playback=old.copy()
    async def empty(*args,**kwargs):return SimpleNamespace(sequence=[],batch_id='empty')
    wave.music.client.rotor_station_tracks=empty
    native={'playing':True,'playerState':{'id':'999','duration':100,'progress':0}}
    wave.station_state(native)
    await asyncio.sleep(.01)
    assert not wave.advancing
    wave.station_state({**native,'playing':False})
    assert wave.queue.owned and wave.radio is not None
    assert wave.playback['track']['id']=='2' and not wave.playback['playing']
    assert not wave.playback['transitioning']
    async def refill(*args,**kwargs):return SimpleNamespace(sequence=[SimpleNamespace(track=model(3))],batch_id='new')
    wave.music.client.rotor_station_tracks=refill
    await wave.advance()
    assert wave.test.played==['3'] and wave.station_continuation is None
