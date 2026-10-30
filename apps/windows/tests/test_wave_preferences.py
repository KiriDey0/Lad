# SPDX-License-Identifier: AGPL-3.0-only
import sys
from pathlib import Path
from types import SimpleNamespace as NS
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'backend'))
from music import Music

@pytest.mark.asyncio
async def test_wave_preferences_use_server_values_and_confirm_saved_result():
    music=Music();calls=[]
    values=lambda items:NS(possible_values=[NS(value=k,name=v)for k,v in items])
    info=NS(station=NS(restrictions2=NS(diversity=values([('favorite','Любимая'),('discover','Незнакомая')]),mood_energy=values([('all','Любое')]),language=values([('any','Любой')]))),settings2=NS(diversity='favorite',mood_energy='all',language='any'))
    async def get(station):assert station=='user:onyourwave';return [info]
    async def save(station,**kwargs):calls.append((station,kwargs));return True
    music.client=NS(rotor_station_info=get,rotor_station_settings2=save)
    settings=await music.wave_settings();assert [g['key']for g in settings['groups']]==['diversity','mood_energy','language']
    assert settings['groups'][0]['values'][1]['name']=='Незнакомая'
    chosen={'diversity':'discover','mood_energy':'all','language':'any'}
    result=await music.wave_settings(chosen);assert calls==[('user:onyourwave',chosen)];assert result['groups'][0]['selected']=='discover'
    with pytest.raises(ValueError):await music.wave_settings({**chosen,'diversity':'made-up'})
    with pytest.raises(ValueError):await music.wave_settings({'diversity':'discover'})
    assert len(calls)==1
