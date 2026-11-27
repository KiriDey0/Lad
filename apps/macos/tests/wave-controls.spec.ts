// SPDX-License-Identifier: AGPL-3.0-only
import { test, expect, _electron as electron } from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
const root = path.resolve(__dirname, "..");
const launch = async () => {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), "lad-wave-controls-"));
  const app = await electron.launch({
    executablePath: process.env.LAD_TEST_EXE || undefined,
    args: process.env.LAD_TEST_EXE ? [] : [root],
    env: {
      ...process.env,
      LAD_USER_DATA: profile,
      LAD_PYTHON: process.env.LAD_PYTHON,
    },
  });
  const page = await app.firstWindow();
  await page.evaluate(() => window.lad!.rpc("snapshot"));
  await page
    .getByRole("button", { name: "Посмотреть демо", exact: true })
    .click();
  await page.evaluate(() => window.lad!.rpc("control", { action: "pause" }));
  return { app, page, profile };
};
test("progress flows continuously, fits wide tracks and flattens on pause or reduced motion", async () => {
  const { app, page, profile } = await launch();
  try {
    await app.evaluate(({ BrowserWindow }) => {
      const win = BrowserWindow.getAllWindows()[0];
      win.setContentSize(1440, 800);
      win.show();
      win.focus();
      win.webContents.setBackgroundThrottling(false);
    });
    await page.evaluate(() =>
      window.lad!.rpc("control", { action: "seek", value: 100 }),
    );
    const scrubber = page.locator(".orbit-scrubber"),
      svg = scrubber.locator("svg"),
      played = scrubber.locator(".orbit-scrubber__line");
    await page.locator(".player .main-play").click();
    await expect(scrubber).toHaveAttribute("data-playing", "true");
    await expect(svg).toHaveCSS("animation-play-state", "running");
    await expect(svg).toHaveCSS("opacity", "1");
    const phase = await svg.evaluate((el) => getComputedStyle(el).transform);
    await expect
      .poll(() => svg.evaluate((el) => getComputedStyle(el).transform))
      .not.toBe(phase);
    const dimensions = await scrubber.evaluate((el) => {
      const wave = el.querySelector("svg")!,
        art = el.querySelector(".orbit-scrubber__art")!,
        line = el
          .querySelector(".orbit-scrubber__line")!
          .getBoundingClientRect(),
        rest = el
          .querySelector(".orbit-scrubber__track")!
          .getBoundingClientRect(),
        head = el
          .querySelector(".orbit-scrubber__cursor")!
          .getBoundingClientRect();
      return {
        svgWidth: Number(wave.getAttribute("width")),
        art: art.clientWidth,
        gap: rest.left - line.right,
        before: line.right <= head.left,
        after: rest.left >= head.right,
      };
    });
    expect(dimensions.svgWidth).toBeGreaterThan(dimensions.art + 32);
    expect(dimensions.before).toBe(true);
    expect(dimensions.after).toBe(true);
    expect(dimensions.gap).toBeGreaterThan(5);
    const pathBefore = await svg.locator("path").getAttribute("d");
    await page.evaluate(() =>
      window.lad!.rpc("control", { action: "seek", value: 20 }),
    );
    expect(await svg.locator("path").getAttribute("d")).toBe(pathBefore);
    await page.locator(".player .main-play").click();
    await expect(svg).toHaveCSS("animation-play-state", "paused");
    await expect(svg).toHaveCSS("opacity", "0");
    expect(
      await played.evaluate((el) => getComputedStyle(el, "::before").opacity),
    ).toBe("1");
    const frozen = await svg.evaluate((el) => getComputedStyle(el).transform);
    await page.waitForTimeout(100);
    expect(await svg.evaluate((el) => getComputedStyle(el).transform)).toBe(
      frozen,
    );
    await page.locator(".player .main-play").click();
    await page.emulateMedia({ reducedMotion: "reduce" });
    await expect(svg).toHaveCSS("animation-name", "none");
    await expect(svg).toHaveCSS("opacity", "0");
    const seek = page.getByRole("slider", {
      name: "Позиция трека",
      exact: true,
    });
    await seek.focus();
    await page.keyboard.press("Home");
    await page.keyboard.press("ArrowRight");
    await expect
      .poll(async () =>
        Math.floor(
          (await page.evaluate(() => window.lad!.rpc("snapshot"))).playback
            .position,
        ),
      )
      .toBe(5);
  } finally {
    await app.close();
    fs.rmSync(profile, { recursive: true, force: true });
  }
});
for (const width of [1440, 320])
  test(`wave preferences use working capsule choices at ${width}px`, async () => {
    const { app, page, profile } = await launch();
    try {
      await app.evaluate(({ BrowserWindow }, width) => {
        const win = BrowserWindow.getAllWindows()[0];
        win.setContentSize(width, 850);
        win.show();
        win.focus();
      }, width);
      await page
        .getByRole(width < 760 ? "link" : "button", {
          name: "Для тебя",
          exact: true,
        })
        .click();
      const state = await page.evaluate(() => window.lad!.rpc("snapshot"));
      state.demo = false;
      await app.evaluate(({ ipcMain, BrowserWindow }, state) => {
        const groups = [
          {
            key: "diversity",
            name: "Характер волны",
            selected: "favorite",
            values: [
              { value: "favorite", name: "Любимое" },
              { value: "discover", name: "Незнакомое" },
              { value: "popular", name: "Популярное" },
              { value: "default", name: "Любое" },
            ],
          },
          {
            key: "moodEnergy",
            name: "Настроение",
            selected: "default",
            values: [
              { value: "fun", name: "Бодрое" },
              { value: "active", name: "Весёлое" },
              { value: "calm", name: "Спокойное" },
              { value: "sad", name: "Грустное" },
              { value: "default", name: "Любое" },
            ],
          },
          {
            key: "language",
            name: "Язык",
            selected: "russian",
            values: [
              { value: "russian", name: "Русский" },
              { value: "foreign", name: "Иностранный" },
              { value: "default", name: "Любой" },
              { value: "without", name: "Без слов" },
            ],
          },
        ];
        (globalThis as any).savedWavePreferences = null;
        ipcMain.removeHandler("orbit:rpc");
        ipcMain.handle("orbit:rpc", (_e, method, params) => {
          if (method === "waveSettings") {
            if (params?.settings)
              (globalThis as any).savedWavePreferences = params.settings;
            return { groups };
          }
          if (method === "home")
            return {
              tracks: state.queue.tracks,
              cards: [],
              tracksTitle: "Проверка",
            };
          if (method === "lyrics") return { lines: [], synced: false };
          if (method === "liked") return { tracks: [], total: 0 };
          if (method === "library")
            return { playlists: [], albums: [], artists: [] };
          return state;
        });
        BrowserWindow.getAllWindows()[0].webContents.send("orbit:event", {
          event: "state",
          data: state,
        });
      }, state);
      await page
        .locator(".wave-home-controls")
        .getByRole("button", { name: "Настроить", exact: true })
        .click();
      const dialog = page.getByRole("dialog", {
        name: "Настроить волну",
        exact: true,
      });
      await expect(
        dialog.getByRole("group", { name: "Характер волны", exact: true }),
      ).toBeVisible();
      for (const choice of await dialog
        .locator(".wave-preference-values button")
        .all())
        await expect(choice).toHaveCSS("border-radius", "999px");
      await dialog
        .getByRole("button", { name: "Незнакомое", exact: true })
        .click();
      await expect(
        dialog.getByRole("button", { name: "Незнакомое", exact: true }),
      ).toHaveAttribute("aria-pressed", "true");
      const box = (await dialog.boundingBox())!;
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(
        (await page.evaluate(() => innerWidth)) + 1,
      );
      await page.waitForTimeout(250);
      const png = await app.evaluate(async ({ BrowserWindow }) =>
        (await BrowserWindow.getAllWindows()[0].webContents.capturePage())
          .toPNG()
          .toString("base64"),
      );
      fs.writeFileSync(
        path.resolve(
          root,
          `../../work/orbit-preview/wave-settings-rounded-${width}.png`,
        ),
        Buffer.from(png, "base64"),
      );
      await dialog
        .getByRole("button", { name: "Со следующего трека", exact: true })
        .click();
      await expect(dialog).toHaveCount(0);
      expect(
        await app.evaluate(() => (globalThis as any).savedWavePreferences),
      ).toEqual({
        diversity: "discover",
        moodEnergy: "default",
        language: "russian",
      });
    } finally {
      await app.close();
      fs.rmSync(profile, { recursive: true, force: true });
    }
  });

