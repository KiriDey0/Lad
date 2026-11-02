// SPDX-License-Identifier: AGPL-3.0-only
const { WaveBudget, cpuLoad, modes } = require("./wave-budget.cjs");
function installWavePower({
  powerMonitor,
  ipcMain,
  getWindow,
  readSaving = async () => null,
  cpus = require("node:os").cpus,
  timers = globalThis,
}) {
  const budget = new WaveBudget();
  let limited = false,
    thermal = false,
    saving = null,
    active = false,
    pressure = 0;
  let timer,
    previous,
    pending = false,
    boundWindow,
    lastFPS;
  const read = () => ({
    fps: budget.fps({
      battery: powerMonitor.isOnBatteryPower(),
      saving,
      limited,
      thermal,
    }),
  });
  const publish = () => {
    const win = getWindow(),
      value = read();
    if (win && !win.isDestroyed() && value.fps !== lastFPS) {
      lastFPS = value.fps;
      win.webContents.send("orbit:event", { event: "wavePower", data: value });
    }
  };
  const sample = () => {
    const current = cpus(),
      load = cpuLoad(previous, current);
    previous = current;
    if (load) budget.sample(load.load, load.peak, pressure);
    if (!pending) {
      pending = true;
      Promise.resolve()
        .then(readSaving)
        .then((value) => {
          saving = typeof value === "boolean" ? value : null;
          publish();
        })
        .catch(() => {
          saving = null;
          publish();
        })
        .finally(() => {
          pending = false;
        });
    }
    publish();
  };
  const sync = () => {
    const win = getWindow();
    const running =
      active &&
      budget.mode !== "eco" &&
      win &&
      !win.isDestroyed() &&
      win.isVisible?.() !== false &&
      win.isMinimized?.() !== true;
    if (running && !timer) {
      previous = cpus();
      timer = timers.setInterval(sample, 2000);
      timer.unref?.();
      sample();
    } else if (!running && timer) {
      timers.clearInterval(timer);
      timer = undefined;
      previous = undefined;
      budget.reset();
    }
    publish();
  };
  const changed = () => {
    publish();
  };
  powerMonitor.on("on-ac", changed);
  powerMonitor.on("on-battery", changed);
  powerMonitor.on("speed-limit-change", (details) => {
    if (!Number.isFinite(details?.limit)) return;
    limited = details.limit < 100;
    publish();
  });
  powerMonitor.on("thermal-state-change", (details) => {
    if (!details || typeof details.state !== "string") return;
    thermal = ["fair", "serious", "critical"].includes(details.state);
    publish();
  });
  try {
    thermal = ["fair", "serious", "critical"].includes(
      powerMonitor.getCurrentThermalState?.(),
    );
  } catch {}
  ipcMain.handle("lad:wave-power", (event, request) => {
    const win = getWindow();
    if (event.sender !== win?.webContents) throw Error("Недоступно");
    if (request !== undefined) {
      if (
        !request ||
        !modes.has(request.mode) ||
        typeof request.active !== "boolean" ||
        (request.pressure !== undefined &&
          ![0, 1, 2].includes(request.pressure))
      )
        throw Error("Некорректный режим волны");
      budget.setMode(request.mode);
      active = request.active;
      pressure = request.pressure || 0;
      if (boundWindow !== win) {
        boundWindow = win;
        for (const name of ["show", "hide", "minimize", "restore"])
          win.on?.(name, sync);
        win.once?.("closed", () => {
          active = false;
          sync();
        });
      }
      sync();
    }
    return read();
  });
  return read;
}
module.exports = { installWavePower };
