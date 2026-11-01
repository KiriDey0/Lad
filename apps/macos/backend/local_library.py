# SPDX-License-Identifier: AGPL-3.0-only
"""Device-owned, read-only music catalogue. Originals never get written or deleted."""
from __future__ import annotations
import asyncio
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import socket
import sqlite3
import tempfile
import threading
import time
import unicodedata
import uuid
import wave
from aiohttp import web
from core import parse_lrc

AUDIO_EXTENSIONS = {'.mp3', '.flac', '.m4a', '.mp4', '.aac', '.ogg', '.opus', '.wav', '.aif', '.aiff'}
CAST_EXTENSIONS = {'.mp3', '.flac', '.m4a', '.mp4', '.aac', '.wav', '.ogg'}


class LocalFileUnavailable(ValueError):
    pass


def local_id(value):
    return str(value or '').startswith('local-')


def digest(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()[:32]


def folded(value):
    return unicodedata.normalize('NFKC', str(value)).casefold()


def first(tags, *names, default='', limit=2000):
    for name in names:
        try: value = tags.get(name)
        except (KeyError, ValueError): continue
        if value:
            if isinstance(value, (list, tuple)): value = value[0]
            text = str(value).strip().replace('\x00', '').strip()
            if text: return text[:limit]
    return default


class LocalLibrary:
    def __init__(self, directory=None, changed=lambda _: None):
        self.temporary = tempfile.TemporaryDirectory(prefix='lad-catalog-') if not directory else None
        self.directory = Path(directory or self.temporary.name) / 'local-library'
        self.directory.mkdir(parents=True, exist_ok=True)
        self.covers = self.directory / 'covers'
        self.covers.mkdir(exist_ok=True)
        self.db = sqlite3.connect(self.directory / 'catalog.sqlite', check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        self.changed = changed
        self.jobs = {}
        self.closed = False
        self.revision = 0
        with self.db:
            self.db.executescript('''
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS folders(id TEXT PRIMARY KEY,path TEXT UNIQUE NOT NULL,name TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'ready',scanned REAL DEFAULT 0,error TEXT DEFAULT '');
                CREATE TABLE IF NOT EXISTS tracks(id TEXT PRIMARY KEY,folder TEXT NOT NULL,relative TEXT NOT NULL,stamp TEXT NOT NULL,seen TEXT NOT NULL,available INTEGER NOT NULL,data TEXT NOT NULL,lyrics TEXT DEFAULT '',attachment TEXT DEFAULT '');
                CREATE INDEX IF NOT EXISTS tracks_folder ON tracks(folder);
                CREATE TABLE IF NOT EXISTS likes(id TEXT PRIMARY KEY);
                CREATE TABLE IF NOT EXISTS playlists(id TEXT PRIMARY KEY,title TEXT NOT NULL,items TEXT NOT NULL);
            ''')
            if 'override' not in {r[1] for r in self.db.execute('PRAGMA table_info(tracks)')}:
                self.db.execute("ALTER TABLE tracks ADD COLUMN override TEXT DEFAULT ''")

    def notify(self):
        if self.closed: return
        self.revision += 1
        self.changed(self.summary())

    def summary(self):
        with self.lock:
            folders = [dict(r) for r in self.db.execute("SELECT f.*,COUNT(t.id) AS count FROM folders f LEFT JOIN tracks t ON t.folder=f.id WHERE f.status!='detached' GROUP BY f.id ORDER BY f.name COLLATE NOCASE")]
            total = self.db.execute("SELECT COUNT(*) FROM tracks t JOIN folders f ON f.id=t.folder WHERE f.status!='detached'").fetchone()[0]
        return {'folders': folders, 'total': total, 'scanning': bool(self.jobs), 'revision': self.revision}

    def add_folder(self, value):
        path = Path(value).expanduser().resolve(strict=True)
        if not path.is_dir(): raise ValueError('Выбери папку с музыкой.')
        with self.lock, self.db:
            existing = self.db.execute('SELECT id,path,status FROM folders').fetchall()
            for r in existing:
                other = Path(r['path'])
                if path == other:
                    self.db.execute("UPDATE folders SET status='ready' WHERE id=?", (r['id'],))
                    return r['id']
                if r['status'] == 'detached': continue
                if path.is_relative_to(other) or other.is_relative_to(path):
                    raise ValueError('Эта папка уже входит в добавленную библиотеку.')
            id_ = 'local-folder-' + uuid.uuid4().hex
            self.db.execute('INSERT INTO folders(id,path,name) VALUES(?,?,?)', (id_, str(path), path.name or str(path)))
        self.notify()
        return id_

    async def stop_scan(self, id_):
        task = self.jobs.get(id_)
        if task:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            if self.jobs.get(id_) is task: self.jobs.pop(id_, None)

    async def remove_folder(self, id_):
        # Keep track records for references in saved playlists; hide the detached source.
        await self.stop_scan(id_)
        with self.lock, self.db:
            self.db.execute("UPDATE folders SET status='detached' WHERE id=?", (id_,))
        self.notify()

    async def restore_folder(self, id_, value):
        self.folder(id_)
        path = Path(value).expanduser().resolve(strict=True)
        if not path.is_dir(): raise ValueError('Выбери папку с музыкой.')
        await self.stop_scan(id_)
        with self.lock, self.db:
            for row in self.db.execute("SELECT id,path FROM folders WHERE id!=? AND status!='detached'", (id_,)):
                other = Path(row['path'])
                if path.is_relative_to(other) or other.is_relative_to(path):
                    raise ValueError('Эта папка уже входит в библиотеку.')
            self.db.execute('UPDATE folders SET path=?,name=?,status=? WHERE id=?', (str(path), path.name, 'ready', id_))
        await self.scan(id_)

    def folder(self, id_):
        with self.lock:
            row = self.db.execute('SELECT * FROM folders WHERE id=?', (id_,)).fetchone()
        if not row: raise LocalFileUnavailable('Папка отключена от библиотеки. Добавь её снова.')
        return dict(row)

    def path(self, id_, require=True):
        with self.lock:
            row = self.db.execute("SELECT t.relative,t.override,f.path FROM tracks t JOIN folders f ON f.id=t.folder WHERE t.id=? AND f.status!='detached'", (id_,)).fetchone()
        if not row: raise LocalFileUnavailable('Файл не найден в библиотеке этого устройства.')
        root = Path(row['path']).resolve()
        path = Path(row['override']).resolve() if row['override'] else (root / row['relative']).resolve()
        if (not row['override'] and not path.is_relative_to(root)) or (require and not path.is_file()):
            raise LocalFileUnavailable('Файл перемещён или накопитель отключён. Найди файл заново в коллекции.')
        return path

    def locate_file(self, id_, value):
        path = Path(value).resolve(strict=True)
        if not path.is_file() or path.suffix.lower() not in AUDIO_EXTENSIONS:
            raise ValueError('Выбери музыкальный файл.')
        with self.lock:
            row = self.db.execute('SELECT folder,relative FROM tracks WHERE id=?', (id_,)).fetchone()
        if not row: raise LocalFileUnavailable('Трек не найден.')
        self.folder(row['folder'])
        data, lyrics = self._metadata(path, row['folder'], row['relative'])
        data['id'] = id_
        with self.lock, self.db:
            self.db.execute('UPDATE tracks SET override=?,data=?,lyrics=?,available=1 WHERE id=?', (str(path), json.dumps(data, ensure_ascii=False), lyrics, id_))
        self.notify()

    def track(self, id_, cover=lambda _: None, require=False):
        with self.lock:
            row = self.db.execute('SELECT data,available FROM tracks WHERE id=?', (id_,)).fetchone()
            liked = self.db.execute('SELECT 1 FROM likes WHERE id=?', (id_,)).fetchone() is not None
        if not row: raise LocalFileUnavailable('Файл не найден в библиотеке этого устройства.')
        t = json.loads(row['data'])
        try:
            path = self.path(id_)
            t['available'] = True
            t['fileName'] = path.name
        except LocalFileUnavailable:
            if require: raise
            t['available'] = False
        t['liked'] = liked
        t['cover'] = cover(t.pop('coverKey', ''))
        return t

    def _art(self, audio, path):
        from PIL import Image, ImageOps
        tags = audio.tags or {}
        data = None
        pictures = getattr(audio, 'pictures', None)
        if pictures: data = next((p.data for p in pictures if p.type == 3), pictures[0].data)
        if not data and hasattr(tags, 'getall'):
            pictures = tags.getall('APIC')
            if pictures: data = next((p.data for p in pictures if p.type == 3), pictures[0].data)
        if not data and tags.get('covr'): data = bytes(tags['covr'][0])
        if not data:
            for name in ('cover.jpg', 'folder.jpg', 'cover.png', 'folder.png'):
                candidate = path.parent / name
                if candidate.is_file() and not candidate.is_symlink() and candidate.stat().st_size <= 8_000_000:
                    data = candidate.read_bytes(); break
        if not data or len(data) > 8_000_000: return ''
        key = hashlib.sha256(data).hexdigest()
        target = self.covers / (key + '.jpg')
        if not target.exists():
            try:
                with Image.open(io.BytesIO(data)) as image:
                    if image.width * image.height > 36_000_000: return ''
                    image = ImageOps.exif_transpose(image).convert('RGB')
                    image.thumbnail((600, 600))
                    image.save(target, 'JPEG', quality=85)
            except Exception: return ''
        return key

    def _metadata(self, path, folder, relative):
        import mutagen
        audio = mutagen.File(path)
        if audio is None or not audio.info or not getattr(audio.info, 'length', 0):
            raise ValueError('Не удалось прочитать аудио')
        easy = mutagen.File(path, easy=True)
        tags, raw = (easy.tags if easy else None) or {}, audio.tags or {}
        title = first(tags, 'title', 'TIT2', '\xa9nam', default=path.stem)
        artist = first(tags, 'artist', 'TPE1', '\xa9ART', default='Неизвестный исполнитель')
        album = first(tags, 'album', 'TALB', '\xa9alb', default='Без альбома')
        album_artist = first(tags, 'albumartist', 'album_artist', 'TPE2', 'aART', default=artist)
        id_ = 'local-track-' + digest(folder + '/' + relative)
        album_id = 'local-album-' + digest(folded(album_artist) + '\n' + folded(album))
        artist_id = 'local-artist-' + digest(folded(artist))
        lyric = first(raw, '\xa9lyr', 'lyrics', 'unsyncedlyrics', limit=1_000_000)
        if hasattr(raw, 'getall'):
            uslt = raw.getall('USLT')
            if uslt: lyric = uslt[0].text
            synced = [s for s in raw.getall('SYLT') if s.format == 2]
            if synced:
                lyric = '\n'.join(f'[{int(ms)//60000:02}:{(int(ms)%60000)/1000:06.3f}]{text}' for text, ms in synced[0].text)
        rating = first(raw, 'rtng', 'explicit', 'itunesadvisory', 'rating')
        explicit = folded(rating) in {'1', 'true', 'explicit', '18', '18+'}
        try: number = int(first(tags, 'tracknumber', 'TRCK', default='0').split('/')[0])
        except ValueError: number = 0
        return {'id': id_, 'source': 'local', 'title': title, 'artist': artist, 'artistId': artist_id,
                'artists': [{'id': artist_id, 'name': artist}], 'album': album, 'albumId': album_id,
                'albumArtist': album_artist, 'duration': float(audio.info.length), 'explicit': explicit,
                'ratingKnown': bool(rating), 'coverKey': self._art(audio, path), 'folderId': folder,
                'fileName': path.name, 'format': path.suffix[1:].upper(), 'trackNumber': number,
                'sampleRate': getattr(audio.info, 'sample_rate', 0), 'channels': getattr(audio.info, 'channels', 0),
                'fileSize': path.stat().st_size}, lyric[:1_000_000]

    async def scan(self, id_):
        if id_ in self.jobs: return
        marker = uuid.uuid4().hex
        stopped = threading.Event()
        loop = asyncio.get_running_loop()
        def worker():
            folder = self.folder(id_)
            root = Path(folder['path'])
            if not root.is_dir(): raise LocalFileUnavailable('Накопитель не подключён или папка перемещена.')
            count, errors = 0, 0
            def walk_error(error): raise error
            for directory, dirs, files in os.walk(root, followlinks=False, onerror=walk_error):
                dirs[:] = [d for d in dirs if not d.startswith('.') and not (Path(directory) / d).is_symlink()]
                for name in files:
                    if stopped.is_set(): return
                    path = Path(directory) / name
                    if path.suffix.lower() not in AUDIO_EXTENSIONS or path.is_symlink(): continue
                    try:
                        relative = str(path.relative_to(root))
                        stat = path.stat(); stamp = f'{stat.st_size}:{stat.st_mtime_ns}'
                        track_id = 'local-track-' + digest(id_ + '/' + relative)
                        with self.lock:
                            old = self.db.execute('SELECT stamp FROM tracks WHERE id=?', (track_id,)).fetchone()
                        if old and old['stamp'] == stamp:
                            with self.lock, self.db:
                                self.db.execute('UPDATE tracks SET seen=?,available=1 WHERE id=?', (marker, track_id))
                        else:
                            t, lyric = self._metadata(path, id_, relative)
                            with self.lock, self.db:
                                if not self.db.execute('SELECT 1 FROM folders WHERE id=?', (id_,)).fetchone(): return
                                self.db.execute('INSERT INTO tracks(id,folder,relative,stamp,seen,available,data,lyrics) VALUES(?,?,?,?,?,1,?,?) ON CONFLICT(id) DO UPDATE SET stamp=excluded.stamp,seen=excluded.seen,available=1,data=excluded.data,lyrics=excluded.lyrics', (track_id, id_, relative, stamp, marker, json.dumps(t, ensure_ascii=False), lyric))
                        count += 1
                        if count % 50 == 0: loop.call_soon_threadsafe(self.notify)
                    except (ValueError, OSError, EOFError): errors += 1
            if stopped.is_set(): return
            with self.lock, self.db:
                self.db.execute('UPDATE tracks SET available=0 WHERE folder=? AND seen!=?', (id_, marker))
                self.db.execute('UPDATE folders SET status=?,scanned=?,error=? WHERE id=?', ('ready', time.time(), f'Не прочитано файлов: {errors}' if errors else '', id_))
        async def run():
            task = None
            try:
                with self.lock, self.db: self.db.execute("UPDATE folders SET status='scanning',error='' WHERE id=?", (id_,))
                task = asyncio.create_task(asyncio.to_thread(worker))
                await asyncio.shield(task)
            except asyncio.CancelledError:
                stopped.set()
                if task: await task
                with self.lock, self.db: self.db.execute("UPDATE folders SET status='ready' WHERE id=?", (id_,))
                raise
            except Exception:
                with self.lock, self.db: self.db.execute("UPDATE folders SET status='unavailable',error='Папка недоступна. Подключи накопитель или выбери её заново.' WHERE id=?", (id_,))
            finally:
                self.jobs.pop(id_, None)
                if not self.closed: self.notify()
        self.jobs[id_] = asyncio.create_task(run())
        self.notify()

    def browse(self, *, kind='albums', query='', id_='', offset=0, limit=100, cover=lambda _: None):
        limit = min(5000, max(1, int(limit))); offset = max(0, int(offset))
        with self.lock:
            rows = self.db.execute("SELECT t.id,t.data FROM tracks t JOIN folders f ON f.id=t.folder WHERE f.status!='detached' ORDER BY t.rowid DESC").fetchall()
        tracks = []
        for row in rows:
            t = json.loads(row['data'])
            if query and folded(query) not in folded(t['title'] + ' ' + t['artist'] + ' ' + t['album'] + ' ' + t['fileName']): continue
            if id_ and id_ not in (t['folderId'], t['albumId'], t['artistId']): continue
            tracks.append(t)
        if id_ or kind == 'tracks':
            tracks.sort(key=lambda t: (folded(t['album']), t['trackNumber'], folded(t['title'])))
            return {'title': tracks[0]['album'] if tracks and id_.startswith('local-album-') else 'На устройстве',
                    'tracks': [self.track(t['id'], cover) for t in tracks[offset:offset + limit]], 'total': len(tracks), 'cards': []}
        if kind == 'folders':
            return {'folders': self.summary()['folders'], 'tracks': [], 'cards': [], 'total': len(self.summary()['folders'])}
        key = 'artistId' if kind == 'artists' else 'albumId'
        grouped = {}
        for t in tracks:
            c = grouped.setdefault(t[key], {'id': t[key], 'type': 'artist' if kind == 'artists' else 'album', 'source': 'local', 'title': t['artist'] if kind == 'artists' else t['album'], 'subtitle': t['artist'], 'count': 0, 'cover': cover(t.get('coverKey'))})
            c['count'] += 1
        cards = list(grouped.values())
        return {'cards': cards[offset:offset + limit], 'tracks': [], 'total': len(cards)}

    def lyrics(self, id_):
        path = self.path(id_)
        with self.lock:
            row = self.db.execute('SELECT lyrics,attachment FROM tracks WHERE id=?', (id_,)).fetchone()
        value = row['lyrics'] or ''
        attachment = Path(row['attachment']) if row['attachment'] else None
        for candidate in ([attachment] if attachment else []) + [path.with_suffix(ext) for ext in ('.lrc', '.LRC', '.txt', '.TXT')]:
            if candidate and (candidate == attachment or not candidate.is_symlink()) and candidate.is_file() and candidate.stat().st_size <= 1_000_000:
                try: value = candidate.read_text(encoding='utf-8-sig'); break
                except UnicodeError:
                    try: value = candidate.read_text(encoding='cp1251'); break
                    except UnicodeError: pass
        lines = parse_lrc(value)
        return {'synced': bool(lines), 'lines': lines or [{'time': None, 'text': line} for line in value.splitlines() if line.strip()], 'writers': [], 'provider': 'Локальный файл'}

    def attach_lyrics(self, id_, value):
        self.path(id_)
        path = Path(value).resolve(strict=True)
        if path.suffix.lower() not in ('.lrc', '.txt') or path.stat().st_size > 1_000_000: raise ValueError('Выбери текстовый файл LRC или TXT размером до 1 МБ.')
        with self.lock, self.db: self.db.execute('UPDATE tracks SET attachment=? WHERE id=?', (str(path), id_))

    def like(self, id_, liked):
        self.track(id_)
        with self.lock, self.db:
            if liked: self.db.execute('INSERT OR IGNORE INTO likes VALUES(?)', (id_,))
            else: self.db.execute('DELETE FROM likes WHERE id=?', (id_,))
        self.notify()

    def liked(self, cover=lambda _: None):
        with self.lock: ids = [r[0] for r in self.db.execute('SELECT id FROM likes')]
        tracks = []
        for id_ in ids:
            try: tracks.append(self.track(id_, cover))
            except LocalFileUnavailable: pass
        return {'title': 'Мне нравится · На устройстве', 'tracks': tracks, 'total': len(tracks)}

    def playlists(self):
        with self.lock: rows = self.db.execute('SELECT * FROM playlists ORDER BY rowid DESC').fetchall()
        return [{'id': r['id'], 'type': 'playlist', 'source': 'local', 'title': r['title'], 'subtitle': f"{len(json.loads(r['items']))} треков"} for r in rows]

    def playlist(self, id_, cover=lambda _: None, append=None):
        with self.lock:
            r = self.db.execute('SELECT * FROM playlists WHERE id=?', (id_,)).fetchone()
            if not r: raise ValueError('Плейлист не найден.')
            items = json.loads(r['items'])
            if append:
                if len(items) + len(append) > 5000: raise ValueError('Плейлист вмещает до 5000 треков.')
                items.extend(append)
                with self.db: self.db.execute('UPDATE playlists SET items=? WHERE id=?', (json.dumps(items), id_))
        tracks = []
        for t in items:
            if local_id(t.get('id')):
                try: t = self.track(t['id'], cover)
                except LocalFileUnavailable: t = {**t, 'available': False}
            tracks.append(t)
        return {'title': r['title'], 'tracks': tracks, 'total': len(tracks)}

    def create_playlist(self, title):
        title = str(title).strip()[:160]
        if not title: raise ValueError('Введи название плейлиста.')
        id_ = 'local-playlist-' + uuid.uuid4().hex
        with self.lock, self.db: self.db.execute('INSERT INTO playlists VALUES(?,?,?)', (id_, title, '[]'))
        self.notify()
        return {'id': id_, 'title': title, 'type': 'playlist', 'source': 'local'}

    async def close(self):
        self.closed = True
        # Do not close SQLite beneath an active worker; cancellation stops enumeration.
        jobs = list(self.jobs.values())
        for task in jobs: task.cancel()
        await asyncio.gather(*jobs, return_exceptions=True)
        with self.lock: self.db.close()
        if self.temporary: self.temporary.cleanup()


class LocalMediaServer:
    """Random per-run capabilities, original bytes, Range and a station-only LAN endpoint."""
    def __init__(self, library):
        self.library = library
        self.secret = secrets.token_urlsafe(32)
        self.runner = None
        self.loopback = ''
        self.lan = None
        self.peer = None
        self.transports = set()
        self.lock = asyncio.Lock()
        self.prepare_lock = asyncio.Lock()
        self.prepared = {}
        self.cast_cache = library.directory / 'cast-cache'
        self.cast_cache.mkdir(exist_ok=True)

    async def start(self):
        async with self.lock:
            if self.runner: return
            app = web.Application()
            app.router.add_get('/{secret}/{kind}/{asset}', self.serve)
            self.runner = web.AppRunner(app, access_log=None, shutdown_timeout=1)
            await self.runner.setup()
            site = web.TCPSite(self.runner, '127.0.0.1', 0)
            await site.start()
            self.loopback = f'http://127.0.0.1:{site._server.sockets[0].getsockname()[1]}/{self.secret}'

    def cover(self, key):
        return f'{self.loopback}/cover/{key}.jpg' if key and self.loopback else None

    async def audio(self, id_, peer=None):
        path = self.library.path(id_)
        await self.start()
        if not peer: return f'{self.loopback}/audio/{id_}{path.suffix.lower()}'
        if path.suffix.lower() not in CAST_EXTENSIONS:
            raise ValueError('Этот формат пока можно слушать на устройстве. Для Станции выбери MP3, FLAC, AAC, M4A или WAV.')
        if path.suffix.lower() in ('.flac', '.ogg'):
            async with self.prepare_lock:
                job = asyncio.create_task(asyncio.to_thread(self.prepare_pcm, id_, path))
                try:
                    prepared = await asyncio.shield(job)
                except asyncio.CancelledError:
                    # Finish the decoder before shutdown removes its state/cache.
                    await job
                    raise
                self.prepared[id_] = prepared
            suffix = '.wav'
        else: suffix = path.suffix.lower()
        if self.peer != peer:
            if self.lan: await self.lan.stop()
            # Select the interface used to reach this authenticated Station.
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                sock.connect((peer, 1961)); host = sock.getsockname()[0]
            finally: sock.close()
            self.lan = web.TCPSite(self.runner, host, 0)
            await self.lan.start()
            self.peer = peer
            self.lan_url = f'http://{host}:{self.lan._server.sockets[0].getsockname()[1]}/{self.secret}'
        return f'{self.lan_url}/audio/{id_}{suffix}'

    def prepare_pcm(self, id_, source):
        import miniaudio
        stat = source.stat()
        key = digest(id_ + f':{stat.st_size}:{stat.st_mtime_ns}')
        target = self.cast_cache / (key + '.wav')
        if target.exists():
            target.touch()
            return target
        info = miniaudio.get_file_info(str(source))
        width = min(4, max(2, info.sample_width))
        if info.num_frames * info.nchannels * width > 512 * 1024 * 1024:
            raise ValueError('Для этого файла нужно больше 512 МБ временного места. Его пока можно слушать на устройстве.')
        temporary = target.with_suffix('.tmp')
        decoder = miniaudio.stream_file(str(source), output_format=miniaudio.SampleFormat.SIGNED16 if width == 2 else miniaudio.SampleFormat.SIGNED32,
                                       nchannels=info.nchannels, sample_rate=info.sample_rate, frames_to_read=32768)
        try:
            with wave.open(str(temporary), 'wb') as output:
                output.setnchannels(info.nchannels); output.setsampwidth(width); output.setframerate(info.sample_rate)
                for frames in decoder:
                    data = frames.tobytes()
                    if width == 3:
                        pcm = bytearray(len(data) // 4 * 3)
                        pcm[0::3], pcm[1::3], pcm[2::3] = data[1::4], data[2::4], data[3::4]
                        data = pcm
                    output.writeframesraw(data)
            temporary.replace(target)
        finally:
            decoder.close()
            temporary.unlink(missing_ok=True)
        keep = sorted(self.cast_cache.glob('*.wav'), key=lambda p: p.stat().st_mtime, reverse=True)
        for old in keep[2:]:
            old.unlink(missing_ok=True)
            self.prepared = {k: v for k, v in self.prepared.items() if v != old}
        return target

    async def serve(self, request):
        if request.match_info['secret'] != self.secret or request.remote not in ('127.0.0.1', self.peer): raise web.HTTPNotFound()
        if request.transport: self.transports.add(request.transport)
        kind, asset = request.match_info['kind'], request.match_info['asset']
        if kind == 'cover':
            key = asset.removesuffix('.jpg')
            if len(key) != 64 or any(c not in '0123456789abcdef' for c in key): raise web.HTTPNotFound()
            path = self.library.covers / (key + '.jpg')
        elif kind == 'audio':
            try: path = self.library.path(Path(asset).stem)
            except LocalFileUnavailable: raise web.HTTPNotFound() from None
            if asset.endswith('.wav') and request.remote == self.peer:
                path = self.prepared.get(Path(asset).stem, path)
        else: raise web.HTTPNotFound()
        if not path.is_file(): raise web.HTTPNotFound()
        return web.FileResponse(path, headers={'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'})

    async def close(self):
        for transport in self.transports: transport.close()
        if self.runner: await self.runner.cleanup()
        self.runner = None
