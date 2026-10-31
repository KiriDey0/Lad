// SPDX-License-Identifier: AGPL-3.0-only
import {
  test,
  expect,
  _electron as electron,
  type ElectronApplication,
  type Page,
} from "@playwright/test";
import path from "node:path";
import fs from "node:fs";
import { execFileSync } from "node:child_process";
test.describe.configure({ mode: "serial" });
const root = path.resolve(__dirname, "..");
let app: ElectronApplication, page: Page;
test.beforeAll(async () => {
  test.setTimeout(90000);
  app = await electron.launch({
    executablePath: process.env.LAD_TEST_EXE || undefined,
    args: process.env.LAD_TEST_EXE ? [] : [root],
    env: {
      ...process.env,
      LAD_USER_DATA:
        process.env.LAD_TEST_PROFILE ||
        path.resolve(root, "../../work/test-profile"),
    },
    timeout: 60000,
  });
  page = await app.firstWindow();
  await page.waitForLoadState("domcontentloaded");
  const paths = await app.evaluate(({ app }) => ({
    data: app.getPath("userData"),
    session: app.getPath("sessionData"),
  }));
  expect(paths.data).toBe(
    process.env.LAD_TEST_PROFILE ||
      path.resolve(root, "../../work/test-profile"),
  );
  expect(paths.session).toBe(paths.data);
  await page.evaluate(() => {
    localStorage.removeItem("lad:pinned:v1:demo:demo");
    localStorage.removeItem("lad:pinned:v1:demo:pin-isolation-test");
  });
  expect(await page.evaluate(() => window.lad?.platform)).toBe("darwin");
  await expect
    .poll(() =>
      app.evaluate(({ BrowserWindow }) =>
        BrowserWindow.getAllWindows()[0].isVisible(),
      ),
    )
    .toBe(true);
  await app.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].hide(),
  );
});
test.afterAll(async () => {
  await app?.close();
});
test("welcome, demo, queue, synced lyrics and keyboard transport", async () => {
  await expect(
    page.getByRole("heading", { name: "Музыка здесь. Звук — где хочешь." }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Посмотреть демо" }).click();
  await expect(
    page.getByText("Демо · без звука", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Первый свет", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".queue-row")).toHaveCount(6);
  await expect(
    page.getByText("Синхронный текст", { exact: true }),
  ).toBeVisible();
  await page.evaluate(() => window.lad!.rpc("control", { action: "pause" }));
  const progress = page.getByRole("slider", {
    name: "Позиция трека",
    exact: true,
  });
  await progress.fill("46");
  await expect(progress).toHaveValue("46");
  await page.evaluate(() =>
    window.lad!.rpc("control", { action: "seek", value: 90 }),
  );
  await expect(progress).toHaveValue("90");
  await expect(page.locator(".progress-row > span").first()).toHaveText("1:30");
  await progress.dispatchEvent("pointerdown", { pointerId: 1 });
  await progress.fill("33");
  await expect(progress).toHaveValue("33");
  await progress.dispatchEvent("pointercancel", { pointerId: 1 });
  await expect(progress).toHaveValue("90");
  await progress.dispatchEvent("pointerdown", { pointerId: 1 });
  await progress.fill("55");
  await page.evaluate(() => window.dispatchEvent(new Event("blur")));
  await expect(progress).toHaveValue("90");
  await page.getByRole("button", { name: "Продолжить", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Пауза", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Мы останемся на своей орбите", exact: true })
    .click();
  expect(
    await page
      .locator(".lyric-line")
      .first()
      .evaluate((el) => parseFloat(getComputedStyle(el).fontSize)),
  ).toBeGreaterThanOrEqual(24);
  await expect(page.locator(".lyric-active")).toHaveText(
    "Мы останемся на своей орбите",
  );
  await page.keyboard.press("Space");
  await expect(
    page.getByRole("button", { name: "Продолжить", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Следующий трек", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Тихий город", exact: true }),
  ).toBeVisible();
  await page.locator(".queue-row").first().hover();
  await page
    .getByRole("button", { name: "Опустить Первый свет", exact: true })
    .click();
  await expect(page.locator(".queue-row").nth(1)).toContainText("Первый свет");
  const beforeDrag = await page
    .locator(".queue-track strong")
    .allTextContents();
  const dataTransfer = await page.evaluateHandle(() => new DataTransfer());
  await page
    .locator(".queue-row")
    .first()
    .dispatchEvent("dragstart", { dataTransfer });
  const target = await page.locator(".queue-row").nth(2).boundingBox();
  await page
    .locator(".queue-row")
    .nth(2)
    .dispatchEvent("dragover", {
      dataTransfer,
      clientY: target!.y + target!.height - 2,
    });
  await expect(page.locator(".queue-row").nth(3)).toHaveClass(
    /queue-drop-before/,
  );
  expect(
    await page
      .locator(".queue-drop-before")
      .evaluate((el) => getComputedStyle(el, "::before").backgroundColor),
  ).toBe("rgb(255, 255, 255)");
  await page
    .locator(".queue-row")
    .nth(2)
    .dispatchEvent("drop", { dataTransfer });
  const expectedDrag = [...beforeDrag];
  expectedDrag.splice(2, 0, expectedDrag.splice(0, 1)[0]);
  await expect(page.locator(".queue-track strong")).toHaveText(expectedDrag);
  await expect(
    page.locator(".queue-drop-before, .queue-drop-after"),
  ).toHaveCount(0);
});
test("catalog search, collection, add next and no layout overflow", async () => {
  await page.getByRole("button", { name: "Для тебя", exact: true }).click();
  await page
    .getByRole("button", { name: "Включить Мою волну", exact: true })
    .click();
  await expect(page.locator(".header-location")).toHaveText("Моя волна");
  await expect(page.locator(".wave-now-playing")).toBeVisible();
  await expect(page.locator(".wave-now-playing .art")).toBeVisible();
  await expect(page.getByLabel("Синхронный текст в карточке")).toBeVisible();
  await page.evaluate(() =>
    window.lad!.rpc("control", { action: "seek", value: 66 }),
  );
  await expect(page.locator(".wave-lyric-current")).toHaveText(
    "Мы останемся на своей орбите",
  );
  await page
    .locator(".home-hero")
    .getByRole("button", { name: "Пауза", exact: true })
    .click();
  await expect(
    page
      .locator(".home-hero")
      .getByRole("button", { name: "Продолжить волну", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: /^Продолжить слушать/ }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: /Сейчас в чарте/ }),
  ).toHaveCount(0);
  await page
    .getByRole("button", { name: "Включить Первый свет", exact: true })
    .click();
  await expect(page.locator(".queue-panel")).toContainText(
    "Продолжить слушать",
  );
  await expect(page.locator(".header-location")).toHaveText("Моя волна");
  await page.keyboard.press("Meta+k");
  await page
    .getByRole("textbox", { name: "Поиск музыки", exact: true })
    .fill("Ближе");
  await expect(page.locator(".track-row")).toHaveCount(1);
  await page
    .getByRole("button", {
      name: "Добавить Ближе к свету в очередь",
      exact: true,
    })
    .click();
  await page
    .getByRole("menuitem", { name: "Играть следующим", exact: true })
    .click();
  await expect(page.locator(".queue-row")).toHaveCount(7);
  await page.getByRole("button", { name: "Коллекция", exact: true }).click();
  await expect(page.locator(".music-card")).toHaveCount(4);
  const favorites = page.getByRole("region", {
    name: "Любимые треки в коллекции",
  });
  await expect(favorites).toBeVisible();
  await expect(favorites.locator(".track-row")).toHaveCount(3);
  await expect(favorites.locator(".liked-preview-heading")).toContainText(
    "3 треков",
  );
  await favorites.locator(".liked-preview-heading").click();
  await expect(page.locator(".header-location")).toHaveText("Мне нравится");
  await page.getByRole("button", { name: "Коллекция", exact: true }).click();
  await page.getByRole("tab", { name: "Альбомы", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Здесь пока пусто" }),
  ).toBeVisible();
  const overflow = await page.evaluate(() => ({
    x: document.documentElement.scrollWidth > innerWidth,
    y: document.documentElement.scrollHeight > innerHeight,
  }));
  expect(overflow).toEqual({ x: false, y: false });
});
test("track links and contextual actions preserve playback and fit the viewport", async () => {
  await page.evaluate(() => window.lad!.rpc("control", { action: "pause" }));
  const before = await page.evaluate(() => window.lad!.rpc("snapshot"));
  await page
    .locator(".player-track-name")
    .getByRole("button", {
      name: "Открыть исполнителя Лад Session",
      exact: true,
    })
    .click();
  await expect(page.locator(".header-location")).toHaveText("Исполнитель");
  await expect(
    page.getByRole("heading", { name: "Лад Session", exact: true }),
  ).toBeVisible();
  await page
    .locator(".player-track-name")
    .getByRole("button", { name: "Открыть альбом Тихий город", exact: true })
    .click();
  await expect(page.locator(".header-location")).toHaveText("Альбом");
  await expect(
    page.getByRole("heading", { name: "Тихий город", exact: true }),
  ).toBeVisible();
  const cover = await page.locator(".collection-heading > .art").boundingBox();
  expect(cover!.width).toBeCloseTo(cover!.height, 1);
  const row = page.locator(".track-row").first();
  await row.click({ button: "right" });
  const menu = page.getByRole("menu");
  await expect(menu).toBeVisible();
  await expect(
    menu.getByRole("menuitem", { name: "Играть следующим", exact: true }),
  ).toBeVisible();
  expect(await page.evaluate(() => window.lad!.rpc("snapshot"))).toMatchObject({
    playback: { track: { id: before.playback.track.id }, playing: false },
    queue: { index: before.queue.index },
  });
  const box = await menu.boundingBox();
  const viewport = await page.evaluate(() => ({
    width: innerWidth,
    height: innerHeight,
  }));
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.y + box!.height).toBeLessThanOrEqual(viewport.height);
  await page.keyboard.press("End");
  await expect(
    menu.getByRole("menuitem", { name: "К исполнителю: Лад Session" }),
  ).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(menu).toHaveCount(0);
  const queued = page.locator(".queue-row").last();
  await queued.click({ button: "right" });
  await expect(
    menu.getByRole("menuitem", { name: "Удалить из очереди", exact: true }),
  ).toBeEnabled();
  await menu
    .getByRole("menuitem", { name: "Удалить из очереди", exact: true })
    .click();
  await expect(page.locator(".queue-row")).toHaveCount(
    before.queue.tracks.length - 1,
  );
  await page.locator(".player-track").click({ button: "right" });
  await expect(menu).toBeVisible();
  const playerMenu = await menu.boundingBox();
  expect(playerMenu!.y + playerMenu!.height).toBeLessThanOrEqual(
    viewport.height,
  );
  await menu
    .getByRole("menuitem", { name: "Перейти к альбому", exact: true })
    .click();
  await expect(page.locator(".header-location")).toHaveText("Альбом");
});
test("collection pinning, album likes and batch queue actions", async () => {
  await page.evaluate(() => window.lad!.rpc("control", { action: "pause" }));
  await page.getByRole("button", { name: "Коллекция", exact: true }).click();
  await page.getByRole("tab", { name: "Плейлисты", exact: true }).click();
  await expect(page.locator(".music-card")).toHaveCount(4);
  let shortcuts = page.locator(".sidebar-playlists .playlist-shortcut");
  const original = await shortcuts.count();
  const title = await page.locator(".music-card strong").first().innerText();
  await shortcuts.filter({ hasText: title }).click({ button: "right" });
  await page
    .getByRole("menuitem", { name: "Открепить из «Твоей музыки»", exact: true })
    .click();
  await expect(shortcuts).toHaveCount(original - 1);
  await expect(page.locator(".music-card")).toHaveCount(4);
  await page.locator(".music-card").first().click({ button: "right" });
  await page
    .getByRole("menuitem", { name: "Закрепить в «Твоей музыке»", exact: true })
    .click();
  await expect(shortcuts).toHaveCount(original);
  const before = await page.evaluate(() => window.lad!.rpc("snapshot"));
  const batch = await page.evaluate(() =>
    window.lad!.rpc("collection", { type: "playlist", id: "demo-0" }),
  );
  await page.locator(".music-card").first().click({ button: "right" });
  await page
    .getByRole("menuitem", { name: "Играть следующим", exact: true })
    .click();
  await expect(page.locator(".queue-row")).toHaveCount(
    before.queue.tracks.length + batch.tracks.length,
  );
  const next = await page.evaluate(() => window.lad!.rpc("snapshot"));
  expect(
    next.queue.tracks
      .slice(
        before.queue.index + 1,
        before.queue.index + 1 + batch.tracks.length,
      )
      .map((t: any) => t.id),
  ).toEqual(batch.tracks.map((t: any) => t.id));
  expect(next.playback.track.id).toBe(before.playback.track.id);
  await page.locator(".music-card").first().click({ button: "right" });
  await page
    .getByRole("menuitem", { name: "В конец очереди", exact: true })
    .click();
  await expect(page.locator(".queue-row")).toHaveCount(
    before.queue.tracks.length + batch.tracks.length * 2,
  );
  const end = await page.evaluate(() => window.lad!.rpc("snapshot"));
  expect(
    end.queue.tracks.slice(-batch.tracks.length).map((t: any) => t.id),
  ).toEqual(batch.tracks.map((t: any) => t.id));
  await page
    .locator(".player-track-name")
    .getByRole("button", { name: "Открыть альбом Тихий город", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Добавить альбом в коллекцию", exact: true })
    .click();
  await expect(
    page.getByRole("button", {
      name: "Убрать альбом из коллекции",
      exact: true,
    }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Закрепить в «Твоей музыке»", exact: true })
    .click();
  await expect(shortcuts).toHaveCount(original + 1);
  await expect(shortcuts.filter({ hasText: "Альбом" })).toHaveCount(1);
  await page.getByRole("button", { name: "Коллекция", exact: true }).click();
  await page.getByRole("tab", { name: "Альбомы", exact: true }).click();
  await expect(page.locator(".music-card")).toHaveCount(1);
  await page.locator(".music-card").click({ button: "right" });
  await page
    .getByRole("menuitem", { name: "Убрать альбом из коллекции", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Здесь пока пусто", exact: true }),
  ).toBeVisible();
  await expect(shortcuts.filter({ hasText: "Альбом" })).toHaveCount(1);
  await app.close();
  app = await electron.launch({
    executablePath: process.env.LAD_TEST_EXE || undefined,
    args: process.env.LAD_TEST_EXE ? [] : [root],
    env: {
      ...process.env,
      LAD_USER_DATA:
        process.env.LAD_TEST_PROFILE ||
        path.resolve(root, "../../work/test-profile"),
    },
    timeout: 30000,
  });
  page = await app.firstWindow();
  await page.waitForLoadState("domcontentloaded");
  await app.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].hide(),
  );
  await page
    .getByRole("button", { name: "Посмотреть демо", exact: true })
    .click();
  shortcuts = page.locator(".sidebar-playlists .playlist-shortcut");
  await expect(shortcuts).toHaveCount(original + 1);
  await app.evaluate(async ({ BrowserWindow }) => {
    const window = BrowserWindow.getAllWindows()[0];
    const state = await window.webContents.executeJavaScript(
      'window.lad.rpc("snapshot")',
    );
    state.profile = { ...state.profile, uid: "pin-isolation-test" };
    window.webContents.send("orbit:event", { event: "state", data: state });
  });
  await expect(shortcuts).toHaveCount(original);
  await expect(shortcuts.filter({ hasText: "Альбом" })).toHaveCount(0);
  await app.evaluate(async ({ BrowserWindow }) => {
    const window = BrowserWindow.getAllWindows()[0];
    const state = await window.webContents.executeJavaScript(
      'window.lad.rpc("snapshot")',
    );
    window.webContents.send("orbit:event", { event: "state", data: state });
  });
  await expect(shortcuts).toHaveCount(original + 1);
  await shortcuts.filter({ hasText: "Альбом" }).click({ button: "right" });
  await page
    .getByRole("menuitem", { name: "Открепить из «Твоей музыки»", exact: true })
    .click();
  await expect(shortcuts).toHaveCount(original);
});
test("local audio engine loads, seeks, pauses and changes volume", async () => {
  const volumeSlider = page.getByRole("slider", {
    name: "Громкость",
    exact: true,
  });
  const volumeBox = await volumeSlider.boundingBox();
  expect(volumeBox!.width).toBeGreaterThanOrEqual(96);
  expect(volumeBox!.height).toBeGreaterThanOrEqual(40);
  await volumeSlider.fill("0.63");
  await volumeSlider.press("ArrowRight");
  await expect(volumeSlider).toHaveAttribute("aria-valuetext", "64%");
  await page
    .getByRole("button", { name: "Выключить звук", exact: true })
    .click();
  await expect(volumeSlider).toHaveValue("0");
  await page
    .getByRole("button", { name: "Включить звук", exact: true })
    .click();
  await expect(volumeSlider).toHaveValue("0.64");
  const url = await page.evaluate(() => {
    const samples = 8000 * 5;
    const data = new ArrayBuffer(44 + samples * 2);
    const view = new DataView(data);
    const str = (offset: number, text: string) =>
      [...text].forEach((c, i) => view.setUint8(offset + i, c.charCodeAt(0)));
    str(0, "RIFF");
    view.setUint32(4, 36 + samples * 2, true);
    str(8, "WAVE");
    str(12, "fmt ");
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, 1, true);
    view.setUint32(24, 8000, true);
    view.setUint32(28, 16000, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    str(36, "data");
    view.setUint32(40, samples * 2, true);
    return URL.createObjectURL(new Blob([data], { type: "audio/wav" }));
  });
  const send = (data: Record<string, unknown>) =>
    app.evaluate(
      ({ BrowserWindow }, payload) =>
        BrowserWindow.getAllWindows()[0].webContents.send("orbit:event", {
          event: "local",
          data: payload,
        }),
      data,
    );
  await send({
    action: "load",
    url,
    position: 1,
    paused: false,
    generation: 99,
    volume: 0.2,
    track: { title: "Silent audio test" },
  });
  await expect
    .poll(() =>
      page.locator("audio").evaluate((el: HTMLAudioElement) => el.readyState),
    )
    .toBeGreaterThanOrEqual(3);
  await expect
    .poll(() =>
      page.locator("audio").evaluate((el: HTMLAudioElement) => el.paused),
    )
    .toBe(false);
  await send({ action: "pause" });
  await expect
    .poll(() =>
      page.locator("audio").evaluate((el: HTMLAudioElement) => el.paused),
    )
    .toBe(true);
  await send({ action: "seek", value: 2 });
  await expect
    .poll(() =>
      page.locator("audio").evaluate((el: HTMLAudioElement) => el.currentTime),
    )
    .toBe(2);
  await send({ action: "volume", value: 0.65 });
  await expect
    .poll(() =>
      page.locator("audio").evaluate((el: HTMLAudioElement) => el.volume),
    )
    .toBe(1); // Listening gain is downstream; wave-volume.spec verifies the actual output.
  await page.locator("audio").evaluate((el: HTMLAudioElement) => {
    el.removeAttribute("src");
    el.load();
  });
  await page.evaluate((u) => URL.revokeObjectURL(u), url);
  await page.evaluate(async () => {
    const snapshot = await window.lad!.rpc("snapshot");
    await window.lad!.rpc("play", {
      tracks: snapshot.queue.tracks.map((track: any, i: number) => ({
        ...track,
        explicit: i === 0,
      })),
      index: 0,
    });
  });
  await expect(page.locator(".player .explicit")).toHaveText("E");
  const badge = page.locator(".player .explicit");
  await badge.hover();
  await expect(page.getByRole("tooltip")).toContainText("Контент 18+");
  await expect(page.getByRole("tooltip")).toBeInViewport();
  const badgeRect = await badge.boundingBox();
  const tipRect = await page.getByRole("tooltip").boundingBox();
  expect(Math.abs(tipRect!.x - badgeRect!.x)).toBeLessThan(250);
  await page.locator(".main-play").hover();
  await expect(page.getByRole("tooltip")).toHaveCount(0);
  await badge.focus();
  await expect(page.getByRole("tooltip")).toBeVisible();
  await badge.press("Escape");
  await expect(page.getByRole("tooltip")).toHaveCount(0);
  await expect(
    page.locator(".queue-row").first().locator(".explicit"),
  ).toHaveText("E");
});
test("devices transfer, settings theme roles, login dialog and logout", async () => {
  await page.evaluate(() => window.lad!.rpc("control", { action: "pause" }));
  await page
    .getByRole("button", { name: "Устройство воспроизведения", exact: true })
    .click();
  await expect(
    page.getByRole("dialog", { name: "Где будем слушать?" }),
  ).toBeVisible();
  const popupStyle = await page.locator(".modal").evaluate((el) => {
    const css = getComputedStyle(el);
    return {
      background: css.backgroundColor,
      blur: css.backdropFilter,
      scrollbar: css.scrollbarWidth,
    };
  });
  expect(popupStyle.background).toMatch(/0\.7[45]/);
  expect(popupStyle.blur).toBe("blur(14px)");
  expect(popupStyle.scrollbar).toBe("none");
  await expect(
    page.getByRole("button", { name: "Обновить устройства" }),
  ).toBeEnabled();
  const viewport = page.viewportSize();
  await page.setViewportSize({ width: 1050, height: 650 });
  await expect(
    page.getByRole("slider", { name: "Громкость", exact: true }),
  ).toBeInViewport();
  await app.evaluate(async ({ BrowserWindow }) => {
    const window = BrowserWindow.getAllWindows()[0];
    const state = await window.webContents.executeJavaScript(
      'window.lad.rpc("snapshot")',
    );
    state.devices = Array.from({ length: 20 }, (_, i) => ({
      ...state.devices[0],
      id: `long-list-${i}`,
      name: `Колонка ${i + 1}`,
    }));
    state.devices.unshift({ id: "demo-midi", name: "Станция Миди · пример" });
    window.webContents.send("orbit:event", { event: "state", data: state });
  });
  await expect(page.locator(".device-option")).toHaveCount(22);
  const before = await page.locator(".modal-footer").boundingBox();
  const scrolled = await page.locator(".modal-scroll").evaluate((el) => {
    el.scrollTop = el.scrollHeight;
    return el.scrollTop;
  });
  expect(
    scrolled,
    JSON.stringify(
      await page.locator(".modal-scroll").evaluate((el) => ({
        height: el.clientHeight,
        scroll: el.scrollHeight,
        overflow: getComputedStyle(el).overflowY,
        modal: el.parentElement?.getBoundingClientRect().height,
        viewport: innerHeight,
        classes: el.parentElement?.className,
      })),
    ),
  ).toBeGreaterThan(0);
  expect(await page.locator(".modal-footer").boundingBox()).toEqual(before);
  await expect(
    page.getByRole("button", { name: "Слушать на Mac", exact: true }),
  ).toBeInViewport();
  await page.locator(".modal-scroll").evaluate((el) => {
    el.scrollTop = 0;
  });
  if (viewport) await page.setViewportSize(viewport);
  await page.locator(".modal").hover({ position: { x: 70, y: 50 } });
  await expect(page.locator(".modal")).toHaveAttribute(
    "data-pointer-active",
    "true",
  );
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.locator(".modal").hover({ position: { x: 100, y: 60 } });
  await expect(page.locator(".modal")).not.toHaveAttribute(
    "data-pointer-active",
    "true",
  );
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.getByRole("button", { name: /Станция Миди · пример/ }).click();
  await page
    .getByRole("button", { name: "Продолжить: Станция Миди · пример", exact: true })
    .click();
  await expect(page.locator(".device-selector")).toContainText("Станция Миди");
  await page.getByRole("button", { name: "Настройки", exact: true }).click();
  const syncVolume = page.getByRole("switch", {
    name: "Синхронизировать громкость с устройством",
    exact: true,
  });
  await expect(syncVolume).toHaveAttribute("aria-checked", "false");
  await syncVolume.click();
  await expect(syncVolume).toHaveAttribute("aria-checked", "true");
  await syncVolume.click();
  await expect(syncVolume).toHaveAttribute("aria-checked", "false");
  const scale = page.getByLabel("Размер интерфейса", { exact: true });
  await expect(scale).toHaveValue("85");
  const originalIconSize = await page
    .locator(".nav-item svg")
    .first()
    .boundingBox();
  await scale.selectOption("110");
  await expect
    .poll(() =>
      app.evaluate(({ BrowserWindow }) =>
        BrowserWindow.getAllWindows()[0].webContents.getZoomFactor(),
      ),
    )
    .toBe(1.1);
  await expect(scale).toHaveValue("110");
  await scale.selectOption("85");
  await expect
    .poll(() =>
      app.evaluate(({ BrowserWindow }) =>
        BrowserWindow.getAllWindows()[0].webContents.getZoomFactor(),
      ),
    )
    .toBe(0.85);
  expect(await page.locator(".nav-item svg").first().boundingBox()).toEqual(
    originalIconSize,
  );
  const brightness = page.getByRole("slider", {name:"Яркость волны", exact:true});
  const smoothing = page.getByRole("slider", {name:"Плавность движения", exact:true});
  await expect(brightness).toHaveValue("100");
  await expect(smoothing).toHaveValue("50");
  await brightness.fill("50");
  await smoothing.fill("0");
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem("orbit-theme")!).waveSmoothing)).toBe(0);
  await smoothing.fill("100");
  await expect(brightness).toHaveValue("50");
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem("orbit-theme")!).waveSmoothing)).toBe(100);
  await smoothing.fill("50");
  await brightness.fill("100");
  const contentFilter = page.getByRole("switch", {
    name: "Не воспроизводить 18+",
    exact: true,
  });
  await expect(contentFilter).not.toBeChecked();
  const knob = contentFilter.locator("span");
  await expect(knob).toBeVisible();
  const switchRect = await contentFilter.boundingBox();
  const offRect = await knob.boundingBox();
  expect(offRect!.width).toBe(20);
  expect(Math.round(offRect!.x - switchRect!.x)).toBe(4);
  await contentFilter.click();
  await expect(contentFilter).toBeChecked();
  await expect
    .poll(async () => {
      const onRect = await knob.boundingBox();
      return Math.round(onRect!.x - switchRect!.x);
    })
    .toBe(24);
  await page
    .getByRole("button", { name: "Устройство воспроизведения", exact: true })
    .click();
  await expect(
    page.getByText("Фильтр 18+ включён в Ладе", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Закрыть окно", exact: true }).click();
  await contentFilter.click();
  await expect(contentFilter).not.toBeChecked();
  await page.getByRole("button", { name: "OLED", exact: true }).click();
  expect(
    await page.evaluate(() =>
      getComputedStyle(document.documentElement)
        .getPropertyValue("--bg")
        .trim(),
    ),
  ).toBe("#000000");
  await page.getByLabel("Окраска интерфейса").selectOption("tinted");
  await page.getByLabel("Цвет акцента").fill("#70a8dd");
  const colors = await page.evaluate(() => ({
    bg: getComputedStyle(document.documentElement).getPropertyValue("--bg"),
    accent: getComputedStyle(document.documentElement).getPropertyValue(
      "--accent",
    ),
  }));
  expect(colors.accent.trim()).not.toBe("#E2E6EC");
  await page.getByRole("button", { name: "Сбросить оформление" }).click();
  await page.getByRole("button", { name: "Выйти из демо" }).click();
  await expect(
    page.getByRole("heading", { name: "Музыка здесь. Звук — где хочешь." }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Войти в Яндекс", exact: true })
    .first()
    .click();
  await expect(
    page.getByRole("dialog", { name: "Твоя музыка начинается здесь" }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
});

// Terminate only the sidecar of this isolated Electron test process.
test("backend exit is reported without crashing or hanging RPC", async () => {
  const pid = await app.evaluate(() => process.pid);
  const childIds =
    process.platform === "win32"
      ? execFileSync(
          "powershell.exe",
          [
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            `(Get-CimInstance Win32_Process -Filter 'ParentProcessId = ${pid}' | Where-Object { $_.Name -eq 'lad-backend.exe' }).ProcessId`,
          ],
          { encoding: "utf8" },
        )
      : execFileSync("ps", ["-axo", "pid=,ppid=,command="], {
          encoding: "utf8",
        })
          .split("\n")
          .filter((line) => {
            const parts = line.trim().split(/\s+/, 3);
            return Number(parts[1]) === pid && /python|lad-backend/.test(line);
          })
          .map((line) => line.trim().split(/\s+/)[0])
          .join("\n");
  const children = childIds.trim().split(/\s+/).filter(Boolean).map(Number);
  expect(children).toHaveLength(1);
  process.kill(children[0], "SIGKILL");
  await expect
    .poll(
      async () =>
        page.evaluate(async () => {
          try {
            await window.lad!.rpc("snapshot");
            return "alive";
          } catch {
            return "stopped";
          }
        }),
      { timeout: 5000 },
    )
    .toBe("stopped");
  expect(await page.title()).toContain("Лад");
  expect(app.process().exitCode).toBeNull();
});
