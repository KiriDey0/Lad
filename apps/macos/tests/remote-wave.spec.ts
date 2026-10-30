// SPDX-License-Identifier: AGPL-3.0-only
import {test,expect,_electron} from '@playwright/test';
import fs from 'node:fs';import os from 'node:os';import path from 'node:path';
import {buildSync} from 'esbuild';

test('remote mirror decodes silently, follows seeks and stops on pause and hiding',async()=>{
 const temp=fs.mkdtempSync(path.join(os.tmpdir(),'lad-remote-'));
 const entry=path.join(temp,'entry.tsx'), bundle=path.join(temp,'bundle.js'), main=path.join(temp,'main.cjs');
 const root=path.resolve(__dirname,'..');
 fs.writeFileSync(entry,`import React from ${JSON.stringify(require.resolve('react'))};import {createRoot} from ${JSON.stringify(require.resolve('react-dom/client'))};import {WaveScene} from ${JSON.stringify(path.join(root,'src/wave/WaveScene.tsx'))};import {sampleWaveAudio} from ${JSON.stringify(path.join(root,'src/wave/audio.ts'))};const root=createRoot(document.getElementById('root'));let p={track:{id:'42'},position:3,positionAt:Date.now(),duration:25,playing:true,transitioning:false};window.setRemote=(change)=>{p={...p,...change,positionAt:Date.now()};root.render(<WaveScene playing={p.playing} local={false} remote={p}/>);};window.features=()=>sampleWaveAudio(.016);window.setRemote({});`);
 buildSync({entryPoints:[entry],bundle:true,outfile:bundle,jsx:'automatic',nodePaths:[path.join(root,'node_modules')],define:{'process.env.NODE_ENV':'"production"'}});
 fs.writeFileSync(main,`const {app,BrowserWindow}=require('electron');app.setPath('userData',${JSON.stringify(temp)});app.commandLine.appendSwitch('autoplay-policy','no-user-gesture-required');app.whenReady().then(()=>new BrowserWindow({show:false}).loadURL('about:blank'));`);
 const app=await _electron.launch({args:[main]});
 try{
  const page=await app.firstWindow();
  await page.setContent('<style>body{margin:0}canvas{width:700px;height:600px}</style><div id="root"></div>');
  await page.evaluate(()=>{
   const w=window as any;w.requests=[];w.audios=[];w.contexts=[];
   const NativeAudio=window.Audio,NativeContext=window.AudioContext;
   w.Audio=function(...args:any[]){const a=new NativeAudio(...args);w.audios.push(a);return a;};
   w.AudioContext=class extends NativeContext{
    constructor(){super();w.contexts.push(this);}
    get destination(){if(!w.monitor){w.monitor=this.createAnalyser();const mute=this.createGain();mute.gain.value=0;w.monitor.connect(mute).connect(super.destination);}return w.monitor;}
   };
   const rate=8000,samples=rate*25,data=new ArrayBuffer(44+samples*2),v=new DataView(data);
   const str=(o:number,s:string)=>[...s].forEach((c,i)=>v.setUint8(o+i,c.charCodeAt(0)));
   str(0,'RIFF');v.setUint32(4,36+samples*2,true);str(8,'WAVE');str(12,'fmt ');v.setUint32(16,16,true);v.setUint16(20,1,true);v.setUint16(22,1,true);v.setUint32(24,rate,true);v.setUint32(28,rate*2,true);v.setUint16(32,2,true);v.setUint16(34,16,true);str(36,'data');v.setUint32(40,samples*2,true);
   for(let i=0;i<samples;i++)v.setInt16(44+i*2,Math.sin(i*2*Math.PI*125/rate)*6000,true);
   w.url=URL.createObjectURL(new Blob([data],{type:'audio/wav'}));
   w.lad={rpc:async(method:string,params:any)=>{w.requests.push({method,params});return {url:w.url};}};
  });
  await page.addScriptTag({path:bundle});
  await expect.poll(()=>page.evaluate(()=>(window as any).audios.some((a:HTMLAudioElement)=>!a.paused&&a.currentTime>3))).toBe(true);
  await expect.poll(()=>page.evaluate(()=>(window as any).features().bass)).toBeGreaterThan(.1);
  const output=await page.evaluate(()=>{const a=new Float32Array(2048);(window as any).monitor.getFloatTimeDomainData(a);return Math.max(...a.map(Math.abs));});
  expect(output).toBeLessThan(.00001);
  await page.evaluate(()=>(window as any).setRemote({position:15}));
  await expect.poll(()=>page.evaluate(()=>(window as any).audios[0].currentTime)).toBeGreaterThan(14.5);
  await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'));});
  await expect.poll(()=>page.evaluate(()=>(window as any).audios[0].paused&&!((window as any).audios[0].getAttribute('src')))).toBe(true);
  await expect.poll(()=>page.evaluate(()=>(window as any).contexts[0].state)).toBe('suspended');
  await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:false});(window as any).setRemote({position:15});document.dispatchEvent(new Event('visibilitychange'));});
  await expect.poll(()=>page.evaluate(()=>(window as any).audios[0].paused)).toBe(false);
  await page.evaluate(()=>(window as any).setRemote({playing:false}));
  await expect.poll(()=>page.evaluate(()=>(window as any).audios.every((a:HTMLAudioElement)=>a.paused&&!a.getAttribute('src')))).toBe(true);
  expect(await page.evaluate(()=>(window as any).contexts.every((c:AudioContext)=>c.state==='closed'))).toBe(true);
  expect(await page.evaluate(()=>(window as any).requests.map((r:any)=>r.method))).toEqual(['waveStream','waveStream']);
 }finally{await app.close();fs.rmSync(temp,{recursive:true,force:true});}
});
