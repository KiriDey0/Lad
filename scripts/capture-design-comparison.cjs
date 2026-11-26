// SPDX-License-Identifier: AGPL-3.0-only
// Capture real Classic and Orbit renderers with independent disposable profiles.
const {
  _electron: electron,
} = require("../apps/macos/node_modules/@playwright/test");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const root = path.resolve(__dirname, "..");
const classic = path.resolve(root, "../implementation/apps/macos");
const orbit = path.join(root, "apps/macos");
const output = path.join(root, "work/design-comparison");
fs.mkdirSync(output, { recursive: true });
const screens = [
  ["01-wave", "Моя волна"],
  ["02-search", "Поиск"],
  ["03-collection", "Коллекция"],
  ["04-playlists", "Плейлисты"],
  ["05-lyrics", "Текст трека"],
  ["06-queue", "Очередь"],
  ["07-appearance", "Оформление"],
  ["08-settings-bottom", "Нижние карточки настроек"],
  ["09-audio", "Профиль звука"],
  ["10-equalizer", "Эквалайзер"],
  ["11-devices", "Выбор устройства"],
];
const sizes = [
  { id: "wide", width: 1440, height: 847, scale: 85 },
  { id: "compact", width: 1100, height: 800, scale: 100 },
];
const manifest = {
  created: "2026-11-27",
  classicCommit: "7d2536c",
  orbitCommit: "working tree",
  sizes,
  screens,
  images: [],
};
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
async function run(design, spec) {
  const profile = fs.mkdtempSync(
    path.join(os.tmpdir(), `lad-compare-${design}-`),
  );
  const app = await electron.launch({
    executablePath: path.join(
      orbit,
      "node_modules/electron/dist/Electron.app/Contents/MacOS/Electron",
    ),
    args: [design === "classic" ? classic : orbit],
    env: {
      ...process.env,
      LAD_USER_DATA: profile,
      LAD_PYTHON: process.env.LAD_PYTHON || "/tmp/lad-queue-venv/bin/python",
    },
    timeout: 60000,
  });
  try {
    const page = await app.firstWindow();
    page.setDefaultTimeout(12000);
    await app.evaluate(({ BrowserWindow }, spec) => {
      const win = BrowserWindow.getAllWindows()[0];
      win.setMinimumSize(320, 540);
      win.setContentSize(spec.width, spec.height);
    }, spec);
    await page.waitForLoadState("domcontentloaded");
    await page.evaluate(() => window.lad.rpc("snapshot"));
    await page
      .getByRole("button", { name: "Посмотреть демо", exact: true })
      .click();
    await page.evaluate(
      (theme) => {
        localStorage.setItem("orbit-theme", JSON.stringify(theme));
      },
      {
        base: "dark",
        mode: "default",
        seed: "#BFCAF5",
        transparency: true,
        lyricOffset: 0,
        scale: spec.scale,
        waveIntensity: 70,
        waveSmoothing: 50,
        waveQuality: "auto",
      },
    );
    await page.reload();
    await page.evaluate(() => window.lad.rpc("snapshot"));
    for (let i = 0; i < 30; i++) {
      const zoom = await app.evaluate(({ BrowserWindow }) =>
        BrowserWindow.getAllWindows()[0].webContents.getZoomFactor(),
      );
      if (Math.abs(zoom - spec.scale / 100) < 0.001) break;
      await pause(50);
    }
    const seed = await page.evaluate(() => window.lad.rpc("snapshot"));
    const colors = [
      ["#b8c9bc", "#264f51"],
      ["#c5b2dc", "#433659"],
      ["#dbbc96", "#653d2d"],
      ["#aec8d4", "#264a66"],
      ["#d8a7b5", "#4c263d"],
      ["#d9d9c8", "#323d38"],
    ];
    const tracks = Array.from({ length: 30 }, (_, i) => {
      const t = { ...seed.queue.tracks[i % seed.queue.tracks.length] };
      const [a, b] = colors[i % 6];
      const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512"><defs><linearGradient id="g" x2="1" y2="1"><stop stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient></defs><rect width="512" height="512" fill="url(#g)"/><circle cx="310" cy="280" r="180" fill="none" stroke="#ffffff38" stroke-width="2"/><circle cx="250" cy="220" r="135" fill="none" stroke="#ffffff50" stroke-width="2"/><path d="M60 455 450 65M80 510 500 90" stroke="#ffffff20" stroke-width="36"/><text x="38" y="74" fill="#fff" font-family="sans-serif" font-size="24" letter-spacing="8">ЛАД SESSION</text><text x="38" y="466" fill="#fff" font-family="sans-serif" font-size="16">0${(i % 6) + 1} / MUSIC STUDY</text></svg>`;
      t.cover =
        "data:image/svg+xml;base64," + Buffer.from(svg).toString("base64");
      return t;
    });
    await page.evaluate(async (tracks) => {
      await window.lad.rpc("play", {
        tracks,
        index: 12,
        source: "Лад Session · пример",
      });
      await window.lad.rpc("control", { action: "pause" });
      await window.lad.rpc("control", { action: "seek", value: 66 });
    }, tracks);
    const nav = async (label) => {
      await page.getByRole("button", { name: label, exact: true }).click();
      await page.locator(".page-content").evaluate((el) => (el.scrollTop = 0));
    };
    const queue = async (visible) => {
      if ((await page.locator(".queue-panel").count()) === Number(visible))
        return;
      if (visible)
        await page
          .locator(".player-right")
          .getByRole("button", { name: "Показать очередь", exact: true })
          .click();
      else
        await page
          .getByRole("button", { name: "Скрыть очередь", exact: true })
          .click();
    };
    const capture = async (id) => {
      await page.evaluate(() => document.fonts.ready);
      await page.mouse.move(2, 2);
      await pause(280);
      const image = await app.evaluate(async ({ BrowserWindow }) =>
        (await BrowserWindow.getAllWindows()[0].webContents.capturePage())
          .toPNG()
          .toString("base64"),
      );
      const file = `${spec.id}-${id}-${design}.png`;
      fs.writeFileSync(path.join(output, file), Buffer.from(image, "base64"));
      const geometry = await page.evaluate(() => ({
        cssWidth: innerWidth,
        cssHeight: innerHeight,
        scale: JSON.parse(localStorage.getItem("orbit-theme")).scale,
      }));
      manifest.images.push({
        design,
        size: spec.id,
        screen: id,
        file,
        ...geometry,
      });
      console.log(`${design} ${spec.id} ${id}`);
    };
    await queue(false);
    await nav("Для тебя");
    await capture("01-wave");
    await nav("Поиск");
    await page.locator('input[aria-label="Поиск музыки"]').fill("Тихий");
    await page.locator(".track-row").first().waitFor();
    await capture("02-search");
    await nav("Коллекция");
    await page.locator(".collection-group").first().waitFor();
    await capture("03-collection");
    await page
      .locator(".collection-group")
      .filter({ hasText: "Плейлисты" })
      .click();
    await page.locator(".music-card").first().waitFor();
    await capture("04-playlists");
    await nav("Сейчас играет");
    await capture("05-lyrics");
    await nav("Для тебя");
    await queue(true);
    await capture("06-queue");
    await queue(false);
    await nav("Настройки");
    await page
      .locator(".settings-section")
      .filter({
        has: page.getByRole("heading", { name: "Оформление", exact: true }),
      })
      .evaluate(
        (el) => (el.closest(".page-content").scrollTop = el.offsetTop - 18),
      );
    await capture("07-appearance");
    await page
      .locator(".page-content")
      .evaluate((el) => (el.scrollTop = el.scrollHeight));
    await capture("08-settings-bottom");
    await page
      .locator(".setting-row")
      .filter({ has: page.getByText("Профиль звука", { exact: true }) })
      .getByRole("button", { name: "Настроить", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "Профиль звука", exact: true })
      .waitFor();
    await capture("09-audio");
    await page.getByRole("tab", { name: "Эквалайзер", exact: true }).click();
    await capture("10-equalizer");
    await nav("Для тебя");
    const state = await page.evaluate(() => window.lad.rpc("snapshot"));
    state.devices = Array.from({ length: 8 }, (_, i) => ({
      id: "compare-device-" + i,
      name:
        i === 0
          ? "Яндекс Станция Миди"
          : i === 1
            ? "Яндекс ТВ"
            : "Устройство с Алисой " + i,
      platform: "yandexmidi",
      model: "Яндекс Станция",
      room: i < 2 ? "Гостиная" : "",
      household: "Мой дом",
      metadataStatus: i < 2 ? "ready" : "unavailable",
    }));
    await app.evaluate(({ ipcMain, BrowserWindow }, state) => {
      const fixture = structuredClone(state);
      ipcMain.removeHandler("orbit:rpc");
      ipcMain.handle("orbit:rpc", (_e, method) =>
        method === "devices" ? fixture.devices : fixture,
      );
      BrowserWindow.getAllWindows()[0].webContents.send("orbit:event", {
        event: "state",
        data: fixture,
      });
    }, state);
    await page.locator(".device-selector").click();
    await page
      .getByRole("dialog", { name: "Где будем слушать?", exact: true })
      .waitFor();
    await capture("11-devices");
  } finally {
    await app.close();
    fs.rmSync(profile, { recursive: true, force: true });
  }
}
(async () => {
  for (const size of sizes)
    for (const design of ["classic", "orbit"]) await run(design, size);
  fs.writeFileSync(
    path.join(output, "manifest.json"),
    JSON.stringify(manifest, null, 2),
  );
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
