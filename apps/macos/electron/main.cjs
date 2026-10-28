// SPDX-License-Identifier: AGPL-3.0-only
const {
  app,
  BrowserWindow,
  ipcMain,
  safeStorage,
  shell,
  session,
  Menu,
  clipboard,
} = require("electron");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const readline = require("node:readline");
const { isYandexUrl } = require("./auth-policy.cjs");
app.commandLine.appendSwitch("autoplay-policy", "no-user-gesture-required");
let window,
  child,
  authWindow,
  quitting = false;
let counter = 0;
let resolveBackendReady;
const backendReady = new Promise((resolve) => {
  resolveBackendReady = resolve;
});
const pending = new Map();
const root = path.resolve(__dirname, "..");
const allowed = new Set([
  "snapshot",
  "setContentFilter",
  "setVolumeSync",
  "demo",
  "logout",
  "loginToken",
  "authStart",
  "authPoll",
  "devices",
  "connect",
  "output",
  "localState",
  "localEnded",
  "localError",
  "control",
  "play",
  "queue",
  "lyrics",
  "search",
  "liked",
  "home",
  "library",
  "collection",
  "link",
  "like",
  "albumLike",
  "wave",
]);
const dataPath = () => app.getPath("userData");
const secretPath = () => path.join(dataPath(), "session.enc");
const queuePath = () => path.join(dataPath(), "queue.json");
const statePath = () => path.join(dataPath(), "window.json");
let secrets = {},
  queueState = {};
