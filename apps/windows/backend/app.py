# SPDX-License-Identifier: AGPL-3.0-only
"""JSON-lines RPC sidecar. Secrets travel only over inherited process pipes."""
from __future__ import annotations
import asyncio
import copy
import json
import logging
import math
import os
import sys
import time
import ssl
import uuid
from contextlib import suppress
import certifi
os.environ.setdefault("SSL_CERT_FILE", certifi.where())
if os.environ.get("LAD_MUSIC_SOURCE"):
    sys.path.insert(0, os.environ["LAD_MUSIC_SOURCE"])
import aiohttp
from yandex_music import ClientAsync
from core import Queue, cover_url, track_key
from music import Music, track
from station import Station, discover, local_host

logging.disable(logging.CRITICAL)


def emit(event, data):
    print(json.dumps({'event': event, 'data': data}, ensure_ascii=False), flush=True)


def safe_error(error):
    from yandex_music.exceptions import UnauthorizedError, NetworkError, NotFoundError
    if isinstance(error, UnauthorizedError):
        return 'Сессия Яндекс Музыки истекла. Войди в аккаунт ещё раз.'
    if isinstance(error, NotFoundError):
        return 'Эта запись недоступна в Яндекс Музыке.'
    if isinstance(error, (NetworkError, aiohttp.ClientError, asyncio.TimeoutError)):
        return 'Не удалось связаться с Яндексом. Проверь интернет или VPN и попробуй ещё раз.'
    if isinstance(error, ValueError):
        return str(error)[:300]
    return 'Не удалось выполнить действие. Попробуй ещё раз или войди в аккаунт заново.'


DEMO_TITLES = ['Первый свет', 'Тихий город', 'Между станциями', 'После полуночи', 'Ближе к свету', 'До следующего утра']
DEMO_TRACKS = [{'id': f'demo-{i}', 'title': title, 'artist': 'Лад Session', 'album': 'Тихий город',
                'artistId': 'demo-artist', 'artists': [{'id': 'demo-artist', 'name': 'Лад Session'}],
                'albumId': 'demo', 'duration': 216 + i * 9, 'cover': None, 'available': True, 'liked': i % 2 == 0,
                'art': i} for i, title in enumerate(DEMO_TITLES)]
DEMO_LINES = ['За окном засыпает город', 'И стихают его голоса', 'Мы останемся на своей орбите',
              'Пусть играет ещё одна', 'Свет проходит сквозь наши окна', 'И ведёт нас до самого утра']


class ActionSuperseded(ValueError):
    """A user action invalidated an earlier network operation."""
    pass


class ContentRestricted(ValueError):
    pass


