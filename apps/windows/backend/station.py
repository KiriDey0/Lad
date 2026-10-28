# SPDX-License-Identifier: AGPL-3.0-only
"""Authenticated Glagol transport. TLS pinned to the owner's device certificate."""
from __future__ import annotations
import asyncio
import hashlib
import ipaddress
import json
import ssl
import time
import uuid
import aiohttp
from core import track_key
from zeroconf import ServiceStateChange
from zeroconf.asyncio import AsyncZeroconf, AsyncServiceBrowser, AsyncServiceInfo


async def discover(seconds: float = 3.0) -> dict:
    zc = AsyncZeroconf()
    found, tasks = {}, set()
    async def resolve(name):
        info = AsyncServiceInfo('_yandexio._tcp.local.', name)
        if await info.async_request(zc.zeroconf, 1500):
            props = {k.decode(): v.decode(errors='replace') if v else '' for k, v in info.properties.items()}
            addresses = info.parsed_scoped_addresses()
            host = next((a for a in addresses if ':' not in a), None)
            if host and props.get('deviceId'):
                found[props['deviceId']] = {'host': host, 'port': info.port, 'platform': props.get('platform', '')}
    def handler(zeroconf, service_type, name, state_change):
        if state_change != ServiceStateChange.Removed:
            task = asyncio.create_task(resolve(name))
            tasks.add(task)
            task.add_done_callback(tasks.discard)
    browser = AsyncServiceBrowser(zc.zeroconf, '_yandexio._tcp.local.', handlers=[handler])
    try:
        await asyncio.sleep(seconds)
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        return found
    finally:
        await browser.async_cancel()
        await zc.async_close()


def local_host(host: str) -> str:
    value = ipaddress.ip_address(host.strip())
    if not (value.is_private or value.is_link_local) or value.is_loopback or value.is_unspecified or value.is_multicast:
        raise ValueError('Укажи локальный IP Станции, например 192.168.1.25.')
    return str(value)


