# SPDX-License-Identifier: AGPL-3.0-only
"""Yandex Music catalog, library and lyrics, using yandex-music (LGPL-3.0)."""
import asyncio
import re
from yandex_music import ClientAsync
from core import cover_url, parse_lrc


def track(t):
    return {'id': str(t.id), 'title': t.title or 'Без названия',
            'artist': ', '.join(a.name for a in (t.artists or [])) or 'Неизвестный исполнитель',
            'artistId': str(t.artists[0].id) if t.artists else None,
            'artists': [{'id': str(a.id), 'name': a.name} for a in (t.artists or [])],
            'albumId': str(t.albums[0].id) if t.albums else None,
            'album': t.albums[0].title if t.albums else '',
            'duration': (t.duration_ms or 0) / 1000, 'cover': cover_url(t.cover_uri),
            'available': t.available is not False,
            'explicit': bool(getattr(t, 'explicit', False) or t.content_warning == 'explicit')}


def playlist(p):
    cover = getattr(p, 'cover', None)
    uri = getattr(cover, 'uri', None) or next(iter(getattr(cover, 'items_uri', None) or []), None)
    owner = getattr(p, 'owner', None)
    return {'type': 'playlist', 'id': str(p.kind), 'owner': str(owner.uid) if owner else '',
            'ownerLogin': getattr(owner, 'login', None) if owner else None,
            'title': p.title, 'subtitle': f'{p.track_count or 0} треков', 'cover': cover_url(uri)}


def album(a):
    return {'type': 'album', 'id': str(a.id), 'title': a.title,
            'subtitle': ', '.join(x.name for x in (a.artists or [])), 'cover': cover_url(a.cover_uri)}


