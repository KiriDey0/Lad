// SPDX-License-Identifier: AGPL-3.0-only
import { test, expect, _electron as electron } from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
const root = path.resolve(__dirname, "..");
for (const width of [320, 390])
  test(`compact capsule dock follows swipes and keeps playback controls at ${width}px`, async () => {
    test.setTimeout(60000);
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), "lad-mobile-dock-"));
    const app = await electron.launch({
      executablePath: process.env.LAD_TEST_EXE || undefined,
      args: process.env.LAD_TEST_EXE ? [] : [root],
      env: {
        ...process.env,
        LAD_USER_DATA: profile,
        LAD_PYTHON: process.env.LAD_PYTHON,
      },
    });
    try {
      const page = await app.firstWindow();
      await app.evaluate(({ BrowserWindow }, width) => {
        const win = BrowserWindow.getAllWindows()[0];
        win.setContentSize(width, 800);
        win.show();
        win.focus();
        win.webContents.setBackgroundThrottling(false);
      }, width);
      await page.evaluate(() => window.lad!.rpc("snapshot"));
      await page
        .getByRole("button", { name: "Посмотреть демо", exact: true })
        .click();
      await page.evaluate(async () => {
        const state = await window.lad!.rpc("snapshot");
        const template = state.queue.tracks[0];
        const tracks = ["#2555cc", "#d32b35", "#24b27f"].map(
          (color, index) => ({
            ...template,
            id: index < 2 ? template.id : state.queue.tracks[1].id,
            title: `Карточка ${index + 1}`,
            cover: `data:image/svg+xml,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96"><rect width="96" height="96" fill="${color}"/></svg>`)}`,
          }),
        );
        await window.lad!.rpc("play", {
          tracks,
          index: 1,
          source: "Mobile QA",
        });
        await window.lad!.rpc("control", { action: "pause" });
        await window.lad!.rpc("control", { action: "seek", value: 90 });
      });
      await page.getByRole("link", { name: "Для тебя", exact: true }).click();
      const dock = page.locator(".mobile-dock"),
        viewport = page.locator(".mobile-player-viewport"),
        card = page.locator('.mobile-track-card[data-current="true"]');
      const state = () => page.evaluate(() => window.lad!.rpc("snapshot"));
      await expect(dock).toBeVisible();
      await expect(page.locator(".player-dock")).toBeHidden();
      await expect(dock.locator(".lad-mobile-navigation")).toBeVisible();
      await expect(card.locator("strong")).toHaveText("Карточка 2");
      await expect(
        card.getByRole("button", { name: "Продолжить", exact: true }),
      ).toBeVisible();
      const first = await state();
      const dockBefore = (await dock.boundingBox())!;
      const colorBefore = await dock.evaluate((el) =>
        getComputedStyle(el).getPropertyValue("--mobile-track-color"),
      );
      const fill = await card
        .locator(".mobile-track-fill")
        .evaluate((el) => (el as HTMLElement).style.width);
      expect(parseFloat(fill)).toBeCloseTo(
        (90 / first.playback.duration) * 100,
        0,
      );
      await expect(dock.getByRole("slider")).toHaveCount(0);
      const box = (await viewport.boundingBox())!,
        startX = box.x + box.width * 0.55,
        y = box.y + box.height * 0.5;
      await page.mouse.move(startX, y);
      await page.mouse.down();
      await page.mouse.move(startX - box.width * 0.32, y, { steps: 10 });
      expect((await state()).queue.index).toBe(1);
      const transform = await page
        .locator(".mobile-player-strip")
        .evaluate((el) => getComputedStyle(el).transform);
      expect(transform).not.toBe("none");
      expect((await dock.boundingBox())!.y).toBe(dockBefore.y);
      await expect(viewport).toHaveAttribute("data-phase", "dragging");
      const screenshot = await app.evaluate(async ({ BrowserWindow }) =>
        (await BrowserWindow.getAllWindows()[0].webContents.capturePage())
          .toPNG()
          .toString("base64"),
      );
      fs.writeFileSync(
        path.resolve(
          root,
          `../../work/orbit-preview/mobile-swipe-${width}.png`,
        ),
        Buffer.from(screenshot, "base64"),
      );
      await page.mouse.up();
      await expect.poll(async () => (await state()).queue.index).toBe(2);
      await expect(viewport).toHaveAttribute("data-phase", "idle");
      await expect
        .poll(() =>
          dock.evaluate((el) =>
            getComputedStyle(el).getPropertyValue("--mobile-track-color"),
          ),
        )
        .not.toBe(colorBefore);
      // Short slow drag snaps back without changing queue position.
      await page.mouse.move(startX, y);
      await page.mouse.down();
      await page.mouse.move(startX + 15, y, { steps: 5 });
      await page.waitForTimeout(200);
      await page.mouse.up();
      await expect(viewport).toHaveAttribute("data-phase", "idle");
      expect((await state()).queue.index).toBe(2);
      // Go back twice, including the duplicate ID, by queue occurrence rather than song ID.
      for (const index of [1, 0]) {
        await page.mouse.move(startX - 60, y);
        await page.mouse.down();
        await page.mouse.move(startX + box.width * 0.2, y, { steps: 10 });
        await page.mouse.up();
        await expect.poll(async () => (await state()).queue.index).toBe(index);
        await expect(viewport).toHaveAttribute("data-phase", "idle");
      }
      expect((await state()).playback.track?.queueEntryId).toBe(
        first.queue.tracks[0].queueEntryId,
      );
      // At the first track, pulling right only rubber-bands.
      await page.mouse.move(startX - 60, y);
      await page.mouse.down();
      await page.mouse.move(startX + 70, y, { steps: 8 });
      await page.mouse.up();
      await expect(viewport).toHaveAttribute("data-phase", "idle");
      expect((await state()).queue.index).toBe(0);
      // Real touch events track the finger, and OS cancellation never changes tracks.
      const touch = await page.context().newCDPSession(page);
      try {
        await touch.send("Emulation.setTouchEmulationEnabled", {
          enabled: true,
          maxTouchPoints: 2,
        });
        await touch.send("Input.dispatchTouchEvent", {
          type: "touchStart",
          touchPoints: [{ x: startX, y }],
        });
        await touch.send("Input.dispatchTouchEvent", {
          type: "touchMove",
          touchPoints: [{ x: startX - 80, y }],
        });
        await expect(viewport).toHaveAttribute("data-phase", "dragging");
        await viewport.dispatchEvent("pointerup", {
          pointerId: 999,
          pointerType: "touch",
          isPrimary: false,
        });
        await expect(viewport).toHaveAttribute("data-phase", "dragging");
        await touch.send("Input.dispatchTouchEvent", {
          type: "touchCancel",
          touchPoints: [],
        });
        await expect(viewport).toHaveAttribute("data-phase", "idle");
        expect((await state()).queue.index).toBe(0);
      } finally {
        await touch.send("Emulation.setTouchEmulationEnabled", {
          enabled: false,
        });
        await touch.detach();
      }
      const like = card.getByRole("button", { name: /любим/ });
      const liked = !!(await state()).playback.track?.liked;
      await like.click();
      await expect(like).toHaveAttribute("aria-pressed", String(!liked));
      await expect
        .poll(async () => !!(await state()).playback.track?.liked)
        .toBe(!liked);
      await card.getByRole("button", { name: "Пауза", exact: true }).click();
      await expect
        .poll(async () => (await state()).playback.playing)
        .toBe(false);
      // All tab labels fit, and the active surface covers icon and text together.
      await page.getByRole("link", { name: "Настройки", exact: true }).click();
      const nav = page.locator(".lad-mobile-navigation");
      expect(
        await nav
          .locator(".orbit-tab__label")
          .evaluateAll((els) =>
            els.every((el) => el.scrollWidth <= el.clientWidth + 1),
          ),
      ).toBe(true);
      const alignment = await nav.evaluate((el) => {
        const active = el
            .querySelector('[data-active="true"]')!
            .getBoundingClientRect(),
          lens = el
            .querySelector(".orbit-tabbar__lens > span")!
            .getBoundingClientRect();
        return Math.abs(
          (active.top + active.bottom - lens.top - lens.bottom) / 2,
        );
      });
      expect(alignment).toBeLessThan(2);
      await expect
        .poll(() =>
          nav.evaluate((el) => {
            const active = el
              .querySelector('[data-active="true"]')!
              .getBoundingClientRect();
            const lens = el
              .querySelector(".orbit-tabbar__lens > span")!
              .getBoundingClientRect();
            return Math.abs(active.left - lens.left);
          }),
        )
        .toBeLessThan(2);

      await page.evaluate(() => document.fonts.ready);
      await page.waitForTimeout(250);
      const finalShot = await app.evaluate(async ({ BrowserWindow }) =>
        (await BrowserWindow.getAllWindows()[0].webContents.capturePage())
          .toPNG()
          .toString("base64"),
      );
      fs.writeFileSync(
        path.resolve(root, `../../work/orbit-preview/mobile-dock-${width}.png`),
        Buffer.from(finalShot, "base64"),
      );
      const bounds = await dock.boundingBox();
      const detail = await app.evaluate(async ({ BrowserWindow }, bounds) => {
        const win = BrowserWindow.getAllWindows()[0],
          zoom = win.webContents.getZoomFactor();
        const rect = {
          x: Math.round(bounds!.x * zoom),
          y: Math.round(bounds!.y * zoom),
          width: Math.round(bounds!.width * zoom),
          height: Math.round(bounds!.height * zoom),
        };
        return (await win.webContents.capturePage(rect))
          .toPNG()
          .toString("base64");
      }, bounds);
      fs.writeFileSync(
        path.resolve(
          root,
          `../../work/orbit-preview/mobile-capsules-${width}.png`,
        ),
        Buffer.from(detail, "base64"),
      );
    } finally {
      await app.close();
      fs.rmSync(profile, { recursive: true, force: true });
    }
  });
