# SPDX-License-Identifier: AGPL-3.0-only
import sys
from pathlib import Path
from unittest.mock import AsyncMock
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
import pytest
import app as module
from app import App

class Output:
    def __init__(self, level=.65): self.level=level; self.reads=0; self.writes=[]
    def read(self): self.reads+=1; return self.level
    def write(self, value): self.level=value; self.writes.append(value); return value

@pytest.fixture
def player(monkeypatch):
    events=[]; monkeypatch.setattr(module,'emit',lambda kind,data:events.append((kind,data)))
    p=App(); p.volume_device=Output(); p.events=events
    return p

@pytest.mark.asyncio
async def test_default_off_never_touches_system_volume(player):
    await player.volume_tick()
    await player.control('volume', .2)
    assert not player.sync_volume
    assert player.volume_device.reads==0
    assert player.volume_device.writes==[]
    assert player.playback['volume']==.2

@pytest.mark.asyncio
async def test_local_enable_uses_device_level_and_unity_gain(player):
    await player.rpc('setVolumeSync', {'enabled':True})
    assert player.playback['volume']==.65
    assert player.volume_device.writes==[]
    assert ('local', {'action':'volume','value':1}) in player.events

@pytest.mark.asyncio
async def test_device_change_updates_local_ui_without_double_gain(player):
    await player.rpc('setVolumeSync', {'enabled':True})
    player.volume_device.level=.7
    await player.volume_tick()
    assert player.playback['volume']==.7
    assert player.events[-2][1]['value']==1
    assert player.volume_device.writes==[.7]

@pytest.mark.asyncio
async def test_slider_writes_native_volume(player):
    await player.rpc('setVolumeSync', {'enabled':True})
    await player.control('volume',.25)
    assert player.volume_device.level==.25
    assert player.playback['volume']==.25
    writes=len(player.volume_device.writes)
    await player.volume_tick(); await player.volume_tick()
    assert len(player.volume_device.writes)==writes

@pytest.mark.asyncio
async def test_station_two_way_sync_does_not_loop(player):
    player.output='station'; player.connection='connected'; player.device_id='fixture'
    player.station=type('Station',(),{'command':AsyncMock()})()
    player.playback['volume']=.35
    await player.rpc('setVolumeSync', {'enabled':True})
    assert player.volume_device.level==.35
    player.volume_device.level=.4
    await player.volume_tick()
    player.station.command.assert_awaited_once_with({'command':'setVolume','volume':.4})
    player.playback['volume']=.28
    await player.volume_tick()
    assert player.volume_device.level==.28
    await player.volume_tick()
    assert player.station.command.await_count==1

@pytest.mark.asyncio
async def test_disabled_sync_stops_both_directions(player):
    await player.rpc('setVolumeSync', {'enabled':True})
    await player.rpc('setVolumeSync', {'enabled':False})
    old_reads=player.volume_device.reads
    player.volume_device.level=.9
    await player.volume_tick()
    await player.control('volume',.1)
    assert player.volume_device.level==.9
    assert player.volume_device.reads==old_reads
    assert not player.snapshot()['syncVolume']

@pytest.mark.asyncio
async def test_demo_never_changes_physical_volume(player):
    player.demo=True
    await player.rpc('setVolumeSync', {'enabled':True})
    await player.control('volume',.9)
    await player.volume_tick()
    assert player.volume_device.reads==0
    assert player.volume_device.writes==[]

@pytest.mark.asyncio
async def test_station_disconnect_restores_original_device_volume(player):
    player.output='station'; player.connection='connected'; player.device_id='fixture'
    player.station=type('Station',(),{'command':AsyncMock()})()
    player.playback['volume']=.3
    await player.rpc('setVolumeSync',{'enabled':True})
    assert player.volume_device.level==.3
    player.station_connection('disconnected',None)
    assert player.volume_device.level==.65
    await player.volume_tick()
    assert player.volume_device.level==.65

@pytest.mark.asyncio
async def test_opt_out_while_casting_restores_previous_volume(player):
    player.output='station'; player.connection='connected'
    player.playback['volume']=.2
    await player.rpc('setVolumeSync',{'enabled':True})
    await player.rpc('setVolumeSync',{'enabled':False})
    assert player.volume_device.level==.65
    assert player.volume_backup is None
