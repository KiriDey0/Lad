# SPDX-License-Identifier: AGPL-3.0-only
"""Synthetic files only: original data, path grants and queue state are boundaries."""
import asyncio
import hashlib
import sys
import threading
import wave
from pathlib import Path
from types import SimpleNamespace
import aiohttp
import pytest
from mutagen.wave import WAVE
from mutagen.id3 import TIT2, TPE1, TALB, USLT
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
from local_library import LocalLibrary, LocalMediaServer, LocalFileUnavailable, first
from app import App


def test_vorbis_rejects_mp4_tag_keys_without_losing_long_lyrics():
    from mutagen._vorbis import VCommentDict
    tags = VCommentDict()
    text = '[00:00]Start\n' + 'x' * 3000 + '\n[00:42]End'
    tags['lyrics'] = text
    assert first(tags, '\xa9lyr', 'lyrics', limit=1_000_000) == text


def audio(path, lyrics='[00:00.00]Start\n[00:01.25]Next'):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000)
        w.writeframes(b'\x00\x00' * 16000)
    a = WAVE(path); a.add_tags()
    a.tags.add(TIT2(encoding=3, text='A title'))
    a.tags.add(TPE1(encoding=3, text='An artist'))
    a.tags.add(TALB(encoding=3, text='An album'))
    a.tags.add(USLT(encoding=3, lang='eng', text=lyrics)); a.save()
    return path


async def scanned(lib, folder):
    await lib.scan(folder)
    if folder in lib.jobs: await lib.jobs[folder]
    return lib.browse(kind='tracks')['tracks']


@pytest.fixture
async def library(tmp_path):
    lib = LocalLibrary(tmp_path / 'private')
    yield lib
    await lib.close()


async def test_recursive_tags_lyrics_and_rescan_never_write_originals(library, tmp_path):
    root = tmp_path / 'music'
    p = audio(root / 'nested' / 'first.wav')
    original = hashlib.sha256(p.read_bytes()).digest()
    folder = library.add_folder(root)
    tracks = await scanned(library, folder)
    assert len(tracks) == 1
    t = tracks[0]
    assert (t['title'], t['artist'], t['album']) == ('A title', 'An artist', 'An album')
    assert t['source'] == 'local' and not t['ratingKnown']
    assert library.lyrics(t['id'])['lines'][1]['time'] == 1.25
    assert (await scanned(library, folder))[0]['id'] == t['id']
    assert hashlib.sha256(p.read_bytes()).digest() == original


async def test_sidecar_long_embedded_lyrics_and_symlink_boundary(library, tmp_path):
    root = tmp_path / 'music'
    p = audio(root / 'one.wav', '[00:00.00]First\n' + 'x'*3000 + '\n[00:05.00]Last')
    outside = audio(tmp_path / 'other' / 'hidden.wav')
    (root / 'escape.wav').symlink_to(outside)
    (root / 'escape').symlink_to(outside.parent, target_is_directory=True)
    folder = library.add_folder(root)
    ts = await scanned(library, folder)
    assert len(ts) == 1
    id_ = ts[0]['id']
    assert library.lyrics(id_)['lines'][-1]['text'] == 'Last'
    sidecar = p.with_suffix('.lrc')
    private = tmp_path / 'outside.lrc'; private.write_text('[00:00]Outside')
    sidecar.symlink_to(private)
    assert library.lyrics(id_)['lines'][0]['text'] == 'First'
    sidecar.unlink(); sidecar.write_text('[00:00.500]Sidecar')
    assert library.lyrics(id_)['lines'] == [{'time': .5, 'text': 'Sidecar'}]


async def test_removed_or_moved_sources_preserve_playlist_references(library, tmp_path):
    root = tmp_path / 'music'; p = audio(root / 'one.wav')
    folder = library.add_folder(root); t = (await scanned(library, folder))[0]
    playlist = library.create_playlist('Saved')
    library.playlist(playlist['id'], append=[t]); library.like(t['id'], True)
    await library.remove_folder(folder)
    assert library.browse(kind='tracks')['total'] == 0
    assert not library.playlist(playlist['id'])['tracks'][0]['available']
    assert p.exists()
    assert library.add_folder(root) == folder
    assert (await scanned(library, folder))[0]['liked']
    relocated = tmp_path / 'relocated'; root.rename(relocated)
    assert not library.track(t['id'])['available']
    await library.restore_folder(folder, relocated)
    await library.jobs[folder]
    assert library.track(t['id'])['available']


async def test_http_original_range_head_and_random_capability(library, tmp_path):
    p = audio(tmp_path / 'music' / 'one.wav')
    folder = library.add_folder(p.parent); t = (await scanned(library, folder))[0]
    server = LocalMediaServer(library)
    try:
        url = await server.audio(t['id'])
        async with aiohttp.ClientSession() as session:
            async with session.get(url, headers={'Range': 'bytes=10-39'}) as r:
                assert r.status == 206
                assert await r.read() == p.read_bytes()[10:40]
                assert r.headers['Content-Range'].startswith('bytes 10-39/')
            async with session.head(url) as r:
                assert r.status == 200 and int(r.headers['Content-Length']) == p.stat().st_size
            async with session.get(url.replace(server.secret, 'not-authorized')) as r:
                assert r.status == 404
    finally:
        await asyncio.wait_for(server.close(), 3)


async def test_cancel_scan_waits_for_worker_before_database_close(tmp_path):
    lib = LocalLibrary(tmp_path / 'private')
    p = audio(tmp_path / 'music' / 'one.wav')
    folder = lib.add_folder(p.parent)
    entered, finish = threading.Event(), threading.Event()
    original = lib._metadata
    def delayed(*args):
        entered.set(); finish.wait(2)
        return original(*args)
    lib._metadata = delayed
    await lib.scan(folder)
    assert await asyncio.to_thread(entered.wait, 2)
    closing = asyncio.create_task(lib.close())
    await asyncio.sleep(.02)
    assert not closing.done()
    finish.set()
    await asyncio.wait_for(closing, 2)


async def test_local_play_and_filter_never_require_yandex_account(tmp_path, monkeypatch):
    import app as module
    monkeypatch.setenv('LAD_DATA_DIR', str(tmp_path / 'data'))
    monkeypatch.setattr(module, 'emit', lambda *args: None)
    engine = App(); await engine.setup()
    try:
        p = audio(tmp_path / 'music' / 'one.wav')
        folder = engine.local_library.add_folder(p.parent)
        t = (await scanned(engine.local_library, folder))[0]
        engine.block_explicit = True
        await engine.rpc('play', {'tracks': [{**t, 'title': 'Forged', 'explicit': True}]})
        assert engine.playback['track']['title'] == 'A title'
        assert engine.playback['track']['source'] == 'local'
        assert (await engine.rpc('lyrics', {'id':t['id']}))['synced']
        await engine.rpc('like', {'id':t['id'], 'liked':True})
        assert len((await engine.rpc('localLiked', {}))['tracks']) == 1
        await engine.rpc('logout', {})
        assert engine.local_library.browse(kind='tracks')['total'] == 1
    finally: await engine.close()
