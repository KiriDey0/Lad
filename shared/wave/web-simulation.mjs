// SPDX-License-Identifier: AGPL-3.0-only
import {waveSmoothing} from "./appearance.mjs";
const smooth = (a, b, t) => a + (b - a) * (1 - Math.exp(-t));
export class WebSimulation {
  constructor(count = 300, seed = 7193) {
    this.count = count;
    this.time = 0;
    this.edges = new Map();
    this.grid = new Map();
    this.candidatePool = [];
    this.candidates = [];
    this.degree = new Uint8Array(count);
    this.topologyAge = Infinity;
    this.topologyBuilds = 0;
    this.points = new Float32Array(count * 4);
    const random = () => {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      return seed / 4294967296;
    };
    this.nodes = Array.from({ length: count }, () => {
      const rest = 0.355 + ((random() + random()) / 2 - 0.5) * 0.38;
      return {
        angle: random() * Math.PI * 2,
        radius: rest,
        rest,
        radialVelocity: 0,
        shownRadius: rest,
        angularVelocity: 0,
        phase: random() * Math.PI * 2,
        shine: random(),
        band: Math.floor(random() * 8),
        blend: random(),
        polarity: random() < 0.5 ? -1 : 1,
        frequency: 0.55 + random() * 0.8,
        follow: 9 + random() * 8,
        energy: 0,
        previousEnergy: 0,
        wanderX: 0,
        wanderY: 0,
      };
    });
    this.lines = new Float32Array(count * 30 * 6);
    this.lineCount = 0;
    this.updatePoints();
  }
  updatePoints() {
    for (let i = 0; i < this.count; i++) {
      const n = this.nodes[i];
      this.points[i * 4] = Math.cos(n.angle) * n.shownRadius + n.wanderX;
      this.points[i * 4 + 1] =
        (Math.sin(n.angle) * n.shownRadius) / 1.06 + n.wanderY;
      this.points[i * 4 + 2] = 0.0035 + n.shine * 0.0025 + n.energy * 0.0007;
      this.points[i * 4 + 3] =
        (0.35 + n.shine * 0.45) *
        (0.84 + n.energy * 0.32) *
        (0.92 + 0.12 * Math.sin(this.time * 0.42 + n.phase * 3));
    }
  }
  step(dt, audio = {}, smoothing = 50) {
    if (dt <= 0) return this;
    dt = Math.min(0.05, dt);
    const tune = waveSmoothing(smoothing);
    this.time += dt * tune.flow;
    const bass = audio.bass || 0,
      mid = audio.mid || 0,
      high = audio.high || 0;
    const bands = audio.spectrum || [
      bass,
      bass,
      mid,
      mid,
      mid,
      high,
      high,
      high,
    ];
    for (const n of this.nodes) {
      const local =
        (bands[n.band] || 0) * (0.8 + n.blend * 0.15) +
        (bands[(n.band + 3) % 8] || 0) * (0.2 - n.blend * 0.15);
      n.energy = smooth(n.energy, local, dt * n.follow * (local > n.energy ? 2 : 1) / tune.response);
      const localAttack = Math.max(0, n.energy - n.previousEnergy);
      n.previousEnergy = n.energy;
      const flow =
        0.045 * Math.sin(n.angle * 2 + this.time * 0.21 + n.phase * 0.35) +
        0.025 * Math.cos(n.angle * 4 - this.time * 0.17 + n.phase);
      const individual =
        0.028 * Math.sin(this.time * n.frequency * 0.43 + n.phase * 2);
      n.angularVelocity = smooth(
        n.angularVelocity,
        flow * (1 + mid * 0.25) + individual * (0.6 + n.energy * 0.7),
        dt * 2.4 / tune.response,
      );
      n.angle += n.angularVelocity * dt * tune.flow;
      n.radialVelocity += localAttack * 0.18 * n.polarity * tune.travel;
      const target =
        n.rest +
        n.polarity * n.energy * 0.075 * tune.travel +
        0.011 * Math.sin(this.time * n.frequency + n.phase) * tune.travel;
      const physicsDt = dt / Math.max(1, tune.response);
      n.radialVelocity +=
        ((target - n.radius) * 42 - n.radialVelocity * 11) * physicsDt;
      n.radius += n.radialVelocity * physicsDt;
      n.shownRadius = n.radius + (target - n.radius) * tune.direct;
      const excursion = (0.01 + n.energy * 0.055) * tune.travel;
      const x =
        Math.sin(this.time * n.frequency + n.phase * 2) +
        0.32 * Math.cos(this.time * n.frequency * 0.71 + n.phase * 5);
      const y =
        Math.cos(this.time * n.frequency * 0.73 + n.phase * 3) +
        0.3 * Math.sin(this.time * n.frequency * 1.17 + n.phase);
      n.wanderX = smooth(n.wanderX, x * excursion, dt * n.follow / tune.response);
      n.wanderY = smooth(n.wanderY, y * excursion, dt * n.follow / tune.response);
    }
    this.updatePoints();
    // Motion and fading remain per-frame; neighbour selection has a 15 Hz budget.
    this.topologyAge += dt;
    if (this.topologyAge >= 1 / 15 - 1e-9) {
      this.selectNeighbours();
      this.topologyAge = 0;
    }
    this.lineCount = 0;
    for (const [k, e] of this.edges) {
      const on = e.selected;
      e.alpha = smooth(e.alpha, on ? 1 : 0, dt / ((on ? 0.16 : 0.32) * Math.sqrt(tune.response)));
      if (!on && e.alpha < 0.008) {
        this.edges.delete(k);
        continue;
      }
      const ax = this.points[e.i * 4],
        ay = this.points[e.i * 4 + 1],
        bx = this.points[e.j * 4],
        by = this.points[e.j * 4 + 1],
        d = Math.hypot(ax - bx, ay - by);
      const near = Math.max(0, 1 - d / e.reach),
        visibility =
          e.alpha *
          (0.085 + 0.16 * near * near) *
          (1 + mid * 0.15 + high * 0.08);
      if (this.lineCount + 2 > this.lines.length / 3) continue;
      let o = this.lineCount * 3;
      this.lines[o] = ax;
      this.lines[o + 1] = ay;
      this.lines[o + 2] = visibility;
      this.lines[o + 3] = bx;
      this.lines[o + 4] = by;
      this.lines[o + 5] = visibility;
      this.lineCount += 2;
    }
    return this;
  }
  selectNeighbours() {
    this.topologyBuilds++;
    const grid = this.grid, cellSize = 0.082;
    const key = (x, y) => x * 65536 + y;
    for (const bucket of grid.values()) bucket.length = 0;
    for (let i = 0; i < this.count; i++) {
      const k = key(Math.floor(this.points[i * 4] / cellSize), Math.floor(this.points[i * 4 + 1] / cellSize));
      let bucket = grid.get(k);
      if (!bucket) grid.set(k, bucket = []);
      bucket.push(i);
    }
    let used = 0;
    const candidates = this.candidates, pool = this.candidatePool;
    for (let i = 0; i < this.count; i++) {
      const x = this.points[i * 4], y = this.points[i * 4 + 1];
      const cx = Math.floor(x / cellSize), cy = Math.floor(y / cellSize);
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        const bucket = grid.get(key(cx + dx, cy + dy));
        if (!bucket) continue;
        for (const j of bucket) {
          if (j <= i) continue;
          const ax = x - this.points[j * 4], ay = y - this.points[j * 4 + 1];
          const d = ax * ax + ay * ay;
          const reach = 0.066 + 0.007 * (this.nodes[i].energy + this.nodes[j].energy);
          if (d >= reach * reach) continue;
          let c = pool[used];
          if (!c) pool[used] = c = { i: 0, j: 0, d: 0, reach: 0 };
          c.i = i; c.j = j; c.d = d; c.reach = reach;
          candidates[used++] = c;
        }
      }
    }
    candidates.length = used;
    candidates.sort((a, b) => a.d - b.d);
    const degree = this.degree;
    degree.fill(0);
    for (const e of this.edges.values()) e.selected = false;
    for (const c of candidates) {
      if (degree[c.i] >= 4 || degree[c.j] >= 4) continue;
      degree[c.i]++; degree[c.j]++;
      const k = c.i * this.count + c.j;
      let e = this.edges.get(k);
      if (!e) this.edges.set(k, e = { i: c.i, j: c.j, alpha: 0, reach: c.reach, selected: true });
      e.selected = true;
      e.reach = c.reach;
    }
  }

}
