// SPDX-License-Identifier: AGPL-3.0-only
const { test } = require("node:test"),
  assert = require("node:assert/strict"),
  { EventEmitter } = require("node:events");
const { installWavePower } = require("../electron/wave-power.cjs");
test("power budget follows AC/battery and thermal speed limits; renderer access is scoped", () => {
  const monitor = new EventEmitter();
  let battery = false;
  monitor.isOnBatteryPower = () => battery;
  let handler;
  const events = [],
    contents = { send: (...args) => events.push(args) },
    window = { webContents: contents, isDestroyed: () => false };
  const read = installWavePower({
    powerMonitor: monitor,
    ipcMain: {
      handle: (_name, fn) => {
        handler = fn;
      },
    },
    getWindow: () => window,
  });
  assert.deepEqual(read(), { fps: 30 });
  battery = true;
  monitor.emit("on-battery");
  assert.deepEqual(read(), { fps: 20 });
  battery = false;
  monitor.emit("on-ac");
  assert.deepEqual(read(), { fps: 30 });
  monitor.emit("speed-limit-change", {}, { limit: 60 });
  assert.deepEqual(read(), { fps: 20 });
  monitor.emit("speed-limit-change", {}, { limit: 100 });
  assert.deepEqual(handler({ sender: contents }), { fps: 30 });
  assert.throws(() => handler({ sender: {} }), /Недоступно/);
  assert.equal(events.length, 4);
});
