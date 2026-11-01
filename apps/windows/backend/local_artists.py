# SPDX-License-Identifier: AGPL-3.0-only
"""Catalogue identities are individual participants, not a formatted credit line."""
import hashlib
import re
import unicodedata

FEATURE = re.compile(r'(?<=\S)(?:\s+[\[(]?\s*|[\[(]\s*)(?:feat(?:uring)?|ft)\.?\s+', re.I)
AMBIGUOUS = re.compile(r'\s*(?:,|\s&\s|\s[x×]\s|\s/\s)\s*', re.I)


def key(name):
    return ' '.join(unicodedata.normalize('NFKC', name).casefold().split())


def explicit_parts(value):
    marker = FEATURE.search(value)
    if marker and any(c in marker.group() for c in '([') and value.rstrip().endswith((')', ']')):
        value = value.rstrip()[:-1]
    return [p.strip() for p in re.split(r'[\x00;]+', FEATURE.sub(';', value)) if p.strip()]


def artist_values(tags, *names):
    for name in names:
        try:
            value = tags.get(name)
        except (ValueError, KeyError):
            continue
        if not value:
            continue
        value = getattr(value, 'text', value)
        values = value if isinstance(value, (list, tuple)) else [value]
        result = [str(v).strip()[:2000] for v in values if str(v).strip()]
        if result:
            return result[:64]
    return []


def normalize_catalog(tracks):
    # Commas, ampersands and slashes can belong to band names. Split them only
    # when the catalogue independently identifies a participant on that credit.
    known = set()
    for t in tracks:
        for value in t.get('artistValues') or [t.get('artist', '')]:
            for part in explicit_parts(value):
                if not AMBIGUOUS.search(part):
                    known.add(key(part))
    result = []
    for t in tracks:
        names = []
        seen = set()
        for value in t.get('artistValues') or [t.get('artist', '')]:
            for part in explicit_parts(value):
                pieces = [p.strip() for p in AMBIGUOUS.split(part) if p.strip()]
                if len(pieces) < 2 or not any(key(p) in known for p in pieces):
                    pieces = [part]
                for name in pieces:
                    canonical = key(name)
                    if canonical and canonical not in seen:
                        seen.add(canonical)
                        names.append({'id': 'local-artist-' + hashlib.sha256(canonical.encode()).hexdigest()[:32], 'name': name})
        if not names:
            name = 'Неизвестный исполнитель'
            names = [{'id': 'local-artist-' + hashlib.sha256(key(name).encode()).hexdigest()[:32], 'name': name}]
        aliases = list(dict.fromkeys([*t.get('artistAliases', []), t.get('artistId', '')]))
        result.append({**t, 'artists': names, 'artistId': names[0]['id'], 'artistAliases': [a for a in aliases if a]})
    return result
