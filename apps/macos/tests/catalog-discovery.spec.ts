// SPDX-License-Identifier: AGPL-3.0-only
import { test, expect, _electron as electron } from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
const root = path.resolve(__dirname, "..");
async function launch(width = 1440) {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), "lad-discovery-"));
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
  await app.evaluate(({ BrowserWindow }, width) => {
    const w = BrowserWindow.getAllWindows()[0];
    w.setContentSize(width, 900);
    w.show();
    w.focus();
    w.webContents.setBackgroundThrottling(false);
  }, width);
  await page
    .getByRole("button", { name: "Посмотреть демо", exact: true })
    .click();
  await page.evaluate(() => window.lad!.rpc("control", { action: "pause" }));
  return { app, page, profile };
}
for (const width of [1440, 390])
  test(`personal releases, artist listening and follow, discography and undo dislike at ${width}px`, async () => {
    const { app, page, profile } = await launch(width);
    try {
      await page
        .getByRole(width < 761 ? "link" : "button", {
          name: "Для тебя",
          exact: true,
        })
        .click();
      await expect(page.locator(".new-releases .release-card")).toHaveCount(1);
      await page.locator(".new-releases").scrollIntoViewIfNeeded();
      const capture = async (name: string) => {
        await page.evaluate(() => document.fonts.ready);
        await page.waitForTimeout(250);
        const data = await app.evaluate(async ({ BrowserWindow }) =>
          Array.from(
            (
              await BrowserWindow.getAllWindows()[0].webContents.capturePage()
            ).toPNG(),
          ),
        );
        fs.writeFileSync(
          path.resolve(root, `../../work/orbit-preview/${name}-${width}.png`),
          Buffer.from(data),
        );
      };
      await capture("discovery-releases");
      await page
        .getByRole("button", { name: "Слушать Первый свет", exact: true })
        .click();
      await expect
        .poll(
          async () =>
            (await page.evaluate(() => window.lad!.rpc("snapshot"))).queue
              .source,
        )
        .toBe("Первый свет");
      await page.locator(".release-artist").click();
      await expect(
        page.getByRole("heading", { name: "Популярные треки", exact: false }),
      ).toBeVisible();
      const follow = page.locator(".artist-actions .like-button");
      await follow.click();
      await expect(follow).toHaveAttribute("aria-pressed", "true");
      expect(
        (
          await page.evaluate(() =>
            window.lad!.rpc("artist", { id: "demo-artist" }),
          )
        ).followed,
      ).toBe(true);
      await page
        .locator(".artist-actions")
        .getByRole("button", { name: "Слушать", exact: true })
        .click();
      await expect
        .poll(
          async () =>
            (await page.evaluate(() => window.lad!.rpc("snapshot"))).queue
              .source,
        )
        .toBe("Лад Session");
      await capture("discovery-artist");
      await page
        .getByRole("button", { name: "Популярные альбомы", exact: true })
        .click();
      await expect(
        page.getByRole("heading", { name: "Альбомы · Лад Session" }),
      ).toBeVisible();
      await expect(page.locator(".artist-discography .music-card")).toHaveCount(
        1,
      );
      await page
        .getByRole("button", { name: "К исполнителю", exact: true })
        .click();
      // The context menu also supplies dislike on mobile, without widening the thin mini-player.
      const first = page.locator(".artist-page .track-row").first();
      const id = (await page.evaluate(() => window.lad!.rpc("snapshot")))
        .playback.track.id;
      await first.click({ button: "right" });
      await page
        .getByRole("menuitem", { name: "Не нравится", exact: true })
        .click();
      await expect
        .poll(
          async () =>
            (await page.evaluate(() => window.lad!.rpc("snapshot"))).playback
              .track.id,
        )
        .not.toBe(id);
      const dislikes = await page.evaluate(() => window.lad!.rpc("dislikes"));
      expect(dislikes.total).toBe(1);
      expect(dislikes.tracks[0].liked).toBeFalsy();
      await page
        .getByRole(width < 761 ? "link" : "button", {
          name: "Коллекция",
          exact: true,
        })
        .click();
      await page.locator(".dislikes-entry button").click();
      await expect(page.locator(".disliked-track")).toHaveCount(1);
      await capture("discovery-dislikes");
      await page.getByRole("button", { name: /^Снять дизлайк:/ }).click();
      await expect(page.locator(".disliked-track")).toHaveCount(0);
      await expect(
        page.getByText("Дизлайков пока нет", { exact: true }),
      ).toBeVisible();
      expect(
        (await page.evaluate(() => window.lad!.rpc("dislikes"))).total,
      ).toBe(0);
    } finally {
      await app.close();
      fs.rmSync(profile, { recursive: true, force: true });
    }
  });
