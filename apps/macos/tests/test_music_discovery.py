# SPDX-License-Identifier: AGPL-3.0-only
import sys
from pathlib import Path
from types import SimpleNamespace as NS
from datetime import date, timedelta
from unittest.mock import AsyncMock
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'backend'))
from music import Music

def artist(id_): return NS(id=id_,name=f'Artist {id_}',cover=NS(uri=None))
def album(id_, days=0):
    return NS(id=id_,title=f'Album {id_}',artists=[artist(1)],year=2026,cover_uri=None,type='single',release_date=(date.today()-timedelta(days=days)).isoformat()+'T00:00:00+03:00')
def track(id_):
    return NS(id=id_,title=f'Track {id_}',artists=[artist(1)],albums=[],duration_ms=100000,cover_uri=None,available=True,explicit=False,content_warning=None)

@pytest.mark.asyncio
async def test_personal_releases_use_follows_likes_history_dates_and_deduplicate():
    m=Music(); calls=[]
    async def albums(id_,**kwargs):
        calls.append((id_,kwargs));return NS(albums=[album(8,2),album(9,95),album(10,-1)])
    m.client=NS(users_likes_artists=AsyncMock(return_value=[NS(artist=artist(1))]),
        music_history=AsyncMock(return_value=NS(history_tabs=[NS(items=[NS(tracks=[NS(data=NS(full_model=NS(artists=[artist(2)])))])])])),artists_direct_albums=albums)
    m.liked=AsyncMock(return_value={'tracks':[{'artists':[{'id':'3','name':'Artist 3'}]}]})
    result=await m.releases()
    assert [a['id'] for a in result['releases']]==['8']
    assert {id_ for id_,_ in calls}=={'1','2','3'}
    assert all(kw['sort_by']=='year' for _,kw in calls)
    assert not result['partial']
    await m.releases();assert len(calls)==3

@pytest.mark.asyncio
async def test_partial_release_failure_does_not_erase_success_or_return_fake_chart():
    m=Music()
    async def latest(id_,**kwargs):
        if id_=='2':raise RuntimeError('unavailable')
        return NS(albums=[album(8)])
    m.client=NS(users_likes_artists=AsyncMock(return_value=[artist(1),artist(2)]),music_history=AsyncMock(side_effect=RuntimeError()),artists_direct_albums=latest)
    m.liked=AsyncMock(side_effect=RuntimeError())
    assert (await m.releases())['partial']
    m.cache.clear();m.client.users_likes_artists=AsyncMock(side_effect=RuntimeError())
    with pytest.raises(ValueError):await m.releases()

@pytest.mark.asyncio
async def test_artist_uses_server_popularity_and_paginates_by_year():
    m=Music();ranked=[album(9),album(4)]
    m.client=NS(artists_brief_info=AsyncMock(return_value=NS(artist=artist(1),popular_tracks=[track(5),track(2)],stats=NS(last_month_listeners=123))),
        artists_direct_albums=AsyncMock(return_value=NS(albums=ranked,pager=NS(per_page=24,total=49))),
        users_likes_artists=AsyncMock(return_value=[NS(artist=artist(1))]),users_likes_tracks=AsyncMock(return_value=NS(tracks=[NS(id=5)])),users_dislikes_tracks=AsyncMock(return_value=NS(tracks=[NS(id=2)])))
    result=await m.artist_info('1')
    assert result['followed'] and result['listeners']==123
    assert [t['id'] for t in result['tracks']]==['5','2']
    assert result['tracks'][0]['liked'] and result['tracks'][1]['disliked']
    assert [a['id'] for a in result['albums']]==['9','4']
    assert m.client.artists_direct_albums.call_args.kwargs['sort_by']=='rating'
    result=await m.artist_albums('1',1)
    assert result['hasMore'] and m.client.artists_direct_albums.call_args.kwargs=={'page':1,'page_size':24,'sort_by':'year'}
    assert not (await m.artist_albums('1',2))['hasMore']

@pytest.mark.asyncio
async def test_dislikes_preserve_server_order_and_advance_past_unavailable_models():
    m=Music();m.client=NS(users_dislikes_tracks=AsyncMock(return_value=NS(tracks=[NS(id=x) for x in [1,2,3]])),tracks=AsyncMock(return_value=[track(2)]))
    result=await m.dislikes(0,2)
    assert [t['id'] for t in result['tracks']]==['1','2']
    assert result['tracks'][0]['available'] is False
    assert result['tracks'][0]['title']=='Недоступный трек'
    assert result['tracks'][0]['disliked'] and result['nextOffset']==2 and result['total']==3

@pytest.mark.asyncio
async def test_marks_use_atomic_yandex_endpoints_validate_and_require_confirmation():
    m=Music();m.client=NS(users_likes_tracks_add=AsyncMock(return_value=True),users_dislikes_tracks_add=AsyncMock(return_value=True),users_dislikes_tracks_remove=AsyncMock(return_value=False))
    assert await m.set_rating('5','liked',True)
    assert await m.set_rating('5','disliked',True)
    with pytest.raises(ValueError):await m.set_rating('5','disliked',False)
    with pytest.raises(ValueError):await m.set_rating('local-1','disliked',True)
    with pytest.raises(ValueError):await m.set_rating('5','liked','yes')

@pytest.mark.asyncio
async def test_missing_rating_service_preserves_unknown_not_false():
    m=Music();m.client=NS()
    result=await m.ratings([{'id':'1','liked':True}])
    assert result==[{'id':'1','liked':True}]

@pytest.mark.asyncio
async def test_dislike_ack_does_not_skip_a_new_queue_occurrence(monkeypatch):
    import app as module
    from app import App
    monkeypatch.setattr(module,'emit',lambda *args:None)
    engine=App();await engine.setup()
    try:
        engine.music.client=NS()
        engine.queue.replace([{'id':'5','duration':100},{'id':'6','duration':100}])
        engine.playback['track']=engine.queue.current
        async def save(*args):
            # User chose another occurrence during the network request.
            engine.queue.replace([{'id':'5','duration':100},{'id':'7','duration':100}])
            engine.playback['track']=engine.queue.current
            return True
        engine.music.set_rating=save
        engine.advance=AsyncMock()
        assert await engine.rpc('dislike',{'id':'5','disliked':True})
        engine.advance.assert_not_awaited()
        assert engine.queue.current['disliked'] and not engine.queue.current['liked']
    finally:await engine.close()

@pytest.mark.asyncio
async def test_empty_discography_is_successful_empty_page():
    from yandex_music import ArtistAlbums
    m=Music();m.client=NS(artists_direct_albums=AsyncMock(return_value=ArtistAlbums(albums=[],pager=None)))
    assert await m.artist_albums('1')=={'albums':[],'page':0,'hasMore':False}
