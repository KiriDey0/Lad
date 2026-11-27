// SPDX-License-Identifier: AGPL-3.0-only
import { test, expect, _electron as electron } from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
const root = path.resolve(__dirname, "..");
for (const width of [1440, 1100, 800])
  test(`desktop player keeps track actions together and volume usable at ${width}px`, async () => {
    const profile = fs.mkdtempSync(
      path.join(os.tmpdir(), "lad-desktop-player-"),
    );
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
      await page.evaluate(() =>
        window.lad!.rpc("control", { action: "pause" }),
      );
      await page.getByRole("button", { name: "Для тебя", exact: true }).click();
      await expect(page.locator(".wave-home-controls")).toBeInViewport();
      const vertical = await page
        .locator(".wave-home")
        .evaluate((el) => ({
          bottom: el.getBoundingClientRect().bottom,
          player: document.querySelector(".player")!.getBoundingClientRect()
            .top,
        }));
      expect(vertical.bottom).toBeLessThanOrEqual(vertical.player - 8);
      const player = page.locator(".player"),
        volume = page.getByRole("slider", { name: "Громкость", exact: true }),
        badge = player.locator(".volume-value");
      await expect(volume).toBeVisible();
      const geometry = await player.evaluate((el) => {
        const meta = el
            .querySelector(".player-track-name")!
            .getBoundingClientRect(),
          like = el
            .querySelector(".player-track > .like-button")!
            .getBoundingClientRect(),
          group = el.querySelector(".player-track")!.getBoundingClientRect();
        const input = el.querySelector<HTMLInputElement>(
          ".volume-control input",
        )!;
        const zones = [".player-track", ".player-center", ".player-right"].map(
          (selector) => {
            const r = el.querySelector(selector)!.getBoundingClientRect();
            return { x: r.x, y: r.y, right: r.right, bottom: r.bottom };
          },
        );
        return {
          heartGap: like.left - meta.right,
          heartInside: like.right <= group.right + 1,
          volumeWidth: input.clientWidth,
          volumeHeight: input.clientHeight,
          zones,
          parent: el.getBoundingClientRect().right,
          last: el.lastElementChild!.getBoundingClientRect().right,
        };
      });
      expect(geometry.heartGap).toBeGreaterThanOrEqual(8);
      expect(geometry.heartGap).toBeLessThanOrEqual(16);
      expect(geometry.heartInside).toBe(true);
      expect(geometry.volumeWidth).toBe(105);
      expect(geometry.volumeHeight).toBeGreaterThanOrEqual(44);
      expect(geometry.last).toBeLessThanOrEqual(geometry.parent);
      for (let i = 0; i < geometry.zones.length; i++)
        for (let j = i + 1; j < geometry.zones.length; j++) {
          const a = geometry.zones[i],
            b = geometry.zones[j];
          expect(
            Math.max(0, Math.min(a.right, b.right) - Math.max(a.x, b.x)) *
              Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.y, b.y)),
          ).toBe(0);
        }
      await expect(player.locator(".player-track-name strong")).toHaveCSS(
        "font-size",
        "16px",
      );
      await expect(player.locator(".player-track-name small")).toHaveCSS(
        "font-size",
        "13px",
      );
      await expect(badge).toBeVisible();
      await expect(badge).toHaveCSS("position", "static");
      await expect(badge).toHaveCSS("opacity", "1");
      await volume.fill("0.63");
      await volume.press("ArrowRight");
      await expect(badge).toHaveText("64%");
      await expect
        .poll(
          async () =>
            (await page.evaluate(() => window.lad!.rpc("snapshot"))).playback
              .volume,
        )
        .toBe(0.64);
      await player
        .getByRole("button", { name: "Выключить звук", exact: true })
        .click();
      await expect(badge).toHaveText("0%");
      await player
        .getByRole("button", { name: "Включить звук", exact: true })
        .click();
      await expect(badge).toHaveText("64%");
      const slider = (await volume.boundingBox())!;
      // Click above the thin painted track, inside the expanded hit area.
      await page.mouse.click(slider.x + slider.width * 0.8, slider.y + 5);
      await expect
        .poll(
          async () =>
            (await page.evaluate(() => window.lad!.rpc("snapshot"))).playback
              .volume,
        )
        .toBeGreaterThan(0.75);
      const capture = async (name: string) => {
        await page.evaluate(() => document.fonts.ready);
        const bounds = (await player.boundingBox())!;
        const png = await app.evaluate(async ({ BrowserWindow }, bounds) => {
          const win = BrowserWindow.getAllWindows()[0],
            z = win.webContents.getZoomFactor();
          return (
            await win.webContents.capturePage({
              x: Math.round(bounds.x * z),
              y: Math.round(bounds.y * z),
              width: Math.round(bounds.width * z),
              height: Math.round(bounds.height * z),
            })
          )
            .toPNG()
            .toString("base64");
        }, bounds);
        fs.writeFileSync(
          path.resolve(root, `../../work/orbit-preview/${name}-${width}.png`),
          Buffer.from(png, "base64"),
        );
      };
      await capture("desktop-player");
      await page.evaluate(async () => {
        const state = await window.lad!.rpc("snapshot");
        await window.lad!.rpc("play", {
          tracks: [
            {
              ...state.queue.tracks[0],
              title: "ОченьДлинноеНазвание".repeat(12),
              artist: "ОченьДлинноеИмяИсполнителя".repeat(8),
              artists: [],
              liked: false,
            },
          ],
          index: 0,
          source: "Player QA",
        });
        await window.lad!.rpc("control", { action: "pause" });
      });
      await expect(player.locator(".player-track-name strong")).toContainText(
        "ОченьДлинноеНазвание",
      );
      const overflow = await player.evaluate((el) => {
        const r = el.getBoundingClientRect(),
          like = el
            .querySelector(".player-track > .like-button")!
            .getBoundingClientRect(),
          name = el
            .querySelector(".player-track-name")!
            .getBoundingClientRect();
        return {
          scroll: document.body.scrollWidth,
          width: innerWidth,
          inside: name.right <= like.left,
          actions:
            el.lastElementChild!.getBoundingClientRect().right <= r.right,
        };
      });
      expect(overflow.scroll).toBeLessThanOrEqual(overflow.width + 1);
      expect(overflow.inside).toBe(true);
      expect(overflow.actions).toBe(true);
      const heart = player.locator(".player-track > .like-button");
      await heart.click();
      await expect(heart).toHaveAttribute("aria-pressed", "true");
      await capture("desktop-player-long-title");
    } finally {
      await app.close();
      fs.rmSync(profile, { recursive: true, force: true });
    }
  });
