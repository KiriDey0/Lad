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
      const releaseArt = await page
        .locator(".release-album .art")
        .first()
        .evaluate((el) => ({
          width: parseFloat(getComputedStyle(el).width),
          height: parseFloat(getComputedStyle(el).height),
        }));
      expect(releaseArt.width).toBeCloseTo(48, 0);
      expect(releaseArt.height).toBeCloseTo(48, 0);
      expect(
        await page
          .locator(".release-artist .art")
          .first()
          .evaluate((el) => parseFloat(getComputedStyle(el).width)),
      ).toBeCloseTo(width < 761 ? 156 : 180, 0);
      await expect(page.locator(".release-album strong").first()).toBeVisible();
      await expect(page.locator(".track-number")).toHaveCount(0);
      await expect(page.locator(".main-header .header-location")).toHaveCSS(
        "font-size",
        width < 761 ? "22px" : "28px",
      );

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
      await expect(
        page.locator(".artist-popular-tracks .artist-track-row"),
      ).toHaveCount(5);
      await expect(page.locator(".artist-page .track-number")).toHaveCount(0);
      await expect(
        page.locator(".artist-popular-albums .music-card"),
      ).toHaveCount(9);
      await page
        .getByRole("button", { name: "Популярные треки", exact: true })
        .click();
      await expect(
        page.getByRole("heading", { name: "Треки · Лад Session", exact: true }),
      ).toBeVisible();
      await expect(
        page.locator(".artist-all-tracks .artist-track-row"),
      ).toHaveCount(50);
      await page
        .getByRole("button", { name: "Показать ещё треки", exact: true })
        .click();
      await expect(
        page.locator(".artist-all-tracks .artist-track-row"),
      ).toHaveCount(62);
      await page
        .getByRole("button", { name: "К исполнителю", exact: true })
        .click();
      await expect(
        page.locator(".artist-popular-tracks .artist-track-row"),
      ).toHaveCount(5);
      await capture("discovery-artist");
      if (width < 761) {
        const heading = page.getByRole("button", {
          name: "Популярные альбомы",
          exact: true,
        });
        await heading.hover();
        await expect
          .poll(() =>
            heading
              .locator("svg")
              .evaluate(
                (el) => new DOMMatrix(getComputedStyle(el).transform).m41,
              ),
          )
          .toBeGreaterThan(4);
        await page.emulateMedia({ reducedMotion: "reduce" });
        await expect
          .poll(() =>
            heading
              .locator("svg")
              .evaluate(
                (el) => new DOMMatrix(getComputedStyle(el).transform).m41,
              ),
          )
          .toBe(0);
        await page.emulateMedia({ reducedMotion: "no-preference" });
        const gallery = page.locator(".artist-album-carousel");
        await gallery.evaluate((el) => el.scrollIntoView({ block: "center" }));
        const box = (await gallery.boundingBox())!;
        const session = await page.context().newCDPSession(page);
        await session.send("Emulation.setTouchEmulationEnabled", {
          enabled: true,
          maxTouchPoints: 1,
        });
        const y = box.y + 40,
          start = box.x + box.width * 0.8;
        await session.send("Input.dispatchTouchEvent", {
          type: "touchStart",
          touchPoints: [{ x: start, y }],
        });
        for (let step = 1; step <= 6; step++) {
          await session.send("Input.dispatchTouchEvent", {
            type: "touchMove",
            touchPoints: [{ x: start - step * box.width * 0.08, y }],
          });
          await page.waitForTimeout(20);
        }
        await session.send("Input.dispatchTouchEvent", {
          type: "touchEnd",
          touchPoints: [],
        });
        await expect
          .poll(() => gallery.evaluate((el) => el.scrollLeft))
          .toBeGreaterThan(40);
      }
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
      const first = page.locator(".artist-page .artist-track-row").first();
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
    await expect(
      page.locator(".player-feedback-row > .like-button"),
    ).toHaveAttribute("aria-pressed", "false");
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
    const heart = page.locator(".player-feedback-row > .like-button");
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

