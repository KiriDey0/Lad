// SPDX-License-Identifier: AGPL-3.0-only
import {test, expect, _electron} from '@playwright/test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';

test('local folder, playback, synced text and playlists work without an account', async()=>{
  test.setTimeout(90000);
  const privateRoot=fs.mkdtempSync(path.join(os.tmpdir(),'lad-local-qa-'));
  const music=path.join(privateRoot,'music');
  fs.mkdirSync(path.join(music,'nested'),{recursive:true});
  const root=path.resolve(__dirname,'..');
  const python=process.env.LAD_PYTHON || path.join(root,'.venv/bin/python');
  execFileSync(python,['-c',`import wave,math,struct,sys\nfrom pathlib import Path\nfrom mutagen.wave import WAVE\nfrom mutagen.id3 import TIT2,TPE1,TALB,USLT\np=Path(sys.argv[1])/'nested'/'qa.wav'\nwith wave.open(str(p),'wb') as w:\n w.setnchannels(1);w.setsampwidth(2);w.setframerate(8000);w.writeframes(b''.join(struct.pack('<h',int(250*math.sin(i*.2))) for i in range(8000*12)))\na=WAVE(p);a.add_tags();a.tags.add(TIT2(encoding=3,text='Local QA'));a.tags.add(TPE1(encoding=3,text='Test Artist'));a.tags.add(TALB(encoding=3,text='Test Album'));a.tags.add(USLT(encoding=3,lang='eng',text='[00:00.00]First line\\n[00:03.00]Second line'));a.save()`,music]);
  const app=await _electron.launch({args:[root],env:{...process.env,LAD_USER_DATA:path.join(privateRoot,'profile'),LAD_PYTHON:python}});
  try {
    const page=await app.firstWindow();
    await expect.poll(()=>page.evaluate(async()=>{try{await window.lad!.rpc('snapshot');return true}catch{return false}})).toBe(true);
    await app.evaluate(({dialog},music)=>{dialog.showOpenDialog=(async()=>({canceled:false,filePaths:[music]})) as any},music);
    await page.getByRole('button',{name:'Музыка на устройстве',exact:true}).click();
    await page.getByRole('button',{name:'Добавить папку',exact:true}).click();
    await expect.poll(()=>page.evaluate(async()=> (await window.lad!.rpc('localSummary') as any).total)).toBe(1);
    const tracks=await page.evaluate(async()=> (await window.lad!.rpc('localBrowse',{kind:'tracks'}) as any).tracks);
    expect(tracks[0]).toMatchObject({title:'Local QA',artist:'Test Artist',source:'local'});
    await expect(page.getByRole('heading',{name:'Любимые исполнители'})).toBeVisible();
    await page.evaluate((t)=>window.lad!.rpc('like',{id:t.id,liked:true}),tracks[0]);
    await page.getByRole('button',{name:'На устройстве',exact:true}).click();
    await expect(page.locator('.liked-preview .track-row')).toHaveCount(1);
    await page.getByRole('button',{name:'Включить Local QA',exact:true}).click();
    await expect.poll(()=>page.evaluate(async()=> (await window.lad!.rpc('snapshot') as any).playback.track?.id)).toBe(tracks[0].id);
    await expect.poll(()=>page.evaluate(async()=> (await window.lad!.rpc('snapshot') as any).playback.position)).toBeGreaterThan(0);
    await page.evaluate(()=>window.lad!.rpc('control',{action:'pause'}));
    const lyrics=await page.evaluate((t)=>window.lad!.rpc('lyrics',{id:t.id}),tracks[0]) as any;
    expect(lyrics.lines).toEqual([{time:0,text:'First line'},{time:3,text:'Second line'}]);
    await page.locator('.liked-preview .track-row').first().click({button:'right'});
    await expect(page.getByRole('menuitem',{name:/Добавить в плейлист на устройстве/})).toBeVisible();
    await page.getByRole('menuitem',{name:/Добавить в плейлист на устройстве/}).click();
    await page.getByRole('textbox',{name:'Название',exact:true}).fill('QA playlist');
    await page.getByRole('button',{name:'Добавить',exact:true}).click();
    const playlists=await page.evaluate(async()=> (await window.lad!.rpc('localBrowse',{kind:'playlists'}) as any).cards);
    expect(playlists).toHaveLength(1);
    const collection=await page.evaluate((id)=>window.lad!.rpc('collection',{type:'playlist',id}),playlists[0].id) as any;
    expect(collection.tracks.map((t:any)=>t.id)).toEqual([tracks[0].id]);
    await expect(page.getByRole('menuitem',{name:'Открыть в Яндекс Музыке'})).toHaveCount(0);
    const denied=await page.evaluate(async()=>{try{await window.lad!.rpc('localAddFolder',{path:'/'});return false}catch{return true}});
    expect(denied).toBe(true);
    await page.screenshot({path:path.join(privateRoot,'collection.png')});
    if(process.env.LAD_QA_DIR){fs.mkdirSync(process.env.LAD_QA_DIR,{recursive:true});fs.copyFileSync(path.join(privateRoot,'collection.png'),path.join(process.env.LAD_QA_DIR,'local-collection.png'))}
    const folders=await page.evaluate(async()=> (await window.lad!.rpc('localSummary') as any).folders);
    await page.evaluate((id)=>window.lad!.rpc('localRemoveFolder',{id}),folders[0].id);
    const unavailable=await page.evaluate((id)=>window.lad!.rpc('collection',{type:'playlist',id}),playlists[0].id) as any;
    expect(unavailable.tracks[0].available).toBe(false);
    expect(fs.existsSync(path.join(music,'nested/qa.wav'))).toBe(true);
  } finally {await app.close();fs.rmSync(privateRoot,{recursive:true,force:true})}
});
