# SPDX-License-Identifier: AGPL-3.0-only
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
import pytest
from core import Queue, parse_lrc, track_key


def tr(id):
    return {'id': str(id), 'title': str(id), 'artist': 'Test', 'duration': 10, 'available': True}


def test_lrc_offsets_multiple_stamps_and_fraction_lengths():
    lines = parse_lrc('[offset:-500]\n[00:01.5][01:02.125]first\n[00:04.25]second\n[ar:artist]\n[00:00.1]start')
    assert lines == [{'time': 0, 'text': 'start'}, {'time': 1., 'text': 'first'}, {'time': 3.75, 'text': 'second'}, {'time': 61.625, 'text': 'first'}]


def test_plain_text_has_no_invented_timestamps():
    assert parse_lrc('Line one\nLine two') == []


def test_move_duplicate_tracks_keeps_exact_current_occurrence():
    q = Queue(); q.replace([tr(1), tr(2), tr(1), tr(3)], 2)
    q.move(0, 3)
    assert q.index == 1
    assert [x['id'] for x in q.tracks] == ['2', '1', '3', '1']
    q.remove(0)
    assert q.index == 0
    with pytest.raises(ValueError): q.remove(0)


def test_shuffle_restores_duplicates_without_replaying_prefix():
    q = Queue(); q.replace([tr(1), tr(2), tr(1), tr(3)], 0)
    q.shuffle(True)
    assert q.tracks[0] == tr(1)
    assert sorted(t['id'] for t in q.tracks) == ['1', '1', '2', '3']
    q.shuffle(False)
    assert q.tracks == [tr(1), tr(2), tr(1), tr(3)]


def test_repeat_one_only_applies_to_automatic_next():
    q = Queue(); q.replace([tr(1),tr(2)], 0); q.repeat = 'one'
    assert q.next_index(automatic=True) == 0
    assert q.next_index() == 1
    q.index = 1
    assert q.next_index() is None
    q.repeat = 'all'
    assert q.next_index() == 0


def test_added_tracks_are_copied_not_shared_with_catalog():
    tracks = [tr(1), tr(2)]
    q = Queue(); q.replace(tracks)
    q.tracks[0]['title'] = 'changed'
    assert tracks[0]['title'] == '1'
    q.add([tr(3)], next_=True)
    assert [t['id'] for t in q.tracks] == ['1','3','2']


def test_track_key_ignores_album_context():
    assert track_key('123:456') == '123'
