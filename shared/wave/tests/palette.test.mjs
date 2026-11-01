// SPDX-License-Identifier: AGPL-3.0-only
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {coverWavePalette,luminance} from '../palette.mjs';
const image=(color)=>new Uint8ClampedArray(Array.from({length:96*96},()=>[...color,255]).flat());

for(const gray of [0,40,100,170,235,255])test(`gray ${gray} gets a visible bright fallback`,()=>{
  const p=coverWavePalette(image([gray,gray,gray]),96,96);
  assert.equal(p.achromatic,true);
  assert.equal(p.lineGain,1.25);
  assert.ok(p.colors.every(c=>luminance(c)>.65));
});
test('colored centre survives a gray border and retains its hue',()=>{
  const pixels=image([90,90,90]);
  for(let y=32;y<64;y++)for(let x=32;x<64;x++)pixels.set([190,35,30,255],(y*96+x)*4);
  const p=coverWavePalette(pixels,96,96);
  assert.equal(p.achromatic,false);
  assert.ok(p.colors[0][0]>p.colors[0][1]+35);
  assert.ok(p.colors.every(c=>luminance(c)>=.475));
});
test('a single colorful noise pixel cannot pick the palette',()=>{
  const pixels=image([80,80,80]);pixels.set([255,0,0,255],0);
  assert.equal(coverWavePalette(pixels,96,96).achromatic,true);
});
test('transparent color is ignored',()=>{
  const pixels=image([255,0,0]);for(let i=3;i<pixels.length;i+=4)pixels[i]=0;
  assert.equal(coverWavePalette(pixels,96,96).achromatic,true);
});
test('gray fallback increases actual line contrast at the same intensity',()=>{
  const background=[23,25,29],old=[108,108,108],p=coverWavePalette(image([90,90,90]),96,96);
  const composite=(fg,a)=>fg.map((v,i)=>v*a+background[i]*(1-a));
  const contrast=c=>(luminance(c)+.05)/(luminance(background)+.05);
  assert.ok(contrast(composite(p.colors[0],.2*p.lineGain))>contrast(composite(old,.2))*1.3);
});
