# SPDX-License-Identifier: AGPL-3.0-only
"""Read-only display metadata. Never match physical speakers by name or list order."""
import unicodedata


def text(value):
    return ' '.join(value.split())[:160] if isinstance(value, str) else ''


def key(value):
    return unicodedata.normalize('NFKC', text(value)).casefold()


def enrich_devices(devices, info=None):
    valid = isinstance(info, dict) and info.get('status') == 'ok' and isinstance(info.get('devices'), list)
    rooms = info.get('rooms', []) if valid else []
    houses = info.get('households', []) if valid else []
    index = {}
    for d in info['devices'] if valid else []:
        q = d.get('quasar_info')
        id_ = text(q.get('device_id')) if isinstance(q, dict) else ''
        if id_:
            index.setdefault(id_, []).append(d)
    result = []
    for original in devices:
        d = {**original, 'model': text(original.get('name')) or 'Яндекс Станция',
             'room': '', 'household': '', 'metadataStatus': 'unmatched' if valid else 'unavailable'}
        matches = index.get(text(d.get('id')), [])
        if len(matches) == 1:
            m = matches[0]
            house_id = text(m.get('household_id'))
            room_id = text(m.get('room'))
            room_matches = [r for r in rooms if text(r.get('id')) == room_id and room_id
                            and text(r.get('household_id')) == house_id]
            house_matches = [h for h in houses if text(h.get('id')) == house_id and house_id]
            d.update(name=text(m.get('name')) or d['model'], metadataStatus='ready',
                     room=text(room_matches[0].get('name')) if len(room_matches) == 1 else '',
                     household=text(house_matches[0].get('name')) if len(house_matches) == 1 else '')
        elif len(matches) > 1:
            d['metadataStatus'] = 'conflict'
        d['name'] = text(d.get('name')) or d['model']
        result.append(d)
    return result


def device_title(d):
    name = text(d.get('name')) or 'Яндекс Станция'
    return text(d.get('room')) if d.get('room') and key(name) == key(d.get('model')) else name


def device_ambiguous(d, devices):
    identity = tuple(key(d.get(k)) for k in ('name', 'room', 'household'))
    return any(other.get('id') != d.get('id') and
               tuple(key(other.get(k)) for k in ('name', 'room', 'household')) == identity
               for other in devices)


AMBIGUOUS_DEVICE = 'Не удалось различить Станции. Укажи разные имена или комнаты в «Доме с Алисой» и обнови список.'
