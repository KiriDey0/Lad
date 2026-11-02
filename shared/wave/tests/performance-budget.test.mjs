// SPDX-License-Identifier: AGPL-3.0-only
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {WebSimulation} from '../web-simulation.mjs';

test('neighbour selection is limited to 15 Hz independently of presentation rate', () => {
  for (const fps of [20,30,60,120,144]) {
    const sim = new WebSimulation(90);
    for(let n=0;n<fps*3;n++) sim.step(1/fps,{bass:.5,mid:.3});
    assert.ok(sim.topologyBuilds <= 46, String(fps));
    assert.ok(sim.topologyBuilds >= 29, String(fps));
    assert.ok(sim.lineCount>0);
    assert.ok(Array.from(sim.points).every(Number.isFinite));
  }
});

test('candidate workspaces are reused and selected degrees stay bounded', () => {
  const sim=new WebSimulation(100);
  for(let n=0;n<300;n++)sim.step(1/60,{bass:.5});
  const grid=sim.grid, degree=sim.degree, pool=sim.candidatePool, first=pool[0];
  for(let n=0;n<120;n++)sim.step(1/60,{bass:.7,high:.4});
  assert.equal(sim.grid,grid);assert.equal(sim.degree,degree);
  assert.equal(sim.candidatePool,pool);assert.equal(pool[0],first);
  const selected=new Uint8Array(sim.count);
  for(const e of sim.edges.values()) if(e.selected){selected[e.i]++;selected[e.j]++;}
  assert.ok(selected.every(n=>n<=4));
});

test('motion still reacts between neighbour selections', () => {
  const a=new WebSimulation(90),b=new WebSimulation(90);
  for(let n=0;n<180;n++){a.step(1/60);b.step(1/60);}
  a.step(1/60,{spectrum:new Float32Array(8).fill(1)});
  b.step(1/60);
  assert.ok(a.nodes.some((node,i)=>node.energy>b.nodes[i].energy));
  assert.equal(a.topologyBuilds,b.topologyBuilds);
});
