// SPDX-License-Identifier: AGPL-3.0-only
import {test, expect, _electron} from '@playwright/test';
import {buildSync} from 'esbuild';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

test('rendered wave gains brightness without changing the frozen particle geometry', async () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(),'lad-appearance-'));
  const root = path.resolve(__dirname,'..');
  const bundle = buildSync({stdin:{contents:`import React from 'react';import {createRoot} from 'react-dom/client';import {WaveScene} from ${JSON.stringify(path.join(root,'src/wave/WaveScene.tsx'))};const root=createRoot(document.getElementById('root'));window.brightness=(intensity)=>root.render(<WaveScene playing={false} local={true} intensity={intensity}/>);window.brightness(50);`,loader:'tsx',resolveDir:root},bundle:true,write:false,jsx:'automatic'});
  const main = path.join(temp,'main.cjs');
  fs.writeFileSync(main, `const {app,BrowserWindow}=require('electron');app.setPath('userData',${JSON.stringify(temp)});app.whenReady().then(()=>new BrowserWindow({show:false}).loadURL('about:blank'));`);
  const app = await _electron.launch({args:[main]});
  try {
    const page = await app.firstWindow();
    await page.setContent('<style>body{margin:0;background:#000}canvas{width:600px;height:600px}</style><div id="root"></div>');
    await page.addScriptTag({content:bundle.outputFiles[0].text});
    const light = () => page.evaluate(() => {
      const canvas = document.querySelector('canvas')!;
      const data = canvas.getContext('2d')!.getImageData(0,0,canvas.width,canvas.height).data;
      let sum=0;
      for(let i=0;i<data.length;i+=4) sum+=(data[i]+data[i+1]+data[i+2])*data[i+3]/255;
      return sum;
    });
    await expect.poll(light).toBeGreaterThan(1000);
    const half = await light();
    await page.evaluate(() => (window as any).brightness(100));
    await expect.poll(light).toBeGreaterThan(half*1.7);
    await page.evaluate(() => (window as any).brightness(0));
    await expect.poll(light).toBe(0);
  } finally {
    await app.close();
    fs.rmSync(temp,{recursive:true,force:true});
  }
});