let lastState;
function loadSession() {
  try {
    if (fs.existsSync(secretPath()) && safeStorage.isEncryptionAvailable())
      secrets = JSON.parse(
        safeStorage.decryptString(fs.readFileSync(secretPath())),
      );
  } catch {}
  try {
    queueState = JSON.parse(fs.readFileSync(queuePath(), "utf8"));
  } catch {}
}
function saveSecret(data) {
  try {
  secrets = data;
  fs.mkdirSync(dataPath(), { recursive: true });
  if (!data.musicToken && !data.cookie) {
    try {
      fs.unlinkSync(secretPath());
    } catch {}
    return;
  }
  if (safeStorage.isEncryptionAvailable())
    fs.writeFileSync(
      secretPath(),
      safeStorage.encryptString(JSON.stringify(data)),
      { mode: 0o600 },
    );
  else
    send("notice", {
      message:
        "Защищённое хранилище недоступно. Сессия останется только до закрытия приложения.",
    });
  } catch { send("notice", { message: "Не удалось сохранить сессию. После перезапуска понадобится войти снова." }); }
}
function send(event, data) {
  if (window && !window.isDestroyed())
    window.webContents.send("orbit:event", { event, data });
}
function rpc(method, params = {}) {
  return new Promise((resolve, reject) => {
    if (!child || child.killed || child.exitCode !== null || child.stdin.destroyed || !child.stdin.writable)
      return reject(
        new Error("Музыкальный сервис не запущен. Перезапусти Лад."),
      );
    let payload;
    const id = ++counter;
    try { payload = JSON.stringify({ id, method, params }) + "\n"; }
    catch { return reject(new Error("Некорректные параметры действия.")); }
    const timer = setTimeout(
      () => {
        pending.delete(id);
        reject(
          new Error("Действие заняло слишком много времени. Попробуй ещё раз."),
        );
      },
      method === "boot" ? 60000 : 45000,
    );
    pending.set(id, { resolve, reject, timer });
    const fail = () => {
      const item = pending.get(id);
      if (!item) return;
      clearTimeout(item.timer);
      pending.delete(id);
      item.reject(new Error("Музыкальный сервис остановился. Перезапусти Лад."));
    };
    try { child.stdin.write(payload, (error) => { if (error) fail(); }); }
    catch { fail(); }
  });
}
function startBackend() {
  const executable = app.isPackaged
    ? path.join(
        process.resourcesPath,
        "backend",
        process.platform === "win32" ? "lad-backend.exe" : "lad-backend",
      )
    : process.env.LAD_PYTHON ||
      path.join(root, ".venv", "bin/python");
  child = spawn(
    executable,
    app.isPackaged ? [] : [path.join(root, "backend/app.py")],
    {
      stdio: ["pipe", "pipe", "pipe"],
      env: {
        ...process.env,
        PYTHONUNBUFFERED: "1",
        ...(app.isPackaged
          ? {
              LAD_MUSIC_SOURCE: path.join(
                process.resourcesPath,
                "backend-source",
                "vendor",
              ),
            }
          : {}),
      },
    },
  );
  const backend = child;
  backend.stdin.on("error", () => {
    for (const item of pending.values()) {
      clearTimeout(item.timer);
      item.reject(new Error("Связь с музыкальным сервисом потеряна. Перезапусти Лад."));
    }
    pending.clear();
  });
  const lines = readline.createInterface({ input: child.stdout });
  lines.on("line", async (line) => {
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      return;
    }
    if (msg.id) {
      const item = pending.get(msg.id);
      if (!item) return;
      clearTimeout(item.timer);
      pending.delete(msg.id);
      return msg.error
        ? item.reject(new Error(msg.error))
        : item.resolve(msg.result);
    }
    if (msg.event === "credentials") return saveSecret(msg.data);
    if (msg.event === "persist") {
      queueState = msg.data;
      try {
        fs.mkdirSync(dataPath(), { recursive: true });
        fs.writeFileSync(queuePath(), JSON.stringify(queueState), { mode: 0o600 });
      } catch { send("notice", { message: "Не удалось сохранить очередь на диск." }); }
      return;
    }
    if (msg.event === "ready") {
      try {
        await rpc("boot", { credentials: secrets, saved: queueState });
      } catch (e) {
        send("notice", { message: e.message });
      } finally {
        resolveBackendReady();
      }
      return;
    }
    if (msg.event === "state") {
      lastState = msg.data;
    }
    send(msg.event, msg.data);
  });
  // Never forward network responses, credentials, or Python stderr into the renderer or a log file.
  child.stderr.on("data", () => {});
  child.on("error", () => {
    resolveBackendReady();
    send("notice", {
      message:
        "Не удалось запустить музыкальный сервис. Проверь сборку приложения.",
    });
  });
  child.on("exit", () => {
    if (child === backend) child = null;
    resolveBackendReady();
    for (const item of pending.values()) {
      clearTimeout(item.timer);
      item.reject(
        new Error("Музыкальный сервис остановился. Перезапусти Лад."),
      );
    }
    pending.clear();
    if (!quitting)
      send("notice", {
        message: "Музыкальный сервис остановился. Перезапусти Лад.",
      });
  });
}
function openAuth() {
  if (authWindow && !authWindow.isDestroyed()) {
    authWindow.focus();
    return Promise.resolve(false);
  }
  return new Promise((resolve, reject) => {
    let completed = false,
      checking = false;
    let failures = 0;
    const authSession = session.fromPartition(`lad-auth-${Date.now()}`, {
      cache: false,
    });
    authSession.setPermissionRequestHandler((_wc, _p, cb) => cb(false));
    authWindow = new BrowserWindow({
      width: 520,
      height: 760,
      parent: window,
      title: "Вход в Яндекс · Лад",
      autoHideMenuBar: true,
      webPreferences: {
        session: authSession,
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true,
      },
    });
    authWindow.webContents.on(
      "did-fail-load",
      (_event, code, _description, _url, isMainFrame) => {
        if (isMainFrame && code !== -3)
          send("notice", {
            message:
              "Страница входа не загрузилась. Можно закрыть её и войти по коду устройства.",
          });
      },
    );
    authWindow.webContents.setWindowOpenHandler(({ url }) => {
      if (isYandexUrl(url)) authWindow.loadURL(url);
      return { action: "deny" };
    });
    authWindow.webContents.on("will-navigate", (event, url) => {
      if (!isYandexUrl(url)) event.preventDefault();
    });
    const check = async () => {
      if (completed || checking || !authWindow || authWindow.isDestroyed())
        return;
      checking = true;
      try {
        const cookies = await authSession.cookies.get({ domain: "yandex.ru" });
        if (!cookies.some((c) => c.name === "Session_id" && c.value)) return;
        const byName = new Map(cookies.map((c) => [c.name, c.value]));
        const cookie = [...byName].map(([k, v]) => `${k}=${v}`).join("; ");
        completed = true;
        const result = await rpc("stationSession", { cookie });
        resolve(result);
        if (authWindow && !authWindow.isDestroyed()) authWindow.close();
      } catch (error) {
        failures++;
        completed = failures >= 3;
        if (completed) {
          reject(new Error(error.message + " Можно войти по коду устройства."));
          if (authWindow && !authWindow.isDestroyed()) authWindow.close();
        }
      } finally {
        checking = false;
      }
    };
    const timer = setInterval(check, 1500);
    authWindow.on("closed", () => {
      clearInterval(timer);
      authWindow = null;
      authSession.clearStorageData().catch(() => {});
      if (!completed) resolve(false);
    });
    authWindow
      .loadURL(
        "https://passport.yandex.ru/auth?retpath=https%3A%2F%2Fyandex.ru%2F",
      )
      .catch(() => reject(new Error("Не удалось открыть вход в Яндекс.")));
  });
}
function buildWindow() {
  let bounds = { width: 1440, height: 940 };
  try {
    bounds = { ...bounds, ...JSON.parse(fs.readFileSync(statePath(), "utf8")) };
  } catch {}
  window = new BrowserWindow({
    ...bounds,
    minWidth: 1024,
    minHeight: 700,
    title: "Лад · Музыка на Станции",
    backgroundColor: "#0C0D0F",
    titleBarStyle: process.platform === "darwin" ? "hiddenInset" : "default",
    trafficLightPosition: { x: 22, y: 22 },
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      zoomFactor: 0.85,
    },
  });
  window.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  window.webContents.session.setPermissionRequestHandler((_wc, _p, cb) =>
    cb(false),
  );
  window.webContents.on("will-navigate", (event, url) => {
    const allowedUrl =
      process.env.LAD_DEV_URL || "file://" + path.join(root, "dist/index.html");
    if (!url.startsWith(allowedUrl)) event.preventDefault();
  });
  window.once("ready-to-show", () => window.show());
  window.webContents.once("did-finish-load", () => {
    if (lastState) send("state", lastState);
  });
  window.on("close", (event) => {
    if (
      !quitting &&
      (lastState?.playback?.playing || lastState?.queue?.owned)
    ) {
      event.preventDefault();
      window.hide();
      return;
    }
    try {
      fs.mkdirSync(dataPath(), { recursive: true });
      fs.writeFileSync(statePath(), JSON.stringify(window.getBounds()));
    } catch {}
  });
  if (process.env.LAD_DEV_URL) window.loadURL(process.env.LAD_DEV_URL);
  else window.loadFile(path.join(root, "dist/index.html"));
}
app.whenReady().then(() => {
  app.setName("Лад");
  if (process.env.LAD_USER_DATA)
    app.setPath("userData", process.env.LAD_USER_DATA);
  loadSession();
  const { UpdateService } = require("./updates.cjs");
  const platform = process.platform === "darwin" ? "macos-arm64" :
    "windows-x64-" + (fs.readdirSync(path.dirname(process.execPath)).some(n => /^uninstall.*\.exe$/i.test(n)) ? "installer" : "portable");
  const updater = new UpdateService({version:app.getVersion(),platform,
    cache:path.join(dataPath(),"updates"),execPath:process.execPath,appPath:app.getAppPath(),
    emit:state => { if(window && !window.isDestroyed())window.webContents.send("lad:update",state); },
    quit:() => app.quit()});
  ipcMain.handle("lad:updates", async (event, action) => {
    if(event.sender !== window?.webContents || !["state","check","download","cancel","install"].includes(action))throw Error("Действие недоступно");
    if(action === "install" && !app.isPackaged)throw Error("Установка доступна в собранном приложении");
    return updater.action(action);
  });
  if(process.env.LAD_UPDATE_ACK) {
    const ack = process.env.LAD_UPDATE_ACK;
    delete process.env.LAD_UPDATE_ACK;
    const ready = () => { if(window?.webContents.isLoading() || !lastState || !child || child.exitCode !== null)setTimeout(ready,100);else fs.writeFileSync(ack,JSON.stringify({version:app.getVersion(),authenticated:!!lastState.profile,queueCount:lastState.queue?.tracks?.length||0}),{mode:0o600}); };
    backendReady.then(()=>setTimeout(ready,300));
  }

  const menu = [
    ...(process.platform === "darwin"
      ? [
          {
            label: "Лад",
            submenu: [
              { role: "about" },
              { type: "separator" },
              {
                label: "Показать Лад",
                click: () => {
                  window.show();
                  window.focus();
                },
              },
              { role: "hide" },
              { type: "separator" },
              { role: "quit" },
            ],
          },
        ]
      : []),
    {
      label: "Правка",
      submenu: [
        { role: "undo" },
        { role: "redo" },
        { type: "separator" },
        { role: "cut" },
        { role: "copy" },
        { role: "paste" },
        { role: "selectAll" },
      ],
    },
    {
      label: "Воспроизведение",
      submenu: [
        {
          label: "Пауза / продолжить",
          accelerator: "MediaPlayPause",
          click: () => rpc("control", { action: "toggle" }).catch(() => {}),
        },
        {
          label: "Следующий",
          accelerator: "MediaNextTrack",
          click: () => rpc("control", { action: "next" }).catch(() => {}),
        },
        {
          label: "Предыдущий",
          accelerator: "MediaPreviousTrack",
          click: () => rpc("control", { action: "previous" }).catch(() => {}),
        },
      ],
    },
    {
      label: "Окно",
      submenu: [
        { role: "minimize" },
        { role: "zoom" },
        {
          label: "Показать Лад",
          click: () => {
            window.show();
            window.focus();
          },
        },
        { role: "close" },
      ],
    },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(menu));
  buildWindow();
  startBackend();
  ipcMain.handle("orbit:scale", (event, scale) => {
    if (
      event.sender !== window.webContents ||
      !Number.isFinite(scale) ||
      scale < 0.75 ||
      scale > 1.15
    )
      throw new Error("Некорректный масштаб интерфейса.");
    window.webContents.setZoomFactor(scale);
    return scale;
  });
  ipcMain.handle("orbit:rpc", async (event, method, params) => {
    if (event.sender !== window.webContents || !allowed.has(method))
      throw new Error("Недоступное действие.");
    await backendReady;
    return rpc(method, params);
  });
  ipcMain.handle("orbit:login", async (event) => {
    if (event.sender !== window.webContents) throw new Error("Недоступно");
    await backendReady;
    return openAuth();
  });
  ipcMain.handle("orbit:external", (_event, url) => {
    if (isYandexUrl(url)) return shell.openExternal(url);
    throw new Error("Неподдерживаемая ссылка.");
  });
  ipcMain.handle("orbit:copy", (_event, text) =>
    clipboard.writeText(String(text).slice(0, 1000)),
  );
  app.on("activate", () => {
    if (!window || window.isDestroyed()) buildWindow();
    else {
      window.show();
      window.focus();
    }
  });
});
app.on("before-quit", (event) => {
  if (quitting) return;
  event.preventDefault();
  quitting = true;
  Promise.race([
    rpc("prepareShutdown").catch(() => {}),
    new Promise((resolve) => setTimeout(resolve, 1500)),
  ]).finally(() => {
    if (child && !child.stdin.destroyed) child.stdin.end();
    app.quit();
  });
});
app.on("will-quit", () => {
  if (child) child.kill();
});
app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
