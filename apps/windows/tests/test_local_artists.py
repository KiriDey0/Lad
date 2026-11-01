# SPDX-License-Identifier: AGPL-3.0-only
import sys
from pathlib import Path
import hashlib
import pytest
from mutagen.wave import WAVE
from mutagen.id3 import TPE1
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
from local_artists import normalize_catalog
from local_library import LocalLibrary
from test_local_library import audio, scanned


def t(name, id_='track', values=None):
    return {'id': id_, 'artist': name, 'artistId': 'legacy-'+id_, **({'artistValues': values} if values else {})}


def names(track):
    return [a['name'] for a in track['artists']]


def test_cached_collaborations_join_every_independent_artist_and_keep_track_ids():
    before = [t('DEEP-EX-SENSE','a'),t('Лжедмитрий IV','b'),
        t('DEEP-EX-SENSE, Лжедмитрий IV, Asper X','c')]
    after = normalize_catalog(before)
    assert names(after[2]) == ['DEEP-EX-SENSE','Лжедмитрий IV','Asper X']
    assert after[2]['id']=='c' and after[2]['artist']==before[2]['artist']
    assert 'legacy-c' in after[2]['artistAliases']
    assert after[0]['artists'][0]['id']==after[2]['artists'][0]['id']
    assert after[1]['artists'][0]['id']==after[2]['artists'][1]['id']


@pytest.mark.parametrize('credit,expected',[
    ('A feat. B',['A','B']),('A (ft. B)',['A','B']),('A; B; A',['A','B']),
    ('AC/DC',['AC/DC']),('Earth, Wind & Fire',['Earth, Wind & Fire']),
    ('Король и Шут',['Король и Шут']),('A (band)',['A (band)']),('FT Island',['FT Island']),
])
def test_credit_markers_and_band_names(credit,expected):
    assert names(normalize_catalog([t(credit)])[0])==expected


def test_structured_multiple_values_and_case_variants_do_not_drop_guests():
    track=normalize_catalog([t('A, B',values=['A','B','a'])])[0]
    assert names(track)==['A','B']


@pytest.mark.asyncio
async def test_real_tag_values_cards_membership_likes_and_originals(tmp_path):
    lib=LocalLibrary(tmp_path/'private')
    try:
        root=tmp_path/'music'
        one=audio(root/'one.wav');two=audio(root/'two.wav')
        solo=WAVE(one);solo.tags.add(TPE1(encoding=3,text=['A']));solo.save()
        duet=WAVE(two);duet.tags.add(TPE1(encoding=3,text=['A','B']));duet.save()
        original=hashlib.sha256(two.read_bytes()).digest()
        folder=lib.add_folder(root)
        tracks=await scanned(lib,folder)
        duet=next(x for x in tracks if x['fileName']=='two.wav')
        lib.like(duet['id'],True)
        legacy_id=lib.db.execute('SELECT data FROM tracks WHERE id=?',(duet['id'],)).fetchone()[0]
        import json
        legacy_id=json.loads(legacy_id)['artistId']
        assert lib.browse(id_=legacy_id)['total']>=1
        cards=lib.browse(kind='artists')['cards']
        assert {c['title']:c['count'] for c in cards}=={'A':2,'B':1}
        guest=next(c for c in cards if c['title']=='B')
        assert [x['id'] for x in lib.browse(id_=guest['id'])['tracks']]==[duet['id']]
        assert lib.track(duet['id'])['liked']
        assert hashlib.sha256(two.read_bytes()).digest()==original
    finally:
        await lib.close()
