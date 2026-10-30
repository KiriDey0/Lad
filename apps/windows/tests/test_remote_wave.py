# SPDX-License-Identifier: AGPL-3.0-only
import sys, copy
from pathlib import Path
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'backend'))
from app import App, ActionSuperseded

@pytest.mark.asyncio
async def test_analysis_stream_is_read_only_and_bound_to_current_remote_track():
    a=App();a.output='station';a.connection='connected';a.playback.update(track={'id':'42'},playing=True)
    calls=[]
    async def stream(id_):calls.append(id_);return 'https://audio.example.test/42.mp3'
    a.stream=stream
    before=copy.deepcopy(a.snapshot())
    result=await a.rpc('waveStream',{'trackId':'42'})
    assert result=={'url':'https://audio.example.test/42.mp3'}
    assert a.snapshot()==before
    for id_ in ['other','',None]:
        with pytest.raises(ValueError):await a.rpc('waveStream',{'trackId':id_})
    assert calls==['42']
    a.output='local'
    with pytest.raises(ValueError):await a.rpc('waveStream',{'trackId':'42'})

@pytest.mark.asyncio
async def test_track_switch_during_stream_request_cannot_install_old_analysis():
    a=App();a.output='station';a.connection='connected';a.playback['track']={'id':'42'}
    async def stream(id_):a.playback['track']={'id':'43'};return 'https://audio.example.test/42.mp3'
    a.stream=stream
    with pytest.raises(ActionSuperseded):await a.rpc('waveStream',{'trackId':'42'})
