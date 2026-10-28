# SPDX-License-Identifier: AGPL-3.0-only
import asyncio
import pytest
from station import Station


def state(id_='42',position=0,playing=True):
    return {'playing':playing,'playerState':{'id':id_,'progress':position,'duration':240}}


@pytest.mark.asyncio
async def test_transfer_waits_for_loaded_track_before_seeking():
    station=Station(None,{}, {},lambda s:None,lambda *a:None)
    commands=[]
    async def command(payload):
        commands.append(payload)
        if payload['command']=='rewind':station.receive_state(state(position=payload['position']))
        if payload['command']=='stop':station.receive_state(state(position=77,playing=False))
    station.command=command
    task=asyncio.create_task(station.play_track('42',77,True))
    await asyncio.sleep(0)
    assert [c['command'] for c in commands]==['playMusic']
    station.receive_state(state('old-track'))
    await asyncio.sleep(0)
    assert len(commands)==1
    station.receive_state(state())
    result=await asyncio.wait_for(task,1)
    assert [c['command'] for c in commands]==['playMusic','rewind','stop']
    assert result['playerState']['progress']==77
    assert not result['playing']


@pytest.mark.asyncio
async def test_transfer_retries_seek_ignored_during_loading():
    station=Station(None,{}, {},lambda s:None,lambda *a:None)
    waits=station.wait_state
    async def wait(predicate,after,timeout=8):return await waits(predicate,after,.03)
    station.wait_state=wait
    seeks=[]
    async def command(payload):
        if payload['command']=='playMusic':station.receive_state(state())
        if payload['command']=='rewind':
            seeks.append(payload['position'])
            station.receive_state(state(position=0 if len(seeks)==1 else payload['position']))
    station.command=command
    result=await station.play_track('42',93)
    assert seeks==[93,93]
    assert result['playerState']['progress']==93


@pytest.mark.asyncio
async def test_same_track_ack_does_not_seek_old_paused_instance():
    station=Station(None,{}, {},lambda s:None,lambda *a:None)
    commands=[]
    async def command(payload):
        commands.append(payload)
        if payload['command']=='playMusic':station.receive_state(state(position=172,playing=False))
        elif payload['command']=='rewind':station.receive_state(state(position=payload['position']))
        elif payload['command']=='stop':station.receive_state(state(position=172,playing=False))
    station.command=command
    task=asyncio.create_task(station.play_track('42',172,True))
    await asyncio.sleep(0)
    assert [c['command'] for c in commands]==['playMusic']
    station.receive_state(state(position=173,playing=True))
    await asyncio.sleep(0)
    assert len(commands)==1
    station.receive_state(state(position=0,playing=True))
    result=await asyncio.wait_for(task,1)
    assert [c['command'] for c in commands]==['playMusic','rewind','stop']
    assert result['playerState']['progress']==172
    assert not result['playing']


@pytest.mark.asyncio
async def test_transfer_never_claims_success_without_confirmed_position():
    station=Station(None,{}, {},lambda s:None,lambda *a:None)
    waits=station.wait_state
    async def wait(predicate,after,timeout=8):return await waits(predicate,after,.03)
    station.wait_state=wait
    async def command(payload):station.receive_state(state(position=0))
    station.command=command
    with pytest.raises(ValueError,match='не подтвердила'):
        await station.play_track('42',123)