test("middle clicks and quick seeks keep the last selected position while playing", async () => {
  const { app, page, profile } = await launch();
  try {
    await app.evaluate(({ BrowserWindow }) =>
      BrowserWindow.getAllWindows()[0].setContentSize(1440, 800),
    );
    await page.locator(".player .main-play").click();
    const slider = page.getByRole("slider", {
      name: "Позиция трека",
      exact: true,
    });
    const box = (await slider.boundingBox())!;
    const length = (await page.evaluate(() => window.lad!.rpc("snapshot")))
      .playback.duration;
    // No acknowledgement waits between clicks: playback updates must not undo the last click.
    for (const ratio of [0.5, 0.25, 0.5, 0.75, 0.5, 0.2, 0.5])
      await page.mouse.click(box.x + box.width * ratio, box.y + box.height / 2);
    await expect
      .poll(async () =>
        Math.abs(
          (await page.evaluate(() => window.lad!.rpc("snapshot"))).playback
            .position -
            length * 0.5,
        ),
      )
      .toBeLessThan(3);
    await slider.focus();
    await page.keyboard.press("Home");
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    await expect
      .poll(async () =>
        Math.round(
          (await page.evaluate(() => window.lad!.rpc("snapshot"))).playback
            .position,
        ),
      )
      .toBe(10);
    await page.locator(".player .main-play").click();
    // Cancelling a drag must not commit the previewed value.
    const before = (await page.evaluate(() => window.lad!.rpc("snapshot")))
      .playback.position;
    await page.mouse.move(box.x + box.width * 0.3, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.8, box.y + box.height / 2, {
      steps: 5,
    });
    await slider.dispatchEvent("pointercancel", {
      pointerId: 1,
      pointerType: "mouse",
      isPrimary: true,
    });
    await page.mouse.up();
    expect(
      (await page.evaluate(() => window.lad!.rpc("snapshot"))).playback
        .position,
    ).toBeCloseTo(before, 0);
  } finally {
    await app.close();
    fs.rmSync(profile, { recursive: true, force: true });
  }
});
