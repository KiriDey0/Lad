# SPDX-License-Identifier: AGPL-3.0-only
"""Лад's queue and timed lyrics. No network or account state in this module."""
from __future__ import annotations
import copy
import random
import re
from dataclasses import dataclass, field


def parse_lrc(text: str) -> list[dict]:
    offset = 0
    m = re.search(r'\[offset:([+-]?\d+)\]', text, re.I)
    if m:
        offset = int(m.group(1)) / 1000
    lines = []
    for line in text.replace('\ufeff', '').splitlines():
        stamps = list(re.finditer(r'\[(\d+):(\d{2})(?:[.:](\d{1,3}))?\]', line))
        if not stamps:
            continue
        value = re.sub(r'\[[^]]+\]', '', line).strip()
        value = re.sub(r'<\d+:\d+(?:\.\d+)?>', '', value).strip()
        for stamp in stamps:
            fraction = stamp.group(3) or '0'
            seconds = int(stamp.group(1)) * 60 + int(stamp.group(2)) + int(fraction) / 10 ** len(fraction)
            lines.append({'time': max(0, seconds + offset), 'text': value})
    return sorted(lines, key=lambda x: x['time'])


def track_key(value: object) -> str:
    return str(value or '').split(':')[0]


def cover_url(value: str | None) -> str | None:
    if not value:
        return None
    value = value.replace('%%', '600x600')
    return value if value.startswith('https://') else ('https://' + value if '://' not in value else None)


@dataclass
class Queue:
    tracks: list[dict] = field(default_factory=list)
    index: int = -1
    source: str = 'Очередь Лад'
    owned: bool = False
    repeat: str = 'off'
    shuffled: bool = False
    original: list[dict] = field(default_factory=list)

    def snapshot(self) -> dict:
        return copy.deepcopy({'tracks': self.tracks, 'index': self.index, 'source': self.source,
                              'owned': self.owned, 'repeat': self.repeat, 'shuffled': self.shuffled})

    @property
    def current(self) -> dict | None:
        return self.tracks[self.index] if 0 <= self.index < len(self.tracks) else None

    def replace(self, tracks: list[dict], index: int = 0, source: str = 'Очередь Лад'):
        if not tracks or not 0 <= index < len(tracks):
            raise ValueError('В очереди нет доступных треков.')
        self.tracks = copy.deepcopy(tracks)
        self.original = copy.deepcopy(tracks)
        self.index, self.source, self.owned, self.shuffled = index, source, True, False

    def next_index(self, direction: int = 1, automatic: bool = False) -> int | None:
        if not self.tracks:
            return None
        if automatic and self.repeat == 'one':
            return self.index
        target = self.index + direction
        if 0 <= target < len(self.tracks):
            return target
        return target % len(self.tracks) if self.repeat == 'all' else None

    def add(self, tracks: list[dict], next_: bool = False):
        start = max(0, self.index + 1) if next_ else len(self.tracks)
        self.tracks[start:start] = copy.deepcopy(tracks)
        self.original = copy.deepcopy(self.tracks)

    def remove(self, index: int):
        if not 0 <= index < len(self.tracks):
            raise ValueError('Трек уже удалён из очереди.')
        if index == self.index and self.owned:
            raise ValueError('Сначала переключи текущий трек, затем удали его.')
        self.tracks.pop(index)
        if index < self.index:
            self.index -= 1
        self.original = copy.deepcopy(self.tracks)

    def move(self, from_: int, to: int):
        if not (0 <= from_ < len(self.tracks) and 0 <= to < len(self.tracks)):
            raise ValueError('Некорректная позиция в очереди.')
        current = self.index
        order = list(range(len(self.tracks)))
        order.insert(to, order.pop(from_))
        self.tracks = [self.tracks[i] for i in order]
        self.index = order.index(current) if current >= 0 else -1
        self.original = copy.deepcopy(self.tracks)

    def shuffle(self, enabled: bool):
        if enabled == self.shuffled:
            return
        if enabled:
            self.original = copy.deepcopy(self.tracks)
            tail = self.tracks[self.index + 1:]
            random.shuffle(tail)
            self.tracks[self.index + 1:] = tail
        else:
            # Preserve the played prefix, including duplicate track occurrences.
            rest = self.original.copy()
            for played in self.tracks[:self.index + 1]:
                try:
                    rest.remove(played)
                except ValueError:
                    pass
            self.tracks[self.index + 1:] = rest
        self.shuffled = enabled
