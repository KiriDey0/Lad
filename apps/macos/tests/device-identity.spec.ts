// SPDX-License-Identifier: AGPL-3.0-only
import {test, expect, _electron} from '@playwright/test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {buildSync} from 'esbuild';

test('rooms distinguish identical speakers and sound transfer requires explicit confirmation', async () => {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(),'lad-room-qa-'));
  const root = path.resolve(__dirname,'..');
  const app = await _electron.launch({executablePath:process.env.LAD_TEST_EXE || undefined,
    args:process.env.LAD_TEST_EXE ? [] : [root],env:{...process.env,LAD_USER_DATA:profile}});
  try {
    const page = await app.firstWindow();
    await expect.poll(()=>page.evaluate(async()=>{try{await window.lad!.rpc('snapshot');return true;}catch{return false;}})).toBe(true);
    await page.evaluate(()=>window.lad!.rpc('demo'));
    await page.evaluate(()=>window.lad!.rpc('control',{action:'pause'}));
    const state = await page.evaluate(()=>window.lad!.rpc('snapshot')) as any;
    state.output='local';state.deviceId=null;state.connection='disconnected';
    state.devices=[
      {id:'room-a',name:'Яндекс Станция',model:'Яндекс Станция',room:'Кухня',household:'Дом',metadataStatus:'ready',host:'192.168.1.10'},
      {id:'room-b',name:'Яндекс Станция',model:'Яндекс Станция',room:'Спальня',household:'Дом',metadataStatus:'ready',host:'192.168.1.11'},
    ];
    await app.evaluate(({ipcMain,BrowserWindow},state)=>{
      const g=globalThis as any;g.deviceFixture=state;g.deviceCalls=[];
      ipcMain.removeHandler('orbit:rpc');
      ipcMain.handle('orbit:rpc',(_e,method,params)=>{
        if(method==='devices')return g.deviceFixture.devices;
        if(['connect','output','control','play'].includes(method))g.deviceCalls.push({method,params});
        if(method==='output'){
          g.deviceFixture.output=params.output;
          BrowserWindow.getAllWindows()[0].webContents.send('orbit:event',{event:'state',data:g.deviceFixture});
        }
        return g.deviceFixture;
      });
      BrowserWindow.getAllWindows()[0].webContents.send('orbit:event',{event:'state',data:state});
    },state);
    await page.getByRole('button',{name:'Устройство воспроизведения',exact:true}).click();
    await page.getByRole('button',{name:/Кухня.*Яндекс Станция/}).click();
    expect(await app.evaluate(()=>(globalThis as any).deviceCalls)).toEqual([]);
    const confirm=page.getByRole('button',{name:'Продолжить: Кухня · Дом',exact:true});
    await expect(confirm).toBeEnabled();
    if (process.env.LAD_QA_DIR) {
      fs.mkdirSync(process.env.LAD_QA_DIR,{recursive:true});
      await page.screenshot({path:path.join(process.env.LAD_QA_DIR,'station-rooms-dialog.png')});
    }
    await confirm.click();
    const calls=await app.evaluate(()=>(globalThis as any).deviceCalls);
    expect(calls).toEqual([
      {method:'connect',params:{id:'room-a',identity:{name:'Яндекс Станция',room:'Кухня',household:'Дом'}}},
      {method:'output',params:{output:'station',transfer:true}},
    ]);
    state.devices=state.devices.map((d:any)=>({...d,room:'',household:'',metadataStatus:'unavailable'}));
    await app.evaluate(({BrowserWindow},state)=>{
      (globalThis as any).deviceFixture=state;
      BrowserWindow.getAllWindows()[0].webContents.send('orbit:event',{event:'state',data:state});
    },state);
    await page.getByRole('button',{name:'Устройство воспроизведения',exact:true}).click();
    await page.locator('.device-option').nth(1).click();
    const continueButton=page.getByRole('button',{name:'Продолжить: Яндекс Станция',exact:true});
    await expect(continueButton).toBeEnabled();
    await continueButton.click();
    await expect(page.getByRole('alert')).toContainText('Точно на эту Станцию?');
    await expect(page.getByRole('alert')).toContainText('room-a');
    expect(await app.evaluate(()=>(globalThis as any).deviceCalls.length)).toBe(2);
    await page.getByRole('button',{name:'Назад к выбору',exact:true}).click();
    await expect(page.getByRole('button',{name:'Всё равно включить',exact:true})).toHaveCount(0);
    await continueButton.click();
    // Playback state updates must not erase the pending warning.
    await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].webContents.send('orbit:event',{event:'state',data:{...(globalThis as any).deviceFixture,devices:[...(globalThis as any).deviceFixture.devices]}}));
    await page.getByRole('button',{name:'Всё равно включить',exact:true}).click();
    expect(await app.evaluate(()=>(globalThis as any).deviceCalls.slice(2))).toEqual([
      {method:'connect',params:{id:'room-a',identity:{name:'Яндекс Станция',room:'',household:''},allowAmbiguous:true}},
      {method:'output',params:{output:'station',transfer:true}},
    ]);
  } finally {
    await app.close();
    fs.rmSync(profile,{recursive:true,force:true});
  }
});

test('a late startup snapshot cannot replace a newer account and device list', async () => {
  const profile=fs.mkdtempSync(path.join(os.tmpdir(),'lad-startup-qa-'));
  const root=path.resolve(__dirname,'..');
  const main=path.join(profile,'main.cjs');
  fs.writeFileSync(path.join(profile,'index.html'),'<div id="root"></div>');
  fs.writeFileSync(main,`const {app,BrowserWindow}=require('electron');app.setPath('userData',${JSON.stringify(profile)});app.whenReady().then(()=>new BrowserWindow({show:false}).loadFile(${JSON.stringify(path.join(profile,'index.html'))}));`);
  const bundle=buildSync({stdin:{contents:`import React from 'react';import {createRoot} from 'react-dom/client';import App from ${JSON.stringify(path.join(root,'src/App.tsx'))};import {initialState} from ${JSON.stringify(path.join(root,'src/types.ts'))};window.initialFixture=initialState;createRoot(document.getElementById('root')).render(<App/>);`,resolveDir:root,loader:'tsx'},bundle:true,write:false,jsx:'automatic'});
  const app=await _electron.launch({args:[main]});
  try {
    const page=await app.firstWindow();
    await page.setContent('<div id="root"></div>');
    await page.evaluate(()=>{
      const w=window as any;
      w.lad={platform:'darwin',onEvent:(listener:any)=>{w.stateListener=listener;return()=>{};},rpc:async(method:string)=>{
        if(method==='snapshot')return new Promise(resolve=>{w.resolveSnapshot=resolve;});
        if(method==='library')return {playlists:[],albums:[]};
        return {cards:[],tracks:[]};
      }};
    });
    await page.addScriptTag({content:bundle.outputFiles[0].text});
    await expect.poll(()=>page.evaluate(()=>typeof (window as any).resolveSnapshot)).toBe('function');
    await page.evaluate(()=>{
      const w=window as any;
      w.stateListener({event:'state',data:{...w.initialFixture,profile:{uid:'qa',name:'QA identity',plus:true},demo:true}});
    });
    await expect(page.getByRole('button',{name:/QA identity/})).toBeVisible();
    await page.evaluate(async()=>{
      const w=window as any;w.resolveSnapshot(w.initialFixture);
      await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
    });
    await expect(page.getByRole('button',{name:/QA identity/})).toBeVisible();
  } finally {await app.close();fs.rmSync(profile,{recursive:true,force:true});}
});
