// SPDX-License-Identifier: AGPL-3.0-only
import {waveSmoothing} from "./appearance.mjs";
const clamp = (v) => Math.max(0, Math.min(1, v));
const follow = (v, t, dt, attack, release) =>
  v + (t - v) * (1 - Math.exp(-dt / (t > v ? attack : release)));
export class WaveFeatures {
  constructor() {
    this.reset();
  }
  reset() {
    this.values = new Float32Array(8);
    this.avg = new Float32Array(8);
    this.previous = new Float32Array(8);
    this.bass = 0;
    this.mid = 0;
    this.high = 0;
    this.cooldown = 0;
    this.age = 0;
    this.level = 0;
  }
  process(input, dt) {
    dt = Math.min(0.1, Math.max(0.001, dt));
    this.age += dt;
    this.cooldown = Math.max(0, this.cooldown - dt);
    const raw = input.spectrum || [
      input.bass,
      input.bass,
      input.mid,
      input.mid,
      input.mid,
      input.high,
      input.high,
      input.high,
    ];
    let onset = 0;
    for (let i = 0; i < 8; i++) {
      const v = Math.max(0, raw[i] || 0),
        prior = this.avg[i];
      this.avg[i] = follow(prior, v, dt, this.age < 0.5 ? 0.2 : 2.5, 3.5);
      const relative = v / Math.max(0.003, prior);
      // Log compression retains quiet detail without turning silence into signal.
      const absolute = clamp((20 * Math.log10(Math.max(v, 0.00001)) + 65) / 48);
      const dynamic = clamp((relative - 0.7) / 1.3);
      const value = absolute * (0.3 + 0.7 * dynamic);
      this.values[i] = follow(this.values[i], value, dt, 0.012, 0.11);
      if (
        i < 3 &&
        v > 0.003 &&
        v - this.previous[i] > Math.max(0.001, prior * 0.1) &&
        relative > 1.35
      )
        onset = Math.max(onset, clamp((relative - 1.1) / 1.9) * absolute);
      this.previous[i] = v;
    }
    const avg = (a, b) => {
      let sum = 0;
      for (let i = a; i < b; i++) sum += this.values[i];
      return sum / (b - a);
    };
    this.bass = avg(0, 2);
    this.mid = avg(2, 5);
    this.high = avg(5, 8);
    let impact = 0;
    if (onset > 0.3 && this.cooldown === 0 && this.age > 0.25) {
      impact = onset;
      this.cooldown = 0.24;
    }
    this.level = follow(
      this.level,
      Math.max(this.bass, this.mid, this.high),
      dt,
      0.035,
      0.25,
    );
    return {
      bass: this.bass,
      mid: this.mid,
      high: this.high,
      impact,
      level: this.level,
      spectrum: this.values,
    };
  }
}
export class WaveMotion {
  constructor() {
    this.spectrum = new Float32Array(8);
    this.bass = 0;
    this.mid = 0;
    this.high = 0;
    this.level = 0;
  }
  update(input, dt, responsiveness = 1, smoothing = 50) {
    dt = Math.min(0.05, Math.max(0.001, dt));
    const response = responsiveness * waveSmoothing(smoothing).response;
    const ease = (v, t) => {
      const delta =
        follow(v, t, dt, 0.03 * response, 0.16 * response) - v;
      const limit = (8 / response) * dt;
      return v + Math.max(-limit, Math.min(limit, delta));
    };
    const raw = input.spectrum || new Float32Array(8);
    for (let i = 0; i < 8; i++)
      this.spectrum[i] = ease(
        this.spectrum[i],
        raw[i] * 0.85 + raw[(i + 7) % 8] * 0.075 + raw[(i + 1) % 8] * 0.075,
      );
    for (const key of ["bass", "mid", "high", "level"])
      this[key] = ease(this[key], input[key] || 0);
    return this;
  }
}