class Station:
    def __init__(self, session, auth_headers, device, on_state, on_connection, base_url="https://quasar.yandex.net"):
        self.session, self.auth_headers, self.device = session, auth_headers, device
        self.base_url = base_url
        self.on_state, self.on_connection = on_state, on_connection
        self.ws = None
        self.token = None
        self.pending = {}
        self.task = None
        self.ready = asyncio.Event()
        self.closed = False
        self.latest_state = None
        self.state_revision = 0
        self.state_changed = asyncio.Event()

    def receive_state(self, state):
        self.latest_state = state
        self.state_revision += 1
        self.state_changed.set()

    async def wait_state(self, predicate, after, timeout=8):
        async def wait():
            while True:
                self.state_changed.clear()
                if self.state_revision > after and self.latest_state and predicate(self.latest_state):
                    return self.latest_state
                await self.state_changed.wait()
        return await asyncio.wait_for(wait(), timeout)

    async def play_track(self, id_, position=0, paused=False):
        revision = self.state_revision
        await self.command({'command': 'playMusic', 'id': id_, 'type': 'track'})
        def ready(state):
            p = state.get('playerState') or {}
            return track_key(p.get('id')) == track_key(id_) and float(p.get('duration') or 0) > 0
        try:
            # playMusic may acknowledge the previous instance of the same track.
            # Wait until its new playback actually starts before applying rewind.
            state = await self.wait_state(lambda s: ready(s) and s.get('playing') and float(s['playerState'].get('progress') or 0) < 5, revision)
            if position:
                for attempt in range(2):
                    revision = self.state_revision
                    await self.command({'command': 'rewind', 'position': position})
                    try:
                        state = await self.wait_state(lambda s: ready(s) and abs(float(s['playerState'].get('progress') or 0) - position) < 3,
                                                      revision, timeout=4)
                        break
                    except asyncio.TimeoutError:
                        if attempt:
                            raise
            if paused:
                revision = self.state_revision
                await self.command({'command': 'stop'})
                state = await self.wait_state(lambda s: ready(s) and not s.get('playing'), revision, timeout=4)
            return state
        except asyncio.TimeoutError:
            raise ValueError('Станция не подтвердила продолжение с нужной позиции. Музыка на компьютере сохранена.') from None

    async def connect(self):
        self.task = asyncio.create_task(self._run())
        try:
            await asyncio.wait_for(self.ready.wait(), 12)
        except asyncio.TimeoutError:
            await self.close()
            raise ValueError('Станция не ответила. Проверь локальную сеть и IP-адрес.') from None

    async def _run(self):
        delay = 1
        while not self.closed:
            try:
                self.on_connection('connecting', None)
                async with self.session.get(self.base_url + '/glagol/token',
                    params={'device_id': self.device['id'], 'platform': self.device['platform']},
                    headers=self.auth_headers) as response:
                    data = await response.json(content_type=None)
                self.token = data.get('token')
                if not self.token:
                    raise ValueError('Сессия Яндекса истекла. Войди снова для управления Станцией.')
                security = self.device.get('glagol', {}).get('security', {})
                certificate = security.get('server_certificate')
                if not certificate:
                    raise ValueError('Яндекс не вернул сертификат Станции. Обнови список устройств.')
                der = ssl.PEM_cert_to_DER_cert(certificate)
                fingerprint = aiohttp.Fingerprint(hashlib.sha256(der).digest())
                host = local_host(self.device['host'])
                address = f'[{host}]' if ':' in host else host
                port = int(self.device.get('port', 1961))
                async with self.session.ws_connect(f'wss://{address}:{port}/', ssl=fingerprint, heartbeat=20) as ws:
                    self.ws = ws
                    await self._send({'command': 'softwareVersion'})
                    async for message in ws:
                        if message.type != aiohttp.WSMsgType.TEXT:
                            continue
                        data = json.loads(message.data)
                        request_id = data.get('requestId')
                        future = self.pending.get(request_id)
                        if future is not None and not future.done():
                            if str(data.get('status', 'SUCCESS')).upper() not in ('SUCCESS', 'OK'):
                                future.set_exception(ValueError('Станция отклонила команду.'))
                            else:
                                future.set_result(data)
                        if 'state' in data:
                            self.receive_state(data['state'])
                            self.ready.set()
                            delay = 1
                            self.on_connection('connected', None)
                            self.on_state(data['state'])
                self.ws = None
            except asyncio.CancelledError:
                break
            except Exception as error:
                reason = str(error) if isinstance(error, ValueError) else 'Соединение со Станцией потеряно. Переподключаюсь…'
                self.on_connection('disconnected', reason)
            finally:
                self.ws = None
                for future in list(self.pending.values()):
                    if not future.done():
                        future.set_exception(ValueError('Соединение со Станцией прервано.'))
            if not self.closed:
                self.on_connection('disconnected', 'Станция недоступна. Переподключаюсь…')
                await asyncio.sleep(delay)
                delay = min(30, delay * 2)

    async def _send(self, payload, id_=None):
        if not self.ws or self.ws.closed:
            raise ValueError('Станция не подключена.')
        await self.ws.send_json({'conversationToken': self.token, 'id': id_ or str(uuid.uuid4()),
                                 'payload': payload, 'sentTime': int(time.time() * 1000)})

    async def command(self, payload):
        request_id = str(uuid.uuid4())
        future = asyncio.get_running_loop().create_future()
        self.pending[request_id] = future
        try:
            await self._send(payload, request_id)
            return await asyncio.wait_for(future, 6)
        except asyncio.TimeoutError:
            raise ValueError('Станция не подтвердила команду. Проверь, изменилась ли музыка.') from None
        finally:
            self.pending.pop(request_id, None)

    async def close(self):
        self.closed = True
        if self.ws:
            await self.ws.close()
        if self.task:
            self.task.cancel()
            await asyncio.gather(self.task, return_exceptions=True)