class App:
    def __init__(self):
        self.music = Music()
        self.queue = Queue()
        self.profile = None
        self.cookie = ''
        self.music_token = ''
        self.refresh_token = ''
        self.demo = False
        self.demo_albums = []
        self.output = 'local'
        self.devices = []
        self.device_id = None
        self.hosts = {}
        self.station = None
        self.connection = 'disconnected'
        self.station_message = None
        self.playback = {'track': None, 'playing': False, 'position': 0, 'duration': 0, 'volume': .4,
                         'positionAt': time.time() * 1000, 'transitioning': False}
        self.station_playback = None
        self.expected = None
        self.starting_until = 0
        self.last_station_at = 0
        self.lock = asyncio.Lock()
        self.tasks = set()
        self.auth_code = None
        self.radio = None
        self.advancing = False
        self.station_continuation = None
        self.operation_epoch = 0
        self.generation = 0
        self.play_id = None
        self.total_played = 0
        self.last_local_report = None
        self.feedback_error = None
        self.block_explicit = False
        self.sync_volume = False
        self.volume_device = None
        self.volume_seen = None
        self.volume_route = None
        self.volume_backup = None
        self.local_volume_backup = None

    def require_operation(self, epoch):
        if self.operation_epoch != epoch:
            raise ActionSuperseded('Действие отменено: состояние плеера изменилось.')

    async def check_content(self, t):
        if not self.block_explicit:
            return
        if not self.demo:
            values = await self.music.client.tracks(t['id'])
            if not values:
                raise ContentRestricted('Не удалось проверить маркировку трека. Фильтр 18+ включён.')
            t.update(track(values[0]))
        if t.get('explicit'):
            raise ContentRestricted('Трек 18+ пропущен: включён фильтр контента.')

    def spawn(self, coro):
        task = asyncio.create_task(coro)
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        def check(t):
            if not t.cancelled() and t.exception():
                emit('notice', {'message': safe_error(t.exception())})
        task.add_done_callback(check)
        return task

    async def setup(self):
        self.session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=18),
            connector=aiohttp.TCPConnector(ssl=ssl.create_default_context(cafile=certifi.where())))
        self.spawn(self.tick())

    def snapshot(self):
        return {'profile': self.profile, 'demo': self.demo, 'output': self.output,
                'devices': [{k: d.get(k) for k in ('id', 'name', 'platform', 'host', 'port')} for d in self.devices],
                'deviceId': self.device_id, 'connection': self.connection, 'stationMessage': self.station_message,
                'playback': self.playback, 'queue': self.queue.snapshot(), 'feedbackError': self.feedback_error,
                'hasStationSession': bool(self.cookie or self.music_token), 'hasMusicSession': bool(self.music_token),
                'blockExplicit': self.block_explicit, 'syncVolume': self.sync_volume}

    def publish(self, persist=False):
        emit('state', self.snapshot())
        if persist and not self.demo:
            emit('persist', {'queue': self.queue.snapshot(), 'hosts': self.hosts, 'deviceId': self.device_id,
                             'output': self.output, 'blockExplicit': self.block_explicit, 'syncVolume': self.sync_volume})

    def credentials(self):
        emit('credentials', {'musicToken': self.music_token, 'cookie': self.cookie, 'refreshToken': self.refresh_token})

    def current_position(self):
        p = self.playback
        extra = (time.time() * 1000 - p['positionAt']) / 1000 if p['playing'] else 0
        return min(p['duration'] or 0, max(0, p['position'] + extra))

    async def login_music(self, token):
        epoch = self.operation_epoch
        profile = await self.music.login(token)
        self.require_operation(epoch)
        self.profile = profile
        self.music_token = token
        self.demo = False
        self.credentials()
        self.publish()
        return self.profile

    def quasar_auth(self):
        if self.music_token:
            return 'https://quasar.yandex.net', {'Authorization': f'OAuth {self.music_token}'}
        if self.cookie:
            return 'https://quasar.yandex.ru', {'Cookie': self.cookie}
        raise ValueError('Сначала войди в аккаунт Яндекса, к которому привязана Станция.')

    async def station_session(self, cookie):
        # Passport validates the browser session before we retain it. Glagol uses Music OAuth.
        async with self.session.post('https://mobileproxy.passport.yandex.net/1/bundle/oauth/token_by_sessionid',
            data={'client_id': 'c0ebe342af7d48fbbbfcf2d2eedb8f9e', 'client_secret': 'ad0a908f0aa341a182a37ecd75bc319e'},
            headers={'Ya-Client-Host': 'passport.yandex.ru', 'Ya-Client-Cookie': cookie}) as response:
            x_token = (await response.json(content_type=None)).get('access_token')
        if not x_token:
            raise ValueError('Яндекс не подтвердил сессию. Попробуй вход по коду устройства.')
        async with self.session.post('https://oauth.mobile.yandex.net/1/token',
            data={'grant_type': 'x-token', 'access_token': x_token, 'client_id': '23cabbbdc6cd418abb4b39c32c41195d',
                  'client_secret': '53bc75238f0c4d08a118e51fe9203300'}) as response:
            token = (await response.json(content_type=None)).get('access_token')
        if not token:
            raise ValueError('Не удалось получить доступ к Музыке. Попробуй вход по коду устройства.')
        await self.login_music(token)
        self.cookie = cookie
        self.credentials()
        try:
            await self.refresh_devices()
        except Exception:
            emit('notice', {'message': 'Вход в Музыку выполнен. Устройства можно обновить в меню выбора Станции.'})
        self.publish()
        return self.snapshot()

    async def refresh_devices(self):
        base_url, headers = self.quasar_auth()
        async with self.session.get(base_url + '/glagol/device_list', headers=headers) as response:
            result = await response.json(content_type=None)
        if 'devices' not in result:
            raise ValueError('Яндекс не вернул список Станций. Обнови вход в аккаунт.')
        self.devices = result['devices']
        for device in self.devices:
            device.get('glagol', {}).get('security', {}).pop('server_private_key', None)
        lan = await discover()
        for d in self.devices:
            if d['id'] in lan:
                d.update(lan[d['id']])
            elif self.hosts.get(d['id']):
                d.update(self.hosts[d['id']])
        self.publish()
        return self.snapshot()['devices']

    async def connect(self, id_, host=None):
        d = next((d for d in self.devices if d['id'] == id_), None)
        if not d:
            raise ValueError('Этой Станции нет в аккаунте. Обнови список.')
        if host:
            d['host'] = local_host(host)
            d['port'] = 1961
        if not d.get('host'):
            raise ValueError('Станция не найдена в локальной сети. Можно указать её IP вручную.')
        if self.station:
            await self.station.close()
        self.device_id = id_
        self.hosts[id_] = {'host': d['host'], 'port': d.get('port', 1961)}
        base_url, headers = self.quasar_auth()
        self.station = Station(self.session, headers, d, self.station_state, self.station_connection, base_url=base_url)
        self.publish(True)
        await self.station.connect()
        return self.snapshot()

    def station_connection(self, status, message):
        changed = status != self.connection or message != self.station_message
        self.connection, self.station_message = status, message
        if status != 'connected' and self.output == 'station':
            self.restore_device_volume()
            self.playback['playing'] = False
        if changed:
            self.publish()

    def station_state(self, data):
        p = data.get('playerState') or {}
        id_ = track_key(p.get('id'))
        current = self.queue.current
        t = next((t for t in self.queue.tracks if track_key(t['id']) == id_), None)
        if not t and id_:
            t = {'id': id_, 'title': p.get('title') or 'Музыка на Станции', 'artist': p.get('subtitle') or '',
                 'duration': p.get('duration') or 0, 'cover': cover_url((p.get('extra') or {}).get('coverURI')),
                 'available': True}
        previous = self.station_playback
        self.station_playback = {'track': t, 'playing': bool(data.get('playing')), 'position': float(p.get('progress') or 0),
            'duration': float(p.get('duration') or 0), 'volume': float(data.get('volume', .4)),
            'positionAt': time.time() * 1000, 'transitioning': False}
        self.last_station_at = time.monotonic()
        if self.output != 'station':
            return
        managed_playback = self.playback
        self.playback = self.station_playback.copy()
        if self.queue.owned and self.expected:
            if id_ == self.expected:
                self.starting_until = 0
                self.station_continuation = None
            elif time.monotonic() > self.starting_until and id_:
                # A native continuation immediately after a completed track is replaced by our queue.
                near_end = bool(previous and previous['playing'] and previous['duration'] and previous['position'] + (time.time() * 1000 - previous['positionAt']) / 1000 >= previous['duration'] - .2 and time.time() * 1000 - previous['positionAt'] < 2500)
                if self.station_continuation == id_:
                    # Repeated reports of the Station's native continuation while our next batch loads.
                    self.playback = {**managed_playback, 'volume': self.station_playback['volume'],
                        'playing': managed_playback['playing'] if self.advancing else self.station_playback['playing'],
                        'transitioning': self.advancing}
                elif near_end:
                    self.station_continuation = id_
                    self.playback = {**managed_playback, 'volume': self.station_playback['volume'], 'transitioning': True}
                    if not self.advancing:
                        self.spawn(self.continue_station_queue())
                else:
                    self.queue.owned = False
                    self.radio = None
                    self.station_continuation = None
                    emit('notice', {'message': 'Музыку переключили вне «Лада». Показываю Станцию; прежняя очередь сохранена.'})
        self.publish()

    async def continue_station_queue(self):
        await self.advance(1, automatic=True)

    def require_music(self):
        if not self.music.client:
            raise ValueError('Сначала войди в Яндекс Музыку.')

    async def stream(self, id_):
        self.require_music()
        values = await self.music.client.tracks_download_info(id_)
        candidates = [d for d in values if d.codec == 'mp3'] or [d for d in values if d.codec == 'aac']
        if not candidates:
            raise ValueError('Яндекс не вернул доступный поток для этого трека.')
        info = max(candidates, key=lambda d: d.bitrate_in_kbps)
        url = await info.get_direct_link_async()
        if url.startswith('http://'):
            url = 'https://' + url[7:]
        if not url.startswith('https://'):
            raise ValueError('Яндекс вернул неподдерживаемый адрес аудио.')
        return url

    async def report_listen(self):
        if self.demo or self.output != 'local' or not self.music.client or not self.playback['track'] or self.total_played < .5:
            return
        t, play_id, total, end = copy.deepcopy(self.playback['track']), self.play_id, self.total_played, self.current_position()
        try:
            await self.music.client.play_audio(t['id'], 'lad-desktop', t.get('albumId') or '0',
                play_id=play_id, track_length_seconds=round(t['duration']),
                total_played_seconds=total, end_position_seconds=end)
        except Exception:
            self.feedback_error = 'Не удалось обновить историю прослушивания в Яндекс Музыке.'

    async def start_track(self, index, position=0, paused=False):
        epoch = self.operation_epoch
        if not 0 <= index < len(self.queue.tracks):
            raise ValueError('Выбери трек в очереди.')
        await self.report_listen()
        self.require_operation(epoch)
        t = self.queue.tracks[index]
        await self.check_content(t)
        self.require_operation(epoch)
        if t.get('available') is False:
            raise ValueError('Этот трек недоступен для воспроизведения.')
        if self.output == 'station' and not self.demo and self.connection != 'connected':
            raise ValueError('Сначала подключи Станцию в меню устройств.')
        url = await self.stream(t['id']) if self.output == 'local' and not self.demo else None
        self.require_operation(epoch)
        self.expected = track_key(t['id'])
        self.station_continuation = None
        self.starting_until = time.monotonic() + 12
        station_state = None
        if self.output == 'station' and not self.demo:
            station_state = await self.station.play_track(t['id'], position, paused)
            self.require_operation(epoch)
        self.queue.index, self.queue.owned = index, True
        self.generation += 1
        self.play_id = str(uuid.uuid4())
        self.total_played = 0
        self.last_local_report = None
        self.playback = {'track': t, 'playing': not paused if self.demo else False,
            'position': position, 'duration': t['duration'], 'volume': self.playback['volume'],
            'positionAt': time.time() * 1000, 'transitioning': not self.demo}
        if station_state:
            p = station_state.get('playerState') or {}
            self.playback.update({'playing': bool(station_state.get('playing')), 'position': float(p.get('progress') or 0),
                                  'duration': float(p.get('duration') or t['duration']), 'transitioning': False})
        if url:
            emit('local', {'action': 'load', 'url': url, 'track': t, 'position': position, 'paused': paused,
                           'generation': self.generation, 'volume': 1 if self.sync_volume else self.playback['volume']})
        self.publish(True)
        if self.radio and not self.demo:
            self.spawn(self.radio_feedback('trackStarted', t, radio=dict(self.radio)))

    async def advance(self, direction=1, automatic=False):
        async with self.lock:
            if self.advancing:
                return
            self.advancing = True
            try:
                if self.output == 'station' and not self.queue.owned and not self.demo:
                    if self.block_explicit:
                        raise ContentRestricted('Для фильтра 18+ запусти музыку из каталога или сохранённой очереди Лада.')
                    if not self.station:
                        raise ValueError('Станция не подключена.')
                    await self.station.command({'command': 'next' if direction > 0 else 'prev'})
                    return
                current = self.queue.current
                if self.radio and current and direction > 0 and not self.demo:
                    await self.radio_feedback('trackFinished' if automatic else 'skip', current,
                        radio=dict(self.radio), played=self.current_position() if self.output == 'station' else self.total_played)
                # A finite queue ends; a radio queue must ask for more, including when
                # its entire remaining tail is blocked by the local explicit filter.
                refills = 0
                if self.radio and direction > 0 and self.queue.index >= len(self.queue.tracks) - 2:
                    await self.topup_radio(max_batches=1)
                    refills += 1
                    if not self.queue.owned:
                        return
                checked = set()
                while True:
                    probe = copy.deepcopy(self.queue)
                    next_ = probe.next_index(direction, automatic)
                    walked = set()
                    while next_ is not None and next_ not in walked:
                        walked.add(next_)
                        if next_ not in checked:
                            checked.add(next_)
                            try:
                                await self.start_track(next_)
                                return
                            except ContentRestricted:
                                pass
                        probe.index = next_
                        next_ = probe.next_index(direction, automatic=False)
                    if self.radio and direction > 0 and refills < 4 and not self.demo:
                        await self.topup_radio(max_batches=1)
                        refills += 1
                        if not self.queue.owned:
                            return
                        # Walk past already checked items to the newly appended batch.
                        continue
                    if self.radio and direction > 0:
                        await self.control('pause')
                        self.publish(True)
                        emit('notice', {'message': 'Моя волна пока не вернула подходящие треки. Нажми «Следующий», чтобы продолжить поиск.'})
                    elif checked:
                        await self.control('pause')
                        emit('notice', {'message': 'В очереди нет следующих треков без маркировки 18+.'})
                    elif automatic:
                        await self.control('pause')
                        self.queue.owned = False
                        self.radio = None
                        self.publish(True)
                    return
            finally:
                self.advancing = False

    async def control(self, action, value=None):
        if action in ('next', 'previous'):
            return await self.advance(1 if action == 'next' else -1)
        if action == 'toggle':
            action = 'pause' if self.playback['playing'] else 'play'
        if action == 'pause':
            self.operation_epoch += 1
        if action == 'play' and self.queue.owned and self.queue.current and track_key((self.playback.get('track') or {}).get('id')) != track_key(self.queue.current['id']):
            return await self.start_track(self.queue.index)
        if action == 'play' and self.playback.get('track'):
            epoch = self.operation_epoch
            await self.check_content(self.playback['track'])
            self.require_operation(epoch)
        if action == 'repeat':
            if value not in ('off', 'one', 'all'):
                raise ValueError('Неизвестный режим повтора.')
            self.queue.repeat = value
            self.publish(True)
            return
        if action == 'shuffle':
            self.queue.shuffle(bool(value))
            self.publish(True)
            return
        if not self.playback.get('track') and action in ('play', 'seek'):
            if action == 'play' and self.queue.tracks:
                return await self.start_track(max(0, self.queue.index))
            raise ValueError('Сначала выбери музыку.')
        if action in ('seek', 'volume'):
            value = float(value)
            if not math.isfinite(value):
                raise ValueError('Некорректное значение.')
            value = max(0, min(value, 1 if action == 'volume' else self.playback['duration']))
        if self.demo:
            self.playback['position'] = self.current_position()
            self.playback['positionAt'] = time.time() * 1000
            if action == 'seek':
                self.playback['position'] = value
            elif action == 'volume':
                self.playback['volume'] = value
            else:
                self.playback['playing'] = action == 'play'
        elif self.output == 'station':
            payload = {'play': {'command': 'play'}, 'pause': {'command': 'stop'},
                       'seek': {'command': 'rewind', 'position': value},
                       'volume': {'command': 'setVolume', 'volume': round(value or 0, 2)}}.get(action)
            if not payload or not self.station or self.connection != 'connected':
                raise ValueError('Станция не подключена.')
            await self.station.command(payload)
        else:
            emit('local', {'action': action, 'value': 1 if action == 'volume' and self.sync_volume else value, 'generation': self.generation})
            if action == 'volume':
                self.playback['volume'] = value
        if action == 'volume' and self.sync_volume and not self.demo:
            self.playback['volume'] = value
            self.volume_seen = self.volume_device.write(value)
        self.publish()

    async def radio_feedback(self, kind, t, radio=None, played=None):
        context = radio if radio is not None else self.radio
        if not context:
            return
        try:
            kwargs = {'track_id': t['id'], 'batch_id': context.get('batch')}
            if kind in ('trackFinished', 'skip'):
                kwargs['total_played_seconds'] = self.total_played if played is None else played
            await self.music.client.rotor_station_feedback(context['id'], kind, **kwargs)
        except Exception:
            self.feedback_error = 'Не удалось отправить обратную связь «Моей волне».'
            self.publish()

    async def radio_batch(self, context, cursor=None):
        next_cursor = context.get('cursor') or cursor
        result = await self.music.client.rotor_station_tracks(context['id'], **({'queue': next_cursor} if next_cursor else {}))
        candidates = []
        for item in result.sequence:
            if not item.track:
                continue
            candidate = track(item.track)
            context['cursor'] = candidate['id']
            if not candidate['available']:
                continue
            if self.block_explicit and candidate.get('explicit'):
                # Move the recommendation cursor beyond filtered songs instead of
                # repeatedly requesting the same explicit-only batch.
                await self.radio_feedback('skip', candidate,
                    radio={'id': context['id'], 'batch': result.batch_id}, played=0)
                continue
            candidates.append(candidate)
        context['batch'] = result.batch_id
        return candidates

    async def topup_radio(self, max_batches=4):
        context = self.radio
        if not context:
            return
        for _ in range(max_batches):
            candidates = await self.radio_batch(context, self.queue.current['id'] if self.queue.current else None)
            if self.radio is not context or not self.queue.owned:
                return
            existing = {track_key(t['id']) for t in self.queue.tracks[max(0, self.queue.index):]}
            additions = []
            for candidate in candidates:
                key = track_key(candidate['id'])
                if key not in existing:
                    additions.append(candidate)
                    existing.add(key)
            self.queue.add(additions)
            self.publish(True)
            if additions:
                return

    def capture_device_volume(self):
        if self.volume_backup is None and self.volume_device is not None:
            capture = getattr(self.volume_device, 'capture', self.volume_device.read)
            self.volume_backup = capture()

    def restore_device_volume(self):
        if self.volume_backup is None or self.volume_device is None:
            return
        saved, self.volume_backup = self.volume_backup, None
        try:
            restore = getattr(self.volume_device, 'restore', self.volume_device.write)
            self.volume_seen = restore(saved)
        except Exception:
            emit('notice', {'message': 'Не удалось восстановить громкость прежнего аудиовыхода: проверь, подключён ли он.'})
        self.volume_route = None

    async def volume_tick(self):
        if not self.sync_volume or self.demo or self.volume_device is None:
            return
        if self.output == 'station' and self.connection != 'connected':
            self.restore_device_volume()
            return
        if self.output == 'local': self.restore_device_volume()
        route = (self.output, self.device_id if self.output == 'station' else None)
        level = self.volume_device.read()
        if route != self.volume_route:
            self.volume_route = route
            self.volume_seen = level
            if self.output == 'local':
                self.playback['volume'] = level
                emit('local', {'action': 'volume', 'value': 1})
                self.publish()
            elif self.connection == 'connected':
                self.capture_device_volume()
                self.volume_seen = self.volume_device.write(self.playback['volume'])
            return
        if self.volume_seen is not None and abs(level - self.volume_seen) > .004:
            self.volume_seen = level
            await self.control('volume', level)
        elif abs(self.playback['volume'] - level) > .009:
            self.volume_seen = self.volume_device.write(self.playback['volume'])

    async def tick(self):
        while True:
            await asyncio.sleep(.2)
            try:
                await self.volume_tick()
            except Exception:
                self.restore_device_volume()
                self.sync_volume = False
                emit('notice', {'message': 'Синхронизация громкости недоступна для этого аудиовыхода.'})
                emit('local', {'action': 'volume', 'value': self.playback['volume']})
                self.publish(True)
            if self.demo and self.playback['playing']:
                if self.current_position() >= self.playback['duration']:
                    await self.advance(automatic=True)
                self.publish()
            elif self.output == 'station' and self.queue.owned and self.connection == 'connected' and not self.advancing:
                p = self.playback
                # Require fresh device evidence. Never advance a sleeping/disconnected Station on a wall-clock timer.
                fresh = time.monotonic() - self.last_station_at < 2.5
                finished = p['duration'] > 0 and self.current_position() >= p['duration'] - .12
                if fresh and finished and (p['playing'] or p['position'] >= p['duration']) and not p['transitioning'] and time.monotonic() > self.starting_until:
                    self.spawn(self.advance(automatic=True))

    async def rpc(self, method, p):
        epoch = self.operation_epoch
        if method == 'prepareShutdown':
            self.restore_device_volume()
            return True
        if method == 'setVolumeSync':
            enabled = p.get('enabled') is True
            if enabled and not self.demo:
                if self.volume_device is None:
                    from system_volume import native_volume
                    self.volume_device = native_volume()
                self.volume_seen = self.volume_device.read()
                if self.output == 'local': self.playback['volume'] = self.volume_seen
                else:
                    self.capture_device_volume()
                    self.volume_seen = self.volume_device.write(self.playback['volume'])
            if not enabled: self.restore_device_volume()
            self.sync_volume = enabled
            self.volume_route = (self.output, self.device_id if self.output == 'station' else None)
            emit('local', {'action': 'volume', 'value': 1 if enabled else self.playback['volume']})
            self.publish(True)
            return self.snapshot()
        if method == 'waveStream':
            # A read-only analysis stream for the current remote track only.
            current = self.playback.get('track')
            id_ = str(p.get('trackId') or '')
            if self.demo or self.output != 'station' or self.connection != 'connected' or not current or str(current['id']) != id_:
                raise ValueError('Поток анализа больше не соответствует треку Станции.')
            url = await self.stream(id_)
            self.require_operation(epoch)
            if self.connection != 'connected' or self.output != 'station' or not self.playback.get('track') or str(self.playback['track']['id']) != id_:
                raise ActionSuperseded('Трек Станции изменился.')
            return {'url': url}
        if method == 'snapshot':
            return self.snapshot()
        if method == 'boot':
            saved = p.get('saved') or {}
            self.block_explicit = saved.get('blockExplicit') is True
            if saved.get('syncVolume') is True:
                try:
                    from system_volume import native_volume
                    self.volume_device = native_volume()
                    self.volume_seen = self.volume_device.read()
                    self.sync_volume = True
                except Exception:
                    emit('notice', {'message': 'Системная громкость недоступна. Синхронизация выключена.'})
            q = saved.get('queue') or {}
            self.queue.tracks = q.get('tracks', [])
            self.queue.original = self.queue.tracks.copy()
            self.queue.index = min(max(-1, int(q.get('index', -1))), len(self.queue.tracks) - 1)
            self.queue.repeat = q.get('repeat', 'off')
            self.queue.source = q.get('source', 'Сохранённая очередь')
            self.hosts = saved.get('hosts', {})
            credentials = p.get('credentials') or {}
            self.cookie = credentials.get('cookie', '')
            self.refresh_token = credentials.get('refreshToken', '')
            token = credentials.get('musicToken', '')
            if token:
                try:
                    await self.login_music(token)
                except Exception:
                    emit('notice', {'message': 'Сохранённый вход в Музыку истёк. Войди заново.'})
            if self.cookie or self.music_token:
                try:
                    await self.refresh_devices()
                    if saved.get('deviceId'):
                        await self.connect(saved['deviceId'])
                except Exception as e:
                    emit('notice', {'message': safe_error(e)})
            self.publish()
            return self.snapshot()
        if method == 'setContentFilter':
            if not isinstance(p.get('enabled'), bool):
                raise ValueError('Некорректное значение фильтра.')
            if self.block_explicit != p['enabled']:
                self.operation_epoch += 1
            self.block_explicit = p['enabled']
            self.publish(True)
            if self.block_explicit and self.playback.get('track') and self.playback['playing']:
                try:
                    await self.check_content(self.playback['track'])
                except Exception:
                    await self.control('pause')
            return self.snapshot()
        if method == 'demo':
            self.restore_device_volume()
            self.operation_epoch += 1
            if self.station:
                await self.station.close()
                self.station = None
            emit('local', {'action': 'pause'})
            self.demo = True
            self.demo_albums = []
            self.profile = {'name': 'Режим знакомства', 'uid': 'demo', 'plus': True}
            self.output = 'local'
            self.devices = [{'id': 'demo-midi', 'name': 'Станция Миди · пример', 'platform': 'yandexmidi'}]
            self.queue.replace(DEMO_TRACKS, 0, 'Лад Session · пример')
            self.radio = None
            self.playback.update({'track': DEMO_TRACKS[0], 'playing': False, 'position': 0, 'duration': 216, 'transitioning': False})
            self.publish()
            return self.snapshot()
        if method == 'logout':
            self.restore_device_volume()
            self.operation_epoch += 1
            emit('local', {'action': 'pause'})
            if self.station:
                await self.station.close()
                self.station = None
            self.demo = False
            self.music = Music()
            self.profile = None
            self.cookie = self.music_token = self.refresh_token = ''
            self.devices = []
            self.hosts = {}
            self.device_id = None
            self.output = 'local'
            self.connection = 'disconnected'
            self.queue = Queue()
            self.radio = None
            self.playback.update({'track': None, 'playing': False, 'position': 0, 'duration': 0})
            self.credentials()
            self.publish(True)
            return self.snapshot()
        if method == 'loginToken':
            profile = await self.login_music(p['token'].strip())
            self.spawn(self.refresh_devices())
            return profile
        if method == 'stationSession':
            return await self.station_session(p['cookie'])
        if method == 'authStart':
            self.auth_client = ClientAsync()
            self.auth_code = await self.auth_client.request_device_code(device_name='Лад · Music for Station')
            self.auth_deadline = time.monotonic() + self.auth_code.expires_in
            return {'url': self.auth_code.verification_url, 'code': self.auth_code.user_code,
                    'interval': self.auth_code.interval or 5, 'expiresIn': self.auth_code.expires_in}
        if method == 'authPoll':
            if not self.auth_code or time.monotonic() > self.auth_deadline:
                raise ValueError('Код входа истёк. Запроси новый.')
            token = await self.auth_client.poll_device_token(self.auth_code.device_code)
            if token:
                self.refresh_token = token.refresh_token or ''
                await self.login_music(token.access_token)
                self.auth_code = None
                self.spawn(self.refresh_devices())
                return {'done': True}
            return {'done': False}
        if method == 'devices':
            if self.demo:
                return [{'id': 'demo-midi', 'name': 'Станция Миди · пример', 'platform': 'yandexmidi', 'host': None}]
            return await self.refresh_devices()
        if method == 'connect':
            if self.demo:
                self.devices = [{'id': 'demo-midi', 'name': 'Станция Миди · пример', 'platform': 'yandexmidi'}]
                self.device_id = 'demo-midi'
                self.connection = 'connected'
                self.publish()
                return self.snapshot()
            return await self.connect(p['id'], p.get('host'))
        if method == 'output':
            target = p['output']
            if target not in ('local', 'station'):
                raise ValueError('Неизвестное устройство.')
            if target == self.output:
                return self.snapshot()
            if self.output == 'station' and target == 'local' and self.playback['track'] and not self.queue.owned and not self.demo:
                self.require_music()
                values = await self.music.client.tracks(self.playback['track']['id'])
                if values:
                    self.queue.replace([track(values[0])], 0, 'Со Станции')
            if target == 'station' and not self.demo and self.connection != 'connected':
                raise ValueError('Сначала выбери и подключи Станцию.')
            old = self.output
            pos, playing = self.current_position(), self.playback['playing']
            previous_playback = copy.deepcopy(self.playback)
            if old == 'local': self.local_volume_backup = self.playback['volume']
            if target == 'local':
                self.restore_device_volume()
                if self.sync_volume and self.volume_device is not None:
                    self.playback['volume'] = self.volume_device.read()
                elif self.local_volume_backup is not None:
                    self.playback['volume'] = self.local_volume_backup
            if old == 'local':
                emit('local', {'action': 'pause'})
            self.output = target
            try:
                if p.get('transfer', True) and self.queue.current and self.queue.owned:
                    await self.start_track(self.queue.index, pos, paused=not playing)
                elif target == 'station' and self.station_playback:
                    self.playback = self.station_playback.copy()
                else:
                    self.playback.update({'track': None, 'playing': False, 'position': 0, 'duration': 0})
            except Exception:
                if self.operation_epoch != epoch:
                    raise
                self.output = old
                self.playback = previous_playback
                self.playback.update({'position': pos, 'positionAt': time.time() * 1000})
                if old == 'local' and playing:
                    emit('local', {'action': 'play'})
                self.publish()
                raise
            if old == 'local':
                emit('local', {'action': 'pause'})
            elif not self.demo and self.station and p.get('transfer', True) and playing:
                await self.station.command({'command': 'stop'})
            self.publish(True)
            return self.snapshot()
        if method == 'localState':
            if self.output != 'local' or self.demo or p.get('generation') != self.generation:
                return None
            self.playback.update({'playing': bool(p.get('playing')), 'position': max(0, float(p.get('position', 0))),
                'duration': float(p.get('duration') or self.playback['duration']), 'positionAt': time.time() * 1000,
                'transitioning': bool(p.get('loading'))})
            now = time.monotonic()
            if self.last_local_report is not None and self.playback['playing']:
                self.total_played += min(2, now - self.last_local_report)
            self.last_local_report = now
            self.publish()
            return None
        if method == 'localEnded':
            if self.output == 'local' and p.get('generation') == self.generation:
                await self.advance(automatic=True)
            return None
        if method == 'localError':
            if p.get('generation') == self.generation:
                self.playback.update({'playing': False, 'transitioning': False})
                self.publish()
                emit('notice', {'message': 'Не удалось воспроизвести аудио. Попробуй запустить трек ещё раз; проверь интернет и Плюс.'})
            return None
        if method == 'control':
            await self.control(p['action'], p.get('value'))
            return self.snapshot()
        if method == 'play':
            tracks = p.get('tracks') or []
            if p.get('collection') == 'liked' and not self.demo:
                first = await self.music.liked(0, 5000)
                tracks = first['tracks']
            if len(tracks) > 5000 or any(not re_valid_track(t, self.demo) for t in tracks):
                raise ValueError('Некорректный список треков.')
            async with self.lock:
                self.require_operation(epoch)
                old = copy.deepcopy(self.queue)
                old_radio = self.radio
                self.radio = None
                try:
                    self.queue.replace(tracks, int(p.get('index', 0)), str(p.get('source', 'Очередь Лад'))[:200])
                    await self.start_track(self.queue.index)
                except Exception:
                    if self.operation_epoch == epoch:
                        self.queue, self.radio = old, old_radio
                        self.publish()
                    raise
            return self.snapshot()
        if method == 'queue':
            async with self.lock:
                action = p['action']
                if action == 'jump':
                    index = int(p['index'])
                    if not 0 <= index < len(self.queue.tracks):
                        raise ValueError('Выбери трек в очереди.')
                    if self.radio and self.queue.current and index != self.queue.index and not self.demo:
                        await self.radio_feedback('skip', self.queue.current, radio=dict(self.radio),
                            played=self.current_position() if self.output == 'station' else self.total_played)
                    # Selecting an occurrence changes position within the same Wave, not its source.
                    await self.start_track(index)
                    if self.radio and index >= len(self.queue.tracks) - 2 and not self.demo:
                        try:
                            await self.topup_radio()
                        except Exception:
                            emit('notice', {'message': 'Не удалось пополнить Мою волну. Текущий трек продолжает играть; «Следующий» повторит запрос.'})
                elif action == 'move':
                    self.queue.move(int(p['from']), int(p['to']))
                elif action == 'remove':
                    self.queue.remove(int(p['index']))
                elif action == 'add':
                    tracks = p['tracks']
                    if len(self.queue.tracks) + len(tracks) > 5000 or any(not re_valid_track(t, self.demo) for t in tracks):
                        raise ValueError('Слишком большая очередь или некорректный трек.')
                    self.queue.add(tracks, bool(p.get('next')))
                elif action == 'clear':
                    # Keep current song, clear only the upcoming queue.
                    current = self.queue.current
                    self.queue.tracks = [current] if current else []
                    self.queue.index = 0 if current else -1
                    self.radio = None
                self.publish(True)
            return self.snapshot()
        if method == 'lyrics':
            if self.demo:
                return {'synced': True, 'lines': [{'time': i * 32, 'text': t} for i, t in enumerate(DEMO_LINES)],
                        'writers': ['Демонстрационный текст Лад'], 'provider': None}
            self.require_music()
            return await self.music.lyrics(p['id'])
        if self.demo:
            if method == 'search':
                matches = [t for t in DEMO_TRACKS if p['query'].lower() in (t['title'] + ' ' + t['artist']).lower()]
                return {'tracks': matches, 'albums': [], 'playlists': [], 'artists': [], 'page': 0, 'total': len(matches)}
            if method == 'liked':
                return {'tracks': [t for t in DEMO_TRACKS if t.get('liked')], 'total': 3}
            if method == 'library':
                return {'playlists': demo_cards(), 'albums': self.demo_albums}
            if method == 'home':
                return {'cards': demo_cards(), 'tracks': DEMO_TRACKS, 'tracksTitle': 'Продолжить слушать'}
            if method in ('collection', 'link'):
                return {'title': ('Тихий город' if p.get('type') == 'album' else 'Лад Session'), 'tracks': DEMO_TRACKS}
            if method == 'albumLike':
                if not isinstance(p.get('liked'), bool):
                    raise ValueError('Некорректное значение лайка.')
                self.demo_albums = [a for a in self.demo_albums if a['id'] != p['id']]
                if p['liked']:
                    self.demo_albums.append({**p.get('card', {}), 'id': p['id'], 'type': 'album'})
                return True
            if method == 'like':
                for t in DEMO_TRACKS:
                    if t['id'] == p['id']:
                        t['liked'] = p['liked']
                return True
            if method == 'wave':
                self.queue.replace(DEMO_TRACKS, 0, 'Моя волна · пример')
                await self.start_track(0)
                return self.snapshot()
        self.require_music()
        if method == 'waveSettings':
            async with self.lock:
                result = await self.music.wave_settings(p.get('settings'))
                if p.get('settings') is not None and self.radio and self.queue.owned and self.queue.current:
                    current = self.queue.current
                    context = {'id': 'user:onyourwave'}
                    additions = []
                    for _ in range(4):
                        candidates = await self.radio_batch(context, current['id'])
                        additions = [t for t in candidates if track_key(t['id']) != track_key(current['id'])]
                        if additions:
                            break
                    self.require_operation(epoch)
                    if not additions:
                        raise ValueError('Настройки сохранены. Яндекс пока не вернул новые треки; прежняя очередь сохранена.')
                    self.queue.tracks = self.queue.tracks[:self.queue.index + 1] + additions
                    self.queue.shuffled = False
                    self.queue.original = []
                    self.radio = context
                    self.publish(True)
                return result
        if method == 'search':
            return await self.music.search(p['query'][:300], int(p.get('page', 0)), p.get('type', 'all'))
        if method == 'liked':
            return await self.music.liked(max(0, int(p.get('offset', 0))), min(100, max(1, int(p.get('limit', 100)))))
        if method == 'home':
            return await self.music.home()
        if method == 'library':
            return await self.music.library()
        if method == 'collection':
            return await self.music.collection(p['type'], p['id'], p.get('owner'), int(p.get('page', 0)))
        if method == 'link':
            return await self.music.from_link(p['url'])
        if method == 'albumLike':
            if not isinstance(p.get('liked'), bool) or not str(p.get('id', '')).isdigit():
                raise ValueError('Некорректный альбом или значение лайка.')
            result = await (self.music.client.users_likes_albums_add(p['id']) if p['liked'] else self.music.client.users_likes_albums_remove(p['id']))
            if not result:
                raise ValueError('Яндекс Музыка не сохранила лайк альбома. Попробуй ещё раз.')
            return True
        if method == 'like':
            result = await (self.music.client.users_likes_tracks_add(p['id']) if p['liked'] else self.music.client.users_likes_tracks_remove(p['id']))
            if not result:
                raise ValueError('Яндекс не подтвердил изменение отметки.')
            for t in self.queue.tracks:
                if t['id'] == p['id']:
                    t['liked'] = p['liked']
            self.publish(True)
            return True
        if method == 'wave':
            async with self.lock:
                context = {'id': 'user:onyourwave'}
                tracks = []
                for _ in range(4):
                    tracks = await self.radio_batch(context)
                    if tracks:
                        break
                if not tracks:
                    raise ValueError('Моя волна пока не вернула подходящие треки. Попробуй ещё раз.')
                old, old_radio = copy.deepcopy(self.queue), self.radio
                self.require_operation(epoch)
                self.queue.replace(tracks, 0, 'Моя волна')
                self.radio = context
                try:
                    await self.start_track(0)
                except Exception:
                    if self.operation_epoch == epoch:
                        self.queue, self.radio = old, old_radio
                    raise
                self.spawn(self.radio_feedback('radioStarted', tracks[0]))
            return self.snapshot()
        raise ValueError('Неизвестное действие.')

    async def close(self):
        self.restore_device_volume()
        if self.station:
            await self.station.close()
        for t in self.tasks:
            t.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        await self.session.close()


