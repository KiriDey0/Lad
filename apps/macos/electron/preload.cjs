// SPDX-License-Identifier: AGPL-3.0-only
const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("lad", {
  rpc: (method, params = {}) => ipcRenderer.invoke("orbit:rpc", method, params),
  login: () => ipcRenderer.invoke("orbit:login"),
  open: (url) => ipcRenderer.invoke("orbit:external", url),
  copy: (text) => ipcRenderer.invoke("orbit:copy", text),
  platform: process.platform,
  setScale: (scale) => ipcRenderer.invoke("orbit:scale", scale),
  onEvent: (callback) => {
    const listener = (_event, data) => callback(data);
    ipcRenderer.on("orbit:event", listener);
    return () => ipcRenderer.removeListener("orbit:event", listener);
  },
});