class Music:
    def __init__(self):
        self.client = None
        self.cache = {}

    async def login(self, token):
        client = await ClientAsync(token).init()
        self.client = client
        status = await client.account_status()
        return {'name': status.account.display_name or status.account.login,
                'uid': str(status.account.uid), 'plus': bool(status.plus and status.plus.has_plus)}

    async def search(self, query, page=0, type_='all'):
        result = await self.client.search(query, type_=type_, page=page)
        def items(name):
            return getattr(getattr(result, name, None), 'results', None) or []
        return {'tracks': [track(t) for t in items('tracks')],
                'albums': [album(a) for a in items('albums')],
                'playlists': [playlist(p) for p in items('playlists')],
                'artists': [{'id': str(a.id), 'type': 'artist', 'title': a.name,
                    'subtitle': 'Исполнитель', 'cover': cover_url(getattr(a.cover, 'uri', None))} for a in items('artists')],
                'page': page, 'total': getattr(getattr(result, 'tracks', None), 'total', 0)}

    async def liked(self, offset=0, limit=100):
        likes = await self.client.users_likes_tracks()
        short = (likes.tracks if likes else []) or []
        ids = [str(t.id) for t in short[offset:offset + limit]]
        found = []
        for start in range(0, len(ids), 150):
            found.extend(await self.client.tracks(ids[start:start + 150]))
        by_id = {str(t.id): t for t in found}
        return {'tracks': [{**track(by_id[id_]), 'liked': True} for id_ in ids if id_ in by_id],
                'total': len(short), 'offset': offset}

    async def library(self):
        own, liked, saved = await asyncio.gather(self.client.users_playlists_list(),
            self.client.users_likes_albums(), self.client.users_likes_playlists(), return_exceptions=True)
        if all(isinstance(x, Exception) for x in (own, liked, saved)):
            raise own
        albums = [album(x.album) for x in (liked if not isinstance(liked, Exception) else []) if getattr(x, 'album', None)]
        playlists = [playlist(p) for p in (own if not isinstance(own, Exception) else [])]
        for item in (saved if not isinstance(saved, Exception) else []):
            if getattr(item, 'playlist', None):
                candidate = playlist(item.playlist)
                if not any(p['id'] == candidate['id'] and p['owner'] == candidate['owner'] for p in playlists):
                    playlists.append(candidate)
        return {'playlists': playlists, 'albums': albums}

    async def home(self):
        result, history = await asyncio.gather(self.client.landing(['personalplaylists', 'new-releases']),
            self.client.music_history(full_models_count=30), return_exceptions=True)
        cards, tracks, seen = [], [], set()
        if not isinstance(result, Exception):
            for block in (result.blocks if result else []) or []:
                for entity in block.entities or []:
                    data = entity.data
                    if getattr(data, 'playlist', None):
                        cards.append(playlist(data.playlist))
                    elif getattr(data, 'kind', None) is not None:
                        cards.append(playlist(data))
        if not isinstance(history, Exception):
            for tab in (getattr(history, 'history_tabs', None) or [])[:3]:
                for group in tab.items or []:
                    for item in group.tracks or []:
                        t = getattr(item.data, 'full_model', None)
                        if t and hasattr(t, 'title') and str(t.id) not in seen:
                            seen.add(str(t.id)); tracks.append(track(t))
                        if len(tracks) >= 20:
                            break
                    if len(tracks) >= 20:
                        break
                if len(tracks) >= 20:
                    break
        title = 'Продолжить слушать'
        if not tracks:
            title = 'Сейчас в чарте'
            try:
                chart = await self.client.chart('russia')
                tracks = [track(x.track) for x in (chart.chart.tracks or [])[:20]] if chart and chart.chart else []
            except Exception:
                if not cards:
                    raise
        return {'cards': cards, 'tracks': tracks, 'tracksTitle': title}

    async def collection(self, kind, id_, owner=None, page=0):
        if kind == 'album':
            data = await self.client.albums_with_tracks(id_)
            return {'title': data.title, 'tracks': [track(t) for volume in (data.volumes or []) for t in volume], 'cover': cover_url(data.cover_uri)}
        if kind == 'playlist':
            data = await self.client.users_playlists(id_, user_id=owner)
            if not data:
                raise ValueError('Плейлист недоступен.')
            short = data.tracks or []
            # Some playlists have only IDs; request missing metadata in bounded batches.
            ids = [str(x.id) for x in short if not x.track]
            resolved = {}
            for start in range(0, len(ids), 150):
                for t in await self.client.tracks(ids[start:start + 150]):
                    resolved[str(t.id)] = t
            tracks = [track(x.track or resolved[str(x.id)]) for x in short if x.track or str(x.id) in resolved]
            return {'title': data.title, 'tracks': tracks, 'cover': playlist(data)['cover']}
        if kind == 'artist':
            info, data = await asyncio.gather(self.client.artists(id_), self.client.artists_tracks(id_, page=page, page_size=100))
            return {'title': info[0].name if info else 'Исполнитель', 'tracks': [track(t) for t in data.tracks or []], 'page': page}
        raise ValueError('Неизвестный тип коллекции.')

    async def from_link(self, url):
        # Only extract identifiers. Never fetch an arbitrary user-provided URL.
        from urllib.parse import urlparse, parse_qs
        parsed = urlparse(url)
        if parsed.scheme != 'https' or parsed.hostname not in ('music.yandex.ru', 'music.yandex.com', 'music.yandex.kz', 'music.yandex.by'):
            raise ValueError('Вставь ссылку на трек, альбом или плейлист Яндекс Музыки.')
        path = parsed.path
        if m := re.search(r'/track/(\d+)', path):
            return {'title': 'По ссылке', 'tracks': [track(t) for t in await self.client.tracks(m[1])]}
        if m := re.search(r'/album/(\d+)', path):
            return await self.collection('album', m[1])
        if m := re.search(r'/users/([^/]+)/playlists/(\d+)', path):
            return await self.collection('playlist', m[2], m[1])
        if m := re.search(r'/playlists/([a-zA-Z0-9-]+)', path):
            p = await self.client.playlists_list([m[1]])
            if p:
                return await self.collection('playlist', str(p[0].kind), str(p[0].owner.uid))
        raise ValueError('Этот формат ссылки пока не поддерживается. Найди запись через поиск.')

    async def lyrics(self, id_):
        if id_ in self.cache:
            return self.cache[id_]
        # Request timed lyrics first; a plain-text fallback must not invent timing.
        errors = []
        for format_ in ('LRC', 'TEXT'):
            try:
                response = await self.client.tracks_lyrics(id_, format_=format_)
                if response:
                    text = await response.fetch_lyrics_async()
                    timed = parse_lrc(text) if format_ == 'LRC' else []
                    result = {'lines': timed or [{'time': None, 'text': line} for line in text.splitlines()],
                              'synced': bool(timed), 'writers': response.writers or [],
                              'provider': getattr(response.major, 'name', None)}
                    self.cache[id_] = result
                    return result
            except Exception as error:
                from yandex_music.exceptions import NotFoundError, BadRequestError
                if not isinstance(error, (NotFoundError, BadRequestError)):
                    errors.append(error)
        if errors:
            raise errors[0]
        return {'lines': [], 'synced': False, 'writers': [], 'provider': None}