def demo_cards():
    return [{'type': 'playlist', 'id': f'demo-{i}', 'title': t, 'subtitle': 'Лад Session · пример', 'art': i, 'owner': 'demo'}
            for i, t in enumerate(['Плейлист дня', 'Тихий город', 'После полуночи', 'Ближе к свету'])]


def re_valid_track(t, demo=False):
    import re
    return isinstance(t, dict) and bool(re.fullmatch(r'\d+(:\d+)?' if not demo else r'demo-\d+', str(t.get('id', '')))) and isinstance(t.get('duration'), (int, float))


async def rpc_lines():
    # Windows Proactor cannot register an ordinary inherited stdin pipe using connect_read_pipe.
    # A single reader thread preserves the asynchronous command loop and works with packaged stdio.
    while True:
        line = await asyncio.to_thread(sys.stdin.buffer.readline, 8 * 1024 * 1024)
        if not line:
            return
        yield line


async def main():
    app = App()
    await app.setup()
    emit('ready', {})
    async def handle(data):
        try:
            value = await app.rpc(data['method'], data.get('params') or {})
            print(json.dumps({'id': data['id'], 'result': value}, ensure_ascii=False), flush=True)
        except ActionSuperseded:
            print(json.dumps({'id': data.get('id'), 'result': app.snapshot()}, ensure_ascii=False), flush=True)
        except Exception as error:
            print(json.dumps({'id': data.get('id'), 'error': safe_error(error)}, ensure_ascii=False), flush=True)
    pending = set()
    try:
        async for line in rpc_lines():
            try:
                data = json.loads(line)
                task = asyncio.create_task(handle(data))
                pending.add(task)
                task.add_done_callback(pending.discard)
            except (json.JSONDecodeError, KeyError):
                continue
    finally:
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)
        await app.close()


if __name__ == '__main__':
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(encoding='utf-8')
    asyncio.run(main())
