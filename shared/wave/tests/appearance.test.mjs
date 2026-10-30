// SPDX-License-Identifier: AGPL-3.0-only
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {waveBrightness} from '../appearance.mjs';
import {WaveFeatures, WaveMotion} from '../audio-features.mjs';
import {WebSimulation} from '../web-simulation.mjs';

test('new 50% retains the original maximum and 100% visibly brightens links', () => {
  const oldLine = .19 * 1.65;
  assert.equal(oldLine * waveBrightness(50), oldLine);
  assert.ok(Math.min(1, oldLine * waveBrightness(100)) > oldLine * 1.9);
  assert.equal(waveBrightness(0), 0);
});

function trace(smoothing, fps = 60) {
  const sim = new WebSimulation(90), quiet = new WebSimulation(90);
  const features = new WaveFeatures(), motion = new WaveMotion();
  let travel = 0, prior, firstVisible = null;
  for (let i = 0; i < fps * 6; i++) {
    const t = i / fps;
    const pulse = t >= 2 && t < 5 ? .14 * Math.exp(-(t % .5) / .055) : 0;
    const spectrum = [pulse, pulse * .8, 0, 0, pulse * .3, 0, 0, 0];
    const f = motion.update(features.process({spectrum}, 1 / fps), 1 / fps, .75, smoothing);
    sim.step(1 / fps, f, smoothing);
    quiet.step(1 / fps, {}, smoothing);
    const difference = [];
    let visible = 0;
    for (let j = 0; j < sim.count; j++) {
      const x = sim.points[j * 4] - quiet.points[j * 4], y = sim.points[j * 4 + 1] - quiet.points[j * 4 + 1];
      difference.push(x, y);
      if (Math.hypot(x, y) > .006) visible++;
      if (prior) travel += Math.hypot(x - prior[j * 2], y - prior[j * 2 + 1]);
      assert.ok(Number.isFinite(x) && Number.isFinite(y));
    }
    if (firstVisible === null && visible >= sim.count * .2) firstVisible = t - 2;
    prior = difference;
  }
  return {travel, firstVisible, points: sim.points};
}

test('0% responds earlier while 100% keeps quiet, finite movement', () => {
  const sharp = trace(0), usual = trace(50), calm = trace(100);
  assert.ok(sharp.firstVisible < usual.firstVisible);
  assert.ok(sharp.firstVisible <= .05, 'sharp mode follows a short beat within 50ms of decoded input');
  assert.ok(calm.travel > .005, 'maximum smoothing still moves');
  assert.ok(calm.travel < usual.travel * .4, 'maximum smoothing is substantially calmer');
  for (const fps of [30, 120]) assert.ok(trace(0, fps).travel > 0);
});

test('50% preserves the existing default model', () => {
  const a = new WebSimulation(60), b = new WebSimulation(60);
  for (let i = 0; i < 180; i++) {
    const audio = {spectrum:[Math.sin(i / 7) ** 2, .2, 0, .3, 0, 0, .1, 0]};
    a.step(1 / 60, audio);
    b.step(1 / 60, audio, 50);
  }
  assert.deepEqual(a.points, b.points);
});
