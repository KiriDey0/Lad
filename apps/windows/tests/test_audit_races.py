# SPDX-License-Identifier: AGPL-3.0-only
import sys, asyncio
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
import pytest
import app as module
from app import App
from test_core import tr

@pytest.fixture
async def engine(monkeypatch):
    events = []
    monkeypatch.setattr(module, 'emit', lambda kind, data: events.append((kind, data)))
    engine = App(); await engine.setup(); engine.events = events
    engine.queue.replace([tr(9)])
    engine.playback.update(track=tr(9), duration=100)
    yield engine
    await engine.close()

async def pending_play(engine):
    entered, release = asyncio.Event(), asyncio.Event()
    async def stream(_):
        entered.set(); await release.wait(); return 'https://cdn.example/fixture.mp3'
    engine.stream = stream
    task = asyncio.create_task(engine.rpc('play', {'tracks':[tr(1)], 'index':0}))
    await asyncio.wait_for(entered.wait(), 2)
    return task, release

@pytest.mark.asyncio
async def test_logout_cannot_be_undone_by_late_stream(engine):
    task, release = await pending_play(engine)
    await engine.rpc('logout', {})
    engine.events.clear(); release.set()
    await asyncio.gather(task, return_exceptions=True)
    assert engine.profile is None
    assert engine.queue.tracks == []
    assert engine.playback['track'] is None
    assert not any(kind == 'local' and data.get('action') == 'load' for kind,data in engine.events)

@pytest.mark.asyncio
async def test_pause_while_loading_cannot_autoplay_late_result(engine):
    task, release = await pending_play(engine)
    await engine.control('pause')
    engine.events.clear(); release.set()
    await asyncio.gather(task, return_exceptions=True)
    assert not any(kind == 'local' and data.get('action') == 'load' and not data.get('paused') for kind,data in engine.events)

@pytest.mark.asyncio
async def test_content_filter_change_cancels_inflight_start(engine):
    task, release = await pending_play(engine)
    await engine.rpc('setContentFilter', {'enabled':True})
    engine.events.clear(); release.set()
    await asyncio.gather(task, return_exceptions=True)
    assert engine.block_explicit
    assert not any(kind == 'local' and data.get('action') == 'load' and not data.get('paused') for kind,data in engine.events)
