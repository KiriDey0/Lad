import {test} from "node:test";import assert from "node:assert/strict";import {WaveFeatures} from "../audio-features.mjs";
test('silence has no energy and no beat',()=>{const f=new WaveFeatures();for(let i=0;i<240;i++){const x=f.process({spectrum:Array(8).fill(0)},1/60);assert.equal(x.level,0);assert.equal(x.impact,0);}});
for(const scale of [1,.1])test(`tracks 5 bass attacks at gain ${scale}`,()=>{const f=new WaveFeatures();let hits=0,peak=0;for(let i=0;i<480;i++){const n=i%60,v=i>120&&i<420?.12*scale*Math.exp(-n/5):0;const x=f.process({bass:v,mid:0,high:0},1/60);hits+=x.impact>.3;peak=Math.max(peak,x.bass);}assert.equal(hits,5);assert.ok(peak>.35);});
test('sustained tone does not repeatedly tear the ring',()=>{const f=new WaveFeatures();let n=0;for(let i=0;i<480;i++)n+=f.process({bass:.05,mid:0,high:0},1/60).impact>.3;assert.ok(n<=1);});
test('high frequencies move their own sectors',()=>{const f=new WaveFeatures();let x;for(let i=0;i<120;i++)x=f.process({bass:0,mid:0,high:.05},1/60);assert.equal(x.bass,0);assert.equal(x.mid,0);assert.ok(x.high>.1);});
test('response peak arrives within 50 ms and decays smoothly',()=>{const f=new WaveFeatures();for(let i=0;i<90;i++)f.process({bass:0,mid:0,high:0},1/60);let p=0;for(let i=0;i<3;i++)p=Math.max(p,f.process({bass:.1,mid:0,high:0},1/60).bass);assert.ok(p>.5);const first=f.process({bass:0,mid:0,high:0},1/60).bass;assert.ok(first>p*.8);let last=first;for(let i=0;i<120;i++)last=f.process({bass:0,mid:0,high:0},1/60).bass;assert.ok(last<.001);});
import {WaveMotion} from "../audio-features.mjs";
test('short attacks reach the motion model without a long visual ramp', () => {
  const m = new WaveMotion(), loud = {spectrum:Array(8).fill(1),bass:1,mid:1,high:1,level:1};
  let previous = 0;
  for (let i = 0; i < 6; i++) {
    const value = m.update(loud, 1/60).bass;
    assert.ok(value > previous && value - previous < .18, 'smooth multi-frame attack');
    previous = value;
  }
  assert.ok(previous > .75, 'attack is already visible within 100 ms');
  assert.ok(m.update({spectrum:Array(8).fill(0)},1/60).bass > previous*.85, 'no hard cut on release');
});
test('isolated bands keep their own identity with a small neighbouring blend', () => {
  const m = new WaveMotion();
  for(let i=0;i<120;i++)m.update({spectrum:[0,0,0,1,0,0,0,0]},1/60);
  assert.ok(m.spectrum[3] > .8);
  assert.ok(m.spectrum[2] > 0 && m.spectrum[4] > 0);
  assert.ok(m.spectrum[3] > m.spectrum[2]*8);
  assert.equal(m.spectrum[0], 0);
});
