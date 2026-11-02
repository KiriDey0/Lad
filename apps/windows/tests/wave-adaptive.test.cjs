// SPDX-License-Identifier: AGPL-3.0-only
const { test } = require("node:test"),
  assert = require("node:assert/strict"),
  { EventEmitter } = require("node:events");
const { WaveBudget, cpuLoad } = require("../electron/wave-budget.cjs");
const { installWavePower } = require("../electron/wave-power.cjs");
const normal = { saving: false };
const quiet = (p) => p.sample(0.2, 0.4);
test("auto warms up, ignores short spikes, yields to sustained CPU load, and recovers slowly", () => {
  const p = new WaveBudget();
  for (let i = 0; i < 4; i++) quiet(p);
  assert.equal(p.fps(normal), 30);
  quiet(p);
  assert.equal(p.fps(normal), 60);
  p.sample(0.7, 0.8);
  assert.equal(p.fps(normal), 60);
  quiet(p);
  assert.equal(p.fps(normal), 60);
  p.sample(0.7, 0.8);
  p.sample(0.7, 0.8);
  assert.equal(p.fps(normal), 30);
  p.sample(0.9, 0.95);
  assert.equal(p.fps(normal), 20);
  quiet(p);
  quiet(p);
  assert.equal(p.fps(normal), 20);
  quiet(p);
  assert.equal(p.fps(normal), 30);
  quiet(p);
  quiet(p);
  assert.equal(p.fps(normal), 60);
});
test("a busy core and slow frames lower the budget even when average CPU is low", () => {
  const p = new WaveBudget();
  for (let i = 0; i < 5; i++) quiet(p);
  p.sample(0.2, 0.98);
  p.sample(0.2, 0.98);
  assert.equal(p.fps(normal), 30);
  for (let i = 0; i < 5; i++) quiet(p);
  p.sample(0.2, 0.4, 1);
  p.sample(0.2, 0.4, 1);
  assert.equal(p.fps(normal), 30);
  p.sample(0.2, 0.4, 2);
  assert.equal(p.fps(normal), 20);
});
test("all quality choices respect OS power restrictions; unknown saving state cannot enable 60", () => {
  for (const mode of ["auto", "eco", "smooth"]) {
    const p = new WaveBudget();
    p.setMode(mode);
    for (let i = 0; i < 6; i++) quiet(p);
    for (const state of [
      { battery: true },
      { saving: true },
      { limited: true },
      { thermal: true },
    ])
      assert.equal(p.fps({ ...normal, ...state }), 20);
    assert.equal(p.fps({ saving: null }), mode === "eco" ? 20 : 30);
  }
  const p = new WaveBudget();
  p.setMode("smooth");
  quiet(p);
  assert.equal(p.fps(normal), 60);
  p.setMode("eco");
  assert.equal(p.fps(normal), 20);
  p.setMode("injected");
  assert.equal(p.mode, "eco");
});
test("CPU load uses counter differences and detects a saturated individual core", () => {
  const make = (idle, busy) => ({
    times: { idle, user: busy, nice: 0, sys: 0, irq: 0 },
  });
  const a = [make(100, 100), make(100, 100)],
    b = [make(200, 100), make(100, 200)];
  assert.deepEqual(cpuLoad(a, b), { load: 0.5, peak: 1 });
  assert.equal(cpuLoad(null, b), null);
  assert.equal(cpuLoad(a, a), null);
  assert.equal(cpuLoad(a, [make(50, 100)]), null);
});
test("sampler sleeps when paused, hidden, minimized or eco; saving and thermal events override 60", async () => {
  const monitor = new EventEmitter();
  monitor.isOnBatteryPower = () => false;
  const win = new EventEmitter();
  win.webContents = { send: () => {} };
  win.isDestroyed = () => false;
  let visible = true,
    minimized = false,
    saving = false,
    tick,
    handler,
    calls = 0,
    time = 0;
  win.isVisible = () => visible;
  win.isMinimized = () => minimized;
  const timers = {
    setInterval(fn) {
      tick = fn;
      return { unref() {} };
    },
    clearInterval() {
      tick = null;
    },
  };
  const read = installWavePower({
    powerMonitor: monitor,
    ipcMain: {
      handle(_n, fn) {
        handler = fn;
      },
    },
    getWindow: () => win,
    timers,
    cpus: () => {
      calls++;
      time += 100;
      return [
        { times: { idle: time, user: time / 4, sys: 0, nice: 0, irq: 0 } },
      ];
    },
    readSaving: async () => saving,
  });
  const request = (mode, active, pressure = 0) =>
    handler({ sender: win.webContents }, { mode, active, pressure });
  const flush = () => new Promise((resolve) => setImmediate(resolve));
  assert.equal(calls, 0);
  assert.equal(read().fps, 30);
  request("auto", true);
  await flush();
  assert(tick);
  for (let i = 0; i < 5; i++) {
    tick();
    await flush();
  }
  assert.equal(read().fps, 60);
  saving = true;
  tick();
  await flush();
  assert.equal(read().fps, 20);
  saving = false;
  tick();
  await flush();
  assert.equal(read().fps, 60);
  monitor.emit("thermal-state-change", { state: "serious" });
  assert.equal(read().fps, 20);
  monitor.emit("thermal-state-change", { state: "nominal" });
  assert.equal(read().fps, 60);
  request("auto", false);
  assert.equal(tick, null);
  const slept = calls;
  request("eco", true);
  assert.equal(tick, null);
  assert.equal(calls, slept);
  request("auto", true);
  visible = false;
  win.emit("hide");
  assert.equal(tick, null);
  visible = true;
  win.emit("show");
  assert(tick);
  minimized = true;
  win.emit("minimize");
  assert.equal(tick, null);
  minimized = false;
  win.emit("restore");
  assert(tick);
  assert.throws(() => handler({ sender: {} }, { mode: "auto", active: true }));
  assert.throws(() => request("auto", true, 99));
  win.emit("closed");
  assert.equal(tick, null);
});
