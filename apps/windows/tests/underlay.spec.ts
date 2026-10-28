// SPDX-License-Identifier: AGPL-3.0-only
import {test,expect,_electron as electron} from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs';
test('content scrolls behind glass while the final card stays reachable',async()=>{
 const root=path.resolve(__dirname,'..');
 const app=await electron.launch({executablePath:process.env.LAD_TEST_EXE||undefined,args:process.env.LAD_TEST_EXE?[]:[root],env:{...process.env,LAD_USER_DATA:process.env.LAD_UNDERLAY_PROFILE||path.resolve(root,'../../work/underlay-'+Date.now())},timeout:60000});
 try {
  const page=await app.firstWindow();
  await page.getByRole('button',{name:'Посмотреть демо'}).click();
  await page.evaluate(()=>window.lad!.rpc('control',{action:'pause'}));
  await page.getByRole('button',{name:'Коллекция',exact:true}).click();
  await expect(page.locator('.music-card').first()).toBeVisible();
  await page.locator('.card-grid').first().evaluate(el=>{const seed=el.firstElementChild!;for(let i=0;i<28;i++)el.append(seed.cloneNode(true));(el.lastElementChild as HTMLElement).dataset.lastFixture='true'});
  const geometry=await page.evaluate(()=>{
   const r=(s:string)=>document.querySelector(s)!.getBoundingClientRect();
   return{scrollBottom:r('.page-content').bottom,shellBottom:r('.app-shell').bottom,playerTop:r('.player').top,sidebarBottom:r('.sidebar').bottom,queueBottom:r('.queue-panel').bottom};
  });
  expect(Math.abs(geometry.scrollBottom-geometry.shellBottom)).toBeLessThan(2);
  expect(geometry.sidebarBottom).toBeLessThan(geometry.playerTop);
  expect(geometry.queueBottom).toBeLessThan(geometry.playerTop);
  await page.locator('.page-content').evaluate(el=>el.scrollTop=el.scrollHeight);
  const last=await page.locator('[data-last-fixture]').boundingBox();const player=await page.locator('.player').boundingBox();
  expect(last!.y+last!.height).toBeLessThan(player!.y);
  await page.locator('.page-content').evaluate(el=>el.scrollTop=el.scrollHeight/2);
  expect(await page.evaluate(()=>{const p=document.querySelector('.player')!.getBoundingClientRect();return [...document.querySelectorAll('.page-content .music-card')].some(el=>{const r=el.getBoundingClientRect();return r.top<p.bottom&&r.bottom>p.top})})).toBe(true);
  const out=process.env.LAD_QA_DIR||path.resolve(root,'../../work');fs.mkdirSync(out,{recursive:true});
  fs.writeFileSync(path.join(out,'desktop-content-under-glass.png'),Buffer.from(await app.evaluate(async({BrowserWindow})=>(await BrowserWindow.getAllWindows()[0].webContents.capturePage()).toPNG().toString('base64')),'base64'));
  const captureGlass=async()=>{
   const rect=await page.evaluate(()=>{const p=document.querySelector('.player')!.getBoundingClientRect();return{x:p.x,y:p.y,width:p.width,height:p.height,shellWidth:document.querySelector('.app-shell')!.getBoundingClientRect().width}});
   return Buffer.from(await app.evaluate(async({BrowserWindow},r)=>{const full=await BrowserWindow.getAllWindows()[0].webContents.capturePage();const scale=full.getSize().width/r.shellWidth;return full.crop({x:Math.round(r.x*scale),y:Math.round(r.y*scale),width:Math.floor(r.width*scale),height:Math.floor(r.height*scale)}).toPNG().toString('base64')},rect),'base64');
  };
  await page.locator('.page-content').evaluate(el=>{el.innerHTML='<div style="height:4000px;background:rgb(160,30,50)"></div>';el.scrollTop=500});
  await page.waitForTimeout(200);const red=await captureGlass();
  await page.locator('.page-content > div').evaluate(el=>(el as HTMLElement).style.background='rgb(20,80,180)');
  await page.waitForTimeout(200);const blue=await captureGlass();
  fs.writeFileSync(path.join(out,'glass-red-backdrop.png'),red);fs.writeFileSync(path.join(out,'glass-blue-backdrop.png'),blue);
  expect(red.equals(blue),'The actual scrolling backdrop must change glass pixels').toBe(false);
 }finally{await app.close()}
});
