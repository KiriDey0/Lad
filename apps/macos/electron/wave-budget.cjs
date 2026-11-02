// SPDX-License-Identifier: AGPL-3.0-only
const modes = new Set(["auto", "eco", "smooth"]);
class WaveBudget {
  constructor() {
    this.mode = "auto";
    this.reset();
  }
  reset() {
    this.level = this.mode === "smooth" ? 60 : 30;
    this.quiet = this.busy = 0;
    this.ready = false;
  }
  setMode(mode) {
    if (modes.has(mode) && mode !== this.mode) {
      this.mode = mode;
      this.reset();
    }
  }
  sample(load, peak, pressure = 0) {
    if (!Number.isFinite(load) || !Number.isFinite(peak)) return;
    this.ready = true;
    const smooth = this.mode === "smooth";
    if (load >= (smooth ? 0.93 : 0.85) || pressure === 2) {
      this.level = 20;
      this.quiet = this.busy = 0;
    } else if (
      load >= (smooth ? 0.8 : 0.65) ||
      peak >= (smooth ? 0.99 : 0.95) ||
      pressure === 1
    ) {
      this.quiet = 0;
      if (++this.busy >= 2) this.level = Math.min(this.level, 30);
    } else if (load < (smooth ? 0.55 : 0.4) && peak < 0.85) {
      this.busy = 0;
      this.quiet++;
      if (this.quiet >= (smooth ? 3 : 5)) this.level = 60;
      else if (this.level === 20 && this.quiet >= 3) this.level = 30;
    } else {
      this.quiet = this.busy = 0;
    }
  }
  fps({
    battery = false,
    saving = null,
    limited = false,
    thermal = false,
  } = {}) {
    if (this.mode === "eco" || battery || saving === true || limited || thermal)
      return 20;
    if (saving !== false || !this.ready) return 30;
    return this.level;
  }
}
function cpuLoad(previous, current) {
  if (!previous || previous.length !== current.length || !current.length)
    return null;
  let idle = 0,
    total = 0,
    peak = 0;
  for (let i = 0; i < current.length; i++) {
    const before = previous[i].times,
      after = current[i].times;
    const ticks = Object.keys(after).reduce(
      (sum, key) => sum + after[key] - before[key],
      0,
    );
    const rest = after.idle - before.idle;
    if (ticks <= 0 || rest < 0 || rest > ticks) return null;
    total += ticks;
    idle += rest;
    peak = Math.max(peak, 1 - rest / ticks);
  }
  return { load: 1 - idle / total, peak };
}
module.exports = { WaveBudget, cpuLoad, modes };