test("like has plain states and deliberate heartbeat / unlike shake, reduced motion stays quiet", async () => {
  const { app, page, profile } = await launch();
  try {
    await page.evaluate(async () => {
      const s = await window.lad!.rpc("snapshot");
      await window.lad!.rpc("like", { id: s.playback.track.id, liked: false });
    });
    await expect(page.locator(".player-track > .like-button")).toHaveAttribute(
      "aria-pressed",
      "false",
    );
    await page.evaluate(() => {
      const original = Element.prototype.animate;
      (window as any).heartAnimations = [];
      Element.prototype.animate = function (
        ...args: Parameters<typeof original>
      ) {
        if (this.closest(".like-button"))
          (window as any).heartAnimations.push(args[0]);
        return original.apply(this, args);
      };
    });
    const heart = page.locator(".player-track > .like-button");
    expect(await page.evaluate(() => (window as any).heartAnimations)).toEqual(
      [],
    );
    await heart.click();
    await expect(heart).toHaveAttribute("aria-pressed", "true");
    await page.mouse.move(0, 0);
    await expect(heart).toHaveCSS("background-color", "rgba(0, 0, 0, 0)");
    expect(
      JSON.stringify(
        await page.evaluate(() => (window as any).heartAnimations),
      ),
    ).toContain("scale(1.2)");
    expect(await heart.locator("svg").getAttribute("fill")).toBe(
      "currentColor",
    );
    await heart.click();
    await expect(heart).toHaveAttribute("aria-pressed", "false");
    expect(
      JSON.stringify(
        await page.evaluate(() => (window as any).heartAnimations),
      ),
    ).toContain("translateX(-3px)");
    await page.emulateMedia({ reducedMotion: "reduce" });
    const count = await page.evaluate(
      () => (window as any).heartAnimations.length,
    );
    await heart.click();
    await expect(heart).toHaveAttribute("aria-pressed", "true");
    expect(
      await page.evaluate(() => (window as any).heartAnimations.length),
    ).toBe(count);
  } finally {
    await app.close();
    fs.rmSync(profile, { recursive: true, force: true });
  }
});

test("dislike pagination keeps server offsets after unresolved tracks and removing a mark through its menu", async () => {
  const { app, page, profile } = await launch();
  try {
    const state = await page.evaluate(() => window.lad!.rpc("snapshot"));
    await app.evaluate(({ ipcMain }, state) => {
      const template = state.playback.track;
      const ids = Array.from({ length: 103 }, (_, i) => String(8000 + i));
      const calls: number[] = [];
      ipcMain.removeHandler("orbit:rpc");
      ipcMain.handle("orbit:rpc", (_event, method, p) => {
        if (method === "dislikes") {
          calls.push(p.offset);
          const slice = ids.slice(p.offset, p.offset + 100);
          return {
            tracks: slice
              .filter((id) => id !== "8001")
              .map((id) => ({
                ...template,
                id,
                title: `Blocked ${id}`,
                disliked: true,
                liked: false,
              })),
            total: ids.length,
            nextOffset: Math.min(ids.length, p.offset + 100),
          };
        }
        if (method === "dislike") {
          const i = ids.indexOf(p.id);
          if (i >= 0) ids.splice(i, 1);
          return true;
        }
        if (method === "probeOffsets") return calls;
        if (method === "library")
          return { playlists: [], albums: [], artists: [] };
        if (method === "localSummary")
          return { folders: [], total: 0, scanning: false };
        if (method === "localLiked" || method === "liked")
          return { tracks: [], total: 0 };
        if (method === "localBrowse") return { cards: [], tracks: [] };
        if (method === "home") return { cards: [], tracks: [] };
        return {};
      });
    }, state);
    await page.getByRole("button", { name: "Коллекция", exact: true }).click();
    await page.locator(".dislikes-entry button").click();
    await expect(page.locator(".disliked-track")).toHaveCount(99);
    await page.locator(".disliked-track").first().click({ button: "right" });
    await page
      .getByRole("menuitem", { name: "Снять дизлайк", exact: true })
      .click();
    await expect(page.locator(".disliked-track")).toHaveCount(98);
    await page
      .getByRole("button", { name: "Показать ещё", exact: true })
      .click();
    await expect(page.locator(".disliked-track")).toHaveCount(101);
    expect(await page.evaluate(() => window.lad!.rpc("probeOffsets"))).toEqual([
      0, 99,
    ]);
    await expect(page.locator(".disliked-track").last()).toContainText(
      "Blocked 8102",
    );
    await expect(
      page.getByRole("button", { name: "Показать ещё", exact: true }),
    ).toHaveCount(0);
  } finally {
    await app.close();
    fs.rmSync(profile, { recursive: true, force: true });
  }
});
