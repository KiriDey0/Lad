// SPDX-License-Identifier: AGPL-3.0-only
function installWavePower({ powerMonitor, ipcMain, getWindow }) {
  let limited = false;
  const read = () => ({
    fps: limited || powerMonitor.isOnBatteryPower() ? 20 : 30,
  });
  const publish = () => {
    const window = getWindow();
    if (window && !window.isDestroyed())
      window.webContents.send("orbit:event", {
        event: "wavePower",
        data: read(),
      });
  };
  powerMonitor.on("on-ac", publish);
  powerMonitor.on("on-battery", publish);
  powerMonitor.on("speed-limit-change", (_event, details) => {
    limited = details.limit < 80;
    publish();
  });
  ipcMain.handle("lad:wave-power", (event) => {
    if (event.sender !== getWindow()?.webContents) throw Error("Недоступно");
    return read();
  });
  return read;
}
module.exports = { installWavePower };