test("artist overview has six albums in one horizontal row and preserves the gallery on return", async () => {
  const { app, page, profile } = await launch(1680);
  try {
    await page.getByRole("button", { name: "Для тебя", exact: true }).click();
    await page.locator(".release-artist").first().click();
    await expect(
      page.locator(".artist-popular-tracks .artist-track-row"),
    ).toHaveCount(5);
    const title = page.locator(".artist-popular-albums .artist-section-title");
    await expect(title).toHaveCSS("font-size", "28px");
    const gallery = page.locator(".artist-album-carousel");
    await gallery.scrollIntoViewIfNeeded();
    await expect(gallery.locator(".music-card")).toHaveCount(9);
    const boxes = await gallery.evaluate((el) => {
      const r = el.getBoundingClientRect();
      return {
        visible: Array.from(el.children).filter((c) => {
          const a = c.getBoundingClientRect();
          return a.left >= r.left - 1 && a.right <= r.right + 1;
        }).length,
        tops: Array.from(el.children).map((c) => c.getBoundingClientRect().top),
        width: el.clientWidth,
        scroll: el.scrollWidth,
      };
    });
    expect(boxes.visible).toBe(6);
    expect(Math.max(...boxes.tops) - Math.min(...boxes.tops)).toBeLessThan(1);
    expect(boxes.scroll).toBeGreaterThan(boxes.width);
    await gallery.hover();
    await page.mouse.wheel(400, 0);
    await expect
      .poll(() => gallery.evaluate((el) => el.scrollLeft))
      .toBeGreaterThan(100);
    await gallery.evaluate((el) => {
      el.style.scrollSnapType = "none";
      el.scrollLeft = 0;
    });
    await page.keyboard.down("Shift");
    await page.mouse.wheel(0, 350);
    await page.keyboard.up("Shift");
    await expect
      .poll(() => gallery.evaluate((el) => el.scrollLeft))
      .toBeGreaterThan(100);
    const saved = await gallery.evaluate((el) => el.scrollLeft);
    await page
      .getByRole("button", { name: "Популярные альбомы", exact: true })
      .click();
    await expect(page.locator(".artist-discography h1")).toBeVisible();
    await page
      .getByRole("button", { name: "К исполнителю", exact: true })
      .click();
    await expect
      .poll(() =>
        page.locator(".artist-album-carousel").evaluate((el) => el.scrollLeft),
      )
      .toBeGreaterThan(saved - 25);
  } finally {
    await app.close();
    fs.rmSync(profile, { recursive: true, force: true });
  }
});

test("collection uses large favorite artists in a gallery and a separated dislike tile", async () => {
  const { app, page, profile } = await launch(1440);
  try {
    const state = await page.evaluate(() => window.lad!.rpc("snapshot"));
    await app.evaluate(({ ipcMain }, state) => {
      ipcMain.removeHandler("orbit:rpc");
      ipcMain.handle("orbit:rpc", (_event, method) => {
        if (method === "library")
          return {
            playlists: [],
            albums: [],
            artists: Array.from({ length: 12 }, (_, i) => ({
              id: `artist-${i}`,
              type: "artist",
              title: `Любимый артист ${i}`,
              art: i % 6,
            })),
          };
        if (method === "localSummary")
          return { folders: [], total: 0, scanning: false };
        if (method === "localBrowse") return { cards: [], tracks: [] };
        if (["liked", "localLiked"].includes(method))
          return { tracks: [], total: 0 };
        if (method === "dislikes")
          return { tracks: [], total: 0, nextOffset: 0 };
        return state;
      });
    }, state);
    await page.getByRole("button", { name: "Коллекция", exact: true }).click();
    const gallery = page.locator(".collection-artist-grid.horizontal-gallery");
    await expect(gallery.locator(".music-card")).toHaveCount(12);
    await gallery.scrollIntoViewIfNeeded();
    const geometry = await gallery.evaluate((el) => {
      const cards = Array.from(el.children).map((c) =>
        c.getBoundingClientRect(),
      );
      return {
        tops: cards.map((c) => c.top),
        art: el.querySelector(".art")!.clientWidth,
      };
    });
    expect(
      Math.max(...geometry.tops) - Math.min(...geometry.tops),
    ).toBeLessThan(1);
    expect(geometry.art).toBeGreaterThan(150);
    await expect(
      page.getByRole("heading", { name: "Любимые исполнители", exact: true }),
    ).toHaveCSS("font-size", "28px");
    await gallery.hover();
    await page.mouse.wheel(500, 0);
    await expect
      .poll(() => gallery.evaluate((el) => el.scrollLeft))
      .toBeGreaterThan(100);
    await expect(page.locator(".dislikes-entry .collection-group")).toHaveCount(
      1,
    );
    const gap = await page
      .getByRole("heading", { name: "В коллекции", exact: true })
      .evaluate(
        (el) =>
          document
            .querySelector(".collection-group-grid")!
            .getBoundingClientRect().top - el.getBoundingClientRect().bottom,
      );
    expect(gap).toBeGreaterThanOrEqual(24);
    await page.locator(".dislikes-entry .collection-group").click();
    await expect(
      page.getByRole("heading", { name: "Мои дизлайки", exact: true }),
    ).toBeVisible();
  } finally {
    await app.close();
    fs.rmSync(profile, { recursive: true, force: true });
  }
});
