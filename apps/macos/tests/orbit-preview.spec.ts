import {
  test,
  expect,
  _electron as electron,
  type Page,
} from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
const root = path.resolve(__dirname, "..");
const shots = path.resolve(root, "../../work/orbit-preview");
fs.mkdirSync(shots, { recursive: true });
async function unboxed(page: Page, selector: string) {
  const elements = page.locator(selector);
  expect(await elements.count()).toBeGreaterThan(0);
  for (const element of await elements.all()) {
    const style = await element.evaluate((el) => {
      const css = getComputedStyle(el);
      return [
        css.backgroundColor,
        css.backgroundImage,
        css.borderLeftWidth,
        css.borderRadius,
      ];
    });
    expect(style).toEqual(["rgba(0, 0, 0, 0)", "none", "0px", "0px"]);
  }
}
async function noOverflow(page: Page) {
  const sizes = await page.evaluate(() => ({
    width: innerWidth,
    body: document.body.scrollWidth,
    root: document.querySelector(".app-shell")!.scrollWidth,
    main: document.querySelector(".page-content")!.scrollWidth,
    mainWidth: document.querySelector(".page-content")!.clientWidth,
    overflow: Array.from(document.querySelectorAll(".page-content *"))
      .filter(
        (e) =>
          e.getBoundingClientRect().right > innerWidth + 1 &&
          e.getBoundingClientRect().width > 0,
      )
      .slice(0, 12)
      .map((e) => ({
        tag: e.tagName,
        c: e.className,
        w: e.getBoundingClientRect().width,
        r: e.getBoundingClientRect().right,
      })),
  }));
  expect(sizes.body, JSON.stringify(sizes)).toBeLessThanOrEqual(
    sizes.width + 1,
  );
  expect(sizes.root, JSON.stringify(sizes)).toBeLessThanOrEqual(
    sizes.width + 1,
  );
  expect(sizes.main, JSON.stringify(sizes)).toBeLessThanOrEqual(
    sizes.mainWidth + 1,
  );
}
for (const width of [1440, 320])
  test(`Orbit desktop application works at ${width}px`, async () => {
    test.setTimeout(90000);
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), "lad-orbit-qa-"));
    const app = await electron.launch({
      executablePath: process.env.LAD_TEST_EXE || undefined,
      args: process.env.LAD_TEST_EXE ? [] : [root],
      env: {
        ...process.env,
        LAD_USER_DATA: profile,
        LAD_PYTHON: process.env.LAD_PYTHON,
      },
      timeout: 60000,
    });
    const errors: string[] = [];
    try {
      const page = await app.firstWindow();
      page.on("pageerror", (e) => errors.push(e.message));
      await app.evaluate(
        ({ BrowserWindow }, width) =>
          BrowserWindow.getAllWindows()[0].setContentSize(width, 900),
        width,
      );
      await page.waitForLoadState("domcontentloaded");
      await page.evaluate(() => window.lad!.rpc("snapshot"));
      await expect(
        page.getByRole("button", { name: "Посмотреть демо", exact: true }),
      ).toBeVisible();
      await noOverflow(page);
      await page
        .getByRole("button", { name: "Посмотреть демо", exact: true })
        .click();
      await expect(
        page.getByRole("heading", { name: "Первый свет", exact: true }),
      ).toBeVisible();
      await expect(page.locator(".lyric-active")).toHaveCSS(
        "font-weight",
        "700",
      );
      await expect(
        page.locator(".lyric-line:not(.lyric-active)").first(),
      ).toHaveCSS("font-weight", "500");
      await page.screenshot({ path: path.join(shots, `lyrics-${width}.png`) });
      const navigate = async (label: string) => {
        if (width < 760)
          await page.getByRole("link", { name: label, exact: true }).click();
        else
          await page.getByRole("button", { name: label, exact: true }).click();
      };
      await navigate("Для тебя");
      await expect(page.locator(".wave-home")).toBeVisible();
      await unboxed(page, ".wave-home");
      expect(
        await page
          .locator(".wave-home")
          .evaluate((el) => getComputedStyle(el, "::before").content),
      ).toBe("none");
      if (width >= 760) {
        await expect(page.locator(".device-selector")).toHaveCSS(
          "column-gap",
          "6px",
        );
        await expect(page.locator(".device-selector")).toHaveCSS(
          "padding-right",
          "10px",
        );
      }
      await noOverflow(page);
      if (width < 760) {
        await expect
          .poll(async () =>
            page.locator(".lad-mobile-navigation").evaluate((el) => {
              const capsule = el
                .querySelector(".orbit-tabbar__lens > span")!
                .getBoundingClientRect();
              const active = el
                .querySelector('.orbit-tab[data-active="true"]')!
                .getBoundingClientRect();
              return (
                Math.abs(
                  capsule.top + capsule.bottom - (active.top + active.bottom),
                ) / 2
              );
            }),
          )
          .toBeLessThanOrEqual(2);
      }
      await page.screenshot({ path: path.join(shots, `wave-${width}.png`) });
      const beforeScroll = await page
        .locator(".wave-backdrop canvas")
        .boundingBox();
      await page.locator(".page-content").evaluate((el) => {
        const cover = el
          .querySelector(".wave-now-playing > .art")!
          .getBoundingClientRect();
        el.scrollTop += cover.top - el.getBoundingClientRect().top + 60;
      });
      await expect
        .poll(async () =>
          page.locator(".wave-home").evaluate((hero) => {
            const clip = hero.closest(".page-content")!.getBoundingClientRect();
            const cover = hero.querySelector(".wave-now-playing > .art")!;
            const rect = cover.getBoundingClientRect();
            const x = rect.left + rect.width / 2;
            return {
              clippedAbove: !cover.contains(
                document.elementFromPoint(x, clip.top - 4),
              ),
              visibleBelow: cover.contains(
                document.elementFromPoint(x, clip.top + 20),
              ),
              spansBoundary: rect.top < clip.top && rect.bottom > clip.top + 20,
            };
          }),
        )
        .toEqual({
          clippedAbove: true,
          visibleBelow: true,
          spansBoundary: true,
        });
      await expect
        .poll(
          async () =>
            (await page.locator(".wave-backdrop canvas").boundingBox())!.y,
        )
        .toBeLessThan(beforeScroll!.y - 50);
      expect(
        await page
          .locator(".wave-backdrop canvas")
          .evaluate((el) => el.closest(".page-content") === null),
      ).toBe(true);
      await page.screenshot({
        path: path.join(shots, `wave-scroll-${width}.png`),
      });
      await page.locator(".page-content").evaluate((el) => (el.scrollTop = 0));

      const playButton =
        width < 760
          ? page.locator('.mobile-track-card[data-current="true"] .mobile-play')
          : page.locator(".player .main-play");
      await playButton.click();
      await expect(playButton).toHaveAttribute("aria-label", "Пауза");
      if (width >= 760)
        await expect(page.locator(".orbit-scrubber")).toHaveAttribute(
          "data-playing",
          "true",
        );
      await playButton.click();
      if (width >= 760) {
        const position = page.getByRole("slider", {
          name: "Позиция трека",
          exact: true,
        });
        await position.focus();
        await page.keyboard.press("Home");
        await page.keyboard.press("ArrowRight");
        await expect
          .poll(async () =>
            Math.round(
              (await page.evaluate(() => window.lad!.rpc("snapshot"))).playback
                .position,
            ),
          )
          .toBe(5);
      } else
        await expect(page.locator(".mobile-track-fill").nth(1)).toBeVisible();
      await navigate("Поиск");
      await page
        .getByRole("searchbox", { name: "Поиск музыки", exact: true })
        .fill("Тихий");
      await expect(page.locator(".track-row").first()).toBeVisible();
      await noOverflow(page);
      await page.screenshot({ path: path.join(shots, `search-${width}.png`) });
      await navigate("Коллекция");
      await expect(
        page.getByRole("heading", { name: "Коллекция", exact: true }),
      ).toBeVisible();
      const favorites = page.locator(".liked-preview");
      await expect(favorites.locator(".track-play").first()).toBeVisible();
      await expect(favorites.locator(".track-number")).toHaveCount(0);
      await expect(favorites.locator(".track-play-icon").first()).toBeVisible();
      const favoriteTitle = await favorites
        .locator(".track-name strong")
        .first()
        .innerText();
      await favorites.locator(".track-play").first().click();
      await expect
        .poll(
          async () =>
            (await page.evaluate(() => window.lad!.rpc("snapshot"))).playback
              .track?.title,
        )
        .toBe(favoriteTitle);
      await noOverflow(page);
      await page.screenshot({
        path: path.join(shots, `collection-${width}.png`),
      });
      await navigate("Настройки");
      await expect(
        page.getByRole("heading", { name: "Настройки", exact: true }),
      ).toBeVisible();
      await noOverflow(page);
      await unboxed(page, ".settings-section");
      await page
        .getByRole("switch", { name: "Не воспроизводить 18+", exact: true })
        .locator("..")
        .click();
      await expect
        .poll(
          async () =>
            (await page.evaluate(() => window.lad!.rpc("snapshot")))
              .blockExplicit,
        )
        .toBe(true);
      await page
        .getByRole("switch", { name: "Не воспроизводить 18+", exact: true })
        .locator("..")
        .click();
      const palette = () =>
        page.evaluate(() => ({
          background: getComputedStyle(document.querySelector(".app-shell")!)
            .backgroundColor,
          body: getComputedStyle(document.body).backgroundColor,
          provider: getComputedStyle(document.querySelector(".lad-orbit")!)
            .backgroundColor,
          surface: getComputedStyle(
            document.querySelector(".lad-orbit")!,
          ).getPropertyValue("--orbit-surface"),
          accent:
            document.documentElement.style.getPropertyValue("--orbit-accent"),
        }));
      const dark = page.getByRole("radio", { name: "Dark", exact: true });
      const oled = page.getByRole("radio", { name: "OLED", exact: true });
      const coloring = page.getByRole("combobox", {
        name: "Окраска интерфейса",
        exact: true,
      });
      const originalDark = await palette();
      for (const mode of ["default", "accent", "tinted"]) {
        await coloring.selectOption(mode);
        await dark.click();
        const graphite = await palette();
        expect(graphite.background).not.toBe("rgb(0, 0, 0)");
        expect(graphite.body).toBe(graphite.background);
        expect(graphite.provider).toBe(graphite.background);
        if (mode === "default")
          await page.screenshot({
            path: path.join(shots, `dark-${width}.png`),
          });
        await oled.click();
        await expect
          .poll(async () => (await palette()).background)
          .toBe("rgb(0, 0, 0)");
        const black = await palette();
        expect(black.body).toBe("rgb(0, 0, 0)");
        expect(black.provider).toBe("rgb(0, 0, 0)");
        expect(black.surface).not.toBe(graphite.surface);
        if (mode === "default")
          await page.screenshot({
            path: path.join(shots, `oled-${width}.png`),
          });
        await dark.click();
        await expect.poll(palette).toEqual(graphite);
      }
      await coloring.selectOption("tinted");
      const colorTrigger = page.getByRole("button", {
        name: "Выбрать цвет акцента",
        exact: true,
      });
      await colorTrigger.click();
      const picker = page.getByRole("dialog", {
        name: "Цвет акцента",
        exact: true,
      });
      await expect(picker).toBeVisible();
      await expect(page.locator('input[type="color"]')).toHaveCount(0);
      const pickerBox = (await picker.boundingBox())!;
      expect(pickerBox.x).toBeGreaterThanOrEqual(0);
      expect(pickerBox.x + pickerBox.width).toBeLessThanOrEqual(
        (await page.evaluate(() => innerWidth)) + 1,
      );
      expect(pickerBox.y).toBeGreaterThanOrEqual(0);
      const seed = () =>
        page.evaluate(
          () => JSON.parse(localStorage.getItem("orbit-theme")!).seed,
        );
      const initialSeed = await seed();
      await picker
        .getByRole("textbox", { name: "HEX", exact: true })
        .fill("#12");
      await picker.getByRole("textbox", { name: "R", exact: true }).focus();
      await expect(
        picker.getByText("Нужны # и 6 символов: 0–9, A–F", { exact: true }),
      ).toBeVisible();
      expect(await seed()).toBe(initialSeed);
      await picker
        .getByRole("textbox", { name: "HEX", exact: true })
        .fill("#112233");
      await expect.poll(seed).toBe("#112233");
      await expect(
        picker.getByRole("textbox", { name: "R", exact: true }),
      ).toHaveValue("17");
      await picker.getByRole("textbox", { name: "R", exact: true }).fill("256");
      await picker.getByRole("textbox", { name: "G", exact: true }).focus();
      await expect(picker.getByRole("alert")).toHaveText(
        "Значения RGB — от 0 до 255",
      );
      expect(await seed()).toBe("#112233");
      await picker.getByRole("textbox", { name: "R", exact: true }).fill("191");
      await expect.poll(seed).toBe("#BF2233");
      const hue = picker.getByRole("slider", {
        name: "Цветовой тон",
        exact: true,
      });
      await hue.focus();
      await page.keyboard.press("Home");
      await expect(hue).toHaveValue("0");
      await picker.getByRole("button", { name: "Шалфей", exact: true }).click();
      await expect.poll(seed).toBe("#A8C8B3");
      await picker.locator(".lad-color-plane").scrollIntoViewIfNeeded();
      const plane = (await picker.locator(".lad-color-plane").boundingBox())!;
      await page.mouse.click(
        plane.x + plane.width * 0.5,
        plane.y + plane.height * 0.5,
      );
      expect(await seed()).not.toBe("#A8C8B3");
      await picker
        .getByRole("button", { name: "Лавандовый", exact: true })
        .click();
      await expect.poll(seed).toBe("#BFCAF5");
      await expect
        .poll(async () =>
          page
            .locator(".lad-orbit")
            .evaluate((el) =>
              getComputedStyle(el).getPropertyValue("--orbit-accent"),
            ),
        )
        .toBe(
          await page.evaluate(() =>
            document.documentElement.style.getPropertyValue("--orbit-accent"),
          ),
        );
      await picker.evaluate((el) => (el.scrollTop = 0));
      const hexField = picker.getByRole("textbox", {
        name: "HEX",
        exact: true,
      });
      expect(
        await hexField.evaluate((el) => getComputedStyle(el).paddingLeft),
      ).toBe("0px");
      await page.screenshot({
        path: path.join(shots, `accent-picker-${width}.png`),
      });
      await page.keyboard.press("Escape");
      await expect(picker).toHaveCount(0);
      await expect(colorTrigger).toBeFocused();
      await coloring.selectOption("default");
      await expect.poll(palette).toEqual(originalDark);
      const glass = page.getByRole("switch", {
        name: "Прозрачность",
        exact: true,
      });
      await glass.locator("..").click();
      await expect(page.locator("html")).toHaveAttribute(
        "data-transparency",
        "false",
      );
      expect(
        await page
          .locator(".player")
          .evaluate((el) => getComputedStyle(el).backdropFilter),
      ).toBe("none");
      await glass.locator("..").click();
      await expect(page.locator("html")).toHaveAttribute(
        "data-transparency",
        "true",
      );
      expect(
        await page
          .locator(".player")
          .evaluate((el) => getComputedStyle(el).backdropFilter),
      ).toContain("blur");
      await page.screenshot({
        path: path.join(shots, `settings-${width}.png`),
      });
      await page
        .locator(".setting-row")
        .filter({ has: page.getByText("Профиль звука", { exact: true }) })
        .getByRole("button", { name: "Настроить", exact: true })
        .click();
      await expect(
        page.getByRole("heading", { name: "Профиль звука", exact: true }),
      ).toBeVisible();
      await noOverflow(page);
      await unboxed(page, ".audio-hero-card, .audio-settings-section");
      await page.screenshot({ path: path.join(shots, `audio-${width}.png`) });
      await page.getByRole("tab", { name: "Эквалайзер", exact: true }).click();
      await noOverflow(page);
      await expect(
        page.getByRole("tabpanel", { name: "Эквалайзер", exact: true }),
      ).toBeVisible();
      await unboxed(page, ".equalizer-card, .audio-settings-section");
      const gain = page.getByRole("spinbutton", {
        name: "31.5 Hz значение",
        exact: true,
      });
      await gain.fill("-1.5");
      await gain.blur();
      await expect(gain).toHaveValue("-1.5");
      await expect
        .poll(async () =>
          page.evaluate(
            () =>
              JSON.parse(localStorage.getItem("lad-audio-settings") || "{}")
                .gains?.[0],
          ),
        )
        .toBe(-1.5);
      await page.screenshot({
        path: path.join(shots, `equalizer-${width}.png`),
      });
      await navigate("Для тебя");
      await page.locator(".device-selector").click();
      const dialog = page.getByRole("dialog", {
        name: "Где будем слушать?",
        exact: true,
      });
      await expect(dialog).toBeVisible();
      const box = (await dialog.boundingBox())!;
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(
        (await page.evaluate(() => innerWidth)) + 1,
      );
      const insets = await dialog.evaluate((el) => {
        const frame = el.getBoundingClientRect();
        return Array.from(
          el.querySelectorAll(".device-option,.device-group-title"),
        ).map((item) => {
          const r = item.getBoundingClientRect();
          return { left: r.left - frame.left, right: frame.right - r.right };
        });
      });
      for (const inset of insets) {
        expect(inset.left).toBeGreaterThanOrEqual(width < 760 ? 16 : 24);
        expect(inset.right).toBeGreaterThanOrEqual(width < 760 ? 16 : 24);
      }
      await expect(
        dialog.getByRole("button", { name: "Слушать на Mac", exact: true }),
      ).toBeEnabled();
      await page.screenshot({ path: path.join(shots, `devices-${width}.png`) });
      await page.keyboard.press("Escape");
      await expect(dialog).toHaveCount(0);
      await expect(page.locator(".device-selector")).toBeFocused();
      if (width < 760) await page.locator(".mobile-queue-button").click();
      else if (!(await page.locator(".queue-panel").count()))
        await page
          .locator(".player-right")
          .getByRole("button", { name: "Показать очередь", exact: true })
          .click();
      await expect(page.locator(".queue-row")).toHaveCount(6);
      await noOverflow(page);
      await page.screenshot({ path: path.join(shots, `queue-${width}.png`) });
      await page.locator(".queue-row").last().click({ button: "right" });
      await page
        .getByRole("menuitem", { name: "Удалить из очереди", exact: true })
        .click();
      await expect(page.locator(".queue-row")).toHaveCount(5);
      await page
        .getByRole("button", { name: "Скрыть очередь", exact: true })
        .click();
      expect(errors).toEqual([]);
    } finally {
      await app.close();
      fs.rmSync(profile, { recursive: true, force: true });
    }
  });

for (const width of [1440, 320])
  test(`Orbit controls contain their icons and labels at every scale (${width}px)`, async () => {
    test.setTimeout(90000);
    const profile = fs.mkdtempSync(
      path.join(os.tmpdir(), "lad-orbit-controls-"),
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
      await app.evaluate(
        ({ BrowserWindow }, width) =>
          BrowserWindow.getAllWindows()[0].setContentSize(width, 900),
        width,
      );
      await page.evaluate(() => window.lad!.rpc("snapshot"));
      await page
        .getByRole("button", { name: "Посмотреть демо", exact: true })
        .click();
      if (width < 760)
        await page
          .getByRole("link", { name: "Настройки", exact: true })
          .click();
      else
        await page
          .getByRole("button", { name: "Настройки", exact: true })
          .click();
      const scale = page.getByRole("combobox", {
        name: "Размер интерфейса",
        exact: true,
      });
      for (const percent of [85, 100, 115]) {
        await scale.selectOption(String(percent));
        await expect
          .poll(() =>
            app.evaluate(({ BrowserWindow }) =>
              BrowserWindow.getAllWindows()[0].webContents.getZoomFactor(),
            ),
          )
          .toBe((percent / 100) * 0.85);
        for (const group of ["Основа", "Режим волны"]) {
          const options = page
            .getByRole("radiogroup", { name: group, exact: true })
            .getByRole("radio");
          for (let i = 0; i < (await options.count()); i++) {
            await options.nth(i).click();
            await expect(options.nth(i)).toHaveAttribute(
              "aria-checked",
              "true",
            );
            const overflow = await options.nth(i).evaluate((button) => {
              const bounds = button.getBoundingClientRect();
              const inside = (r: DOMRect) =>
                r.left >= bounds.left + 3 &&
                r.right <= bounds.right - 3 &&
                r.top >= bounds.top &&
                r.bottom <= bounds.bottom;
              const failures: string[] = [];
              for (const svg of button.querySelectorAll("svg"))
                if (!inside(svg.getBoundingClientRect())) failures.push("icon");
              for (const child of button.childNodes)
                if (
                  child.nodeType === Node.TEXT_NODE &&
                  child.textContent?.trim()
                ) {
                  const range = document.createRange();
                  range.selectNodeContents(child);
                  if (!inside(range.getBoundingClientRect()))
                    failures.push("label");
                }
              return failures;
            });
            expect(overflow, `${group} at ${percent}% / ${width}px`).toEqual(
              [],
            );
          }
        }
        await noOverflow(page);
        const escapedIcons = await page
          .locator(".settings-page")
          .evaluate((root) => {
            const failures: string[] = [];
            for (const button of root.querySelectorAll(
              "button,.orbit-choice",
            )) {
              const rect = button.getBoundingClientRect();
              for (const icon of button.querySelectorAll("svg")) {
                const bounds = icon.getBoundingClientRect();
                if (
                  bounds.width > 0 &&
                  (bounds.left < rect.left - 1 ||
                    bounds.right > rect.right + 1 ||
                    bounds.top < rect.top - 1 ||
                    bounds.bottom > rect.bottom + 1)
                )
                  failures.push(
                    button.textContent?.trim() ||
                      button.getAttribute("aria-label") ||
                      "switch",
                  );
              }
            }
            return failures;
          });
        expect(
          escapedIcons,
          `settings icons at ${percent}% / ${width}px`,
        ).toEqual([]);
        // Capture after the 150 ms control transition reaches the compositor.
        await page.waitForTimeout(200);
        const capture = await app.evaluate(async ({ BrowserWindow }) =>
          (await BrowserWindow.getAllWindows()[0].webContents.capturePage())
            .toPNG()
            .toString("base64"),
        );
        fs.writeFileSync(
          path.join(shots, `controls-${width}-${percent}.png`),
          Buffer.from(capture, "base64"),
        );
      }
      await scale.selectOption("100");
      await page
        .locator(".setting-row")
        .filter({ has: page.getByText("Профиль звука", { exact: true }) })
        .getByRole("button", { name: "Настроить", exact: true })
        .click();
      await page.getByRole("tab", { name: "Эквалайзер", exact: true }).click();
      const gain = page.getByRole("spinbutton", {
        name: "31.5 Hz значение",
        exact: true,
      });
      await gain.fill("-12");
      const geometry = await gain.evaluate((el) => ({
        padding: getComputedStyle(el).paddingLeft,
        width: el.clientWidth,
        scroll: el.scrollWidth,
      }));
      expect(geometry.padding).toBe("3px");
      expect(geometry.scroll).toBeLessThanOrEqual(geometry.width);
    } finally {
      await app.close();
      fs.rmSync(profile, { recursive: true, force: true });
    }
  });

for (const width of [1440, 320])
  test(`settings cards stay separate and long device lists scroll in a compact dialog (${width}px)`, async () => {
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), "lad-orbit-dialog-"));
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
      await app.evaluate(
        ({ BrowserWindow }, width) =>
          BrowserWindow.getAllWindows()[0].setContentSize(width, 900),
        width,
      );
      await page.evaluate(() => window.lad!.rpc("snapshot"));
      await page
        .getByRole("button", { name: "Посмотреть демо", exact: true })
        .click();
      if (width < 760)
        await page
          .getByRole("link", { name: "Настройки", exact: true })
          .click();
      else
        await page
          .getByRole("button", { name: "Настройки", exact: true })
          .click();
      const gaps = await page.locator(".settings-page").evaluate((el) =>
        Array.from(el.querySelectorAll(":scope > .settings-section"))
          .slice(1)
          .map((card) => {
            const previous = card.previousElementSibling!;
            return (
              card.getBoundingClientRect().top -
              previous.getBoundingClientRect().bottom
            );
          }),
      );
      for (const gap of gaps) expect(gap).toBeGreaterThanOrEqual(15);
      await page
        .locator(".page-content")
        .evaluate((el) => (el.scrollTop = el.scrollHeight));
      await page.screenshot({
        path: path.join(shots, `settings-bottom-${width}.png`),
      });
      const state = await page.evaluate(() => window.lad!.rpc("snapshot"));
      state.devices = Array.from({ length: 9 }, (_, i) => ({
        id: "qa-device-" + i,
        name: i === 0 ? "Яндекс Станция Миди" : "Устройство с Алисой " + i,
        room: i % 2 ? "Гостиная" : "Кухня",
        household: "Мой дом",
        platform: "yandexmidi",
        metadataStatus: "ready",
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
      const dialog = page.getByRole("dialog", {
        name: "Где будем слушать?",
        exact: true,
      });
      await expect(dialog.locator(".device-option")).toHaveCount(10);
      const height = await dialog.evaluate((el) => ({
        height: el.getBoundingClientRect().height,
        viewport: innerHeight,
      }));
      expect(height.height).toBeLessThanOrEqual(
        ((height.viewport - 48) * 2) / 3 + 2,
      );
      const list = dialog.locator(".modal-scroll");
      expect(
        await list.evaluate((el) => el.scrollHeight > el.clientHeight + 100),
      ).toBe(true);
      const footer = (await dialog.locator(".modal-footer").boundingBox())!;
      await list.evaluate((el) => (el.scrollTop = el.scrollHeight));
      await expect(dialog.locator(".device-option").last()).toBeInViewport();
      const moved = (await dialog.locator(".modal-footer").boundingBox())!;
      expect(moved.y).toBeCloseTo(footer.y, 0);
      await expect(
        dialog.getByRole("button", { name: "Слушать на Mac", exact: true }),
      ).toBeInViewport();
      await page.screenshot({
        path: path.join(shots, `devices-compact-${width}.png`),
      });
      await page.keyboard.press("Escape");
      await expect(dialog).toHaveCount(0);
    } finally {
      await app.close();
      fs.rmSync(profile, { recursive: true, force: true });
    }
  });

test("old 85 percent becomes 100 without changing zoom; migration survives reload", async () => {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), "lad-orbit-scale-"));
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
    await page.evaluate(() => window.lad!.rpc("snapshot"));
    for (const oldScale of [75, 85, 100, 115]) {
      await page.evaluate((oldScale) => {
        const theme = JSON.parse(localStorage.getItem("orbit-theme") || "{}");
        delete theme.scaleBasis;
        theme.scale = oldScale;
        localStorage.setItem("orbit-theme", JSON.stringify(theme));
      }, oldScale);
      await page.reload();
      await page.evaluate(() => window.lad!.rpc("snapshot"));
      await expect
        .poll(() =>
          app.evaluate(({ BrowserWindow }) =>
            BrowserWindow.getAllWindows()[0].webContents.getZoomFactor(),
          ),
        )
        .toBeCloseTo(oldScale / 100, 8);
      await expect
        .poll(() =>
          page.evaluate(
            () => JSON.parse(localStorage.getItem("orbit-theme")!).scaleBasis,
          ),
        )
        .toBe(85);
      expect(
        await page.evaluate(
          () => JSON.parse(localStorage.getItem("orbit-theme")!).scale,
        ),
      ).toBeCloseTo(oldScale / 0.85, 8);
      await page.reload();
      await page.evaluate(() => window.lad!.rpc("snapshot"));
      await expect
        .poll(() =>
          app.evaluate(({ BrowserWindow }) =>
            BrowserWindow.getAllWindows()[0].webContents.getZoomFactor(),
          ),
        )
        .toBeCloseTo(oldScale / 100, 8);
    }
    await page.evaluate(() => {
      const theme = JSON.parse(localStorage.getItem("orbit-theme")!);
      theme.scale = 85;
      delete theme.scaleBasis;
      localStorage.setItem("orbit-theme", JSON.stringify(theme));
    });
    await page.reload();
    await page.evaluate(() => window.lad!.rpc("snapshot"));
    await page
      .getByRole("button", { name: "Посмотреть демо", exact: true })
      .click();
    await page.getByRole("button", { name: "Настройки", exact: true }).click();
    await expect(
      page.getByRole("combobox", { name: "Размер интерфейса", exact: true }),
    ).toHaveValue("100");
  } finally {
    await app.close();
    fs.rmSync(profile, { recursive: true, force: true });
  }
});

for (const size of [
  { width: 1440, height: 800 },
  { width: 1100, height: 720 },
  { width: 320, height: 800 },
])
  test(`wave card fits above dock and Orbit spacing survives at ${size.width}x${size.height}`, async () => {
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), "lad-orbit-fit-"));
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
      await app.evaluate(({ BrowserWindow }, size) => {
        const win = BrowserWindow.getAllWindows()[0];
        win.setContentSize(size.width, size.height);
      }, size);
      await page.evaluate(() => window.lad!.rpc("snapshot"));
      await page
        .getByRole("button", { name: "Посмотреть демо", exact: true })
        .click();
      const nav = async (label: string) => {
        if (size.width < 760)
          await page.getByRole("link", { name: label, exact: true }).click();
        else
          await page.getByRole("button", { name: label, exact: true }).click();
      };
      await nav("Для тебя");
      const geometry = await page.locator(".wave-home").evaluate((card) => {
        const hero = card.getBoundingClientRect();
        const mobile = document.querySelector(".mobile-dock")!;
        const dock = (
          getComputedStyle(mobile).display !== "none"
            ? mobile
            : document.querySelector(".player")!
        ).getBoundingClientRect();
        const header = document
          .querySelector(".main-header")!
          .getBoundingClientRect();
        return {
          top: hero.top,
          bottom: hero.bottom,
          dock: dock.top,
          header: header.bottom,
          scroll: card.scrollHeight,
          client: card.clientHeight,
          cover: card.querySelector(".art")!.getBoundingClientRect().height,
        };
      });
      expect(geometry.top).toBeGreaterThanOrEqual(geometry.header - 1);
      expect(geometry.bottom, JSON.stringify(geometry)).toBeLessThanOrEqual(
        geometry.dock - 8,
      );
      expect(geometry.scroll).toBeLessThanOrEqual(geometry.client + 2);
      await expect(page.locator(".wave-home-controls")).toBeInViewport();
      const button = page.locator(".wave-home-controls .lad-button").first();
      const spacing = await button.evaluate((el) => {
        const icon = el.querySelector("svg")!.getBoundingClientRect();
        const label = el
          .querySelector(".lad-button-label")!
          .getBoundingClientRect();
        return {
          gap:
            (label.left - icon.right) /
            (el.getBoundingClientRect().width /
              (el as HTMLElement).offsetWidth),
          padding: getComputedStyle(el.querySelector(".orbit-button__icon")!)
            .padding,
        };
      });
      expect(spacing.gap).toBeCloseTo(8, 0);
      expect(spacing.padding).toBe("0px");
      const capture = await app.evaluate(async ({ BrowserWindow }) =>
        (await BrowserWindow.getAllWindows()[0].webContents.capturePage())
          .toPNG()
          .toString("base64"),
      );
      fs.writeFileSync(
        path.join(shots, `refined-wave-${size.width}-${size.height}.png`),
        Buffer.from(capture, "base64"),
      );
      await nav("Поиск");
      await page
        .getByRole("searchbox", { name: "Поиск музыки", exact: true })
        .fill("Тихий");
      const chip = page
        .getByRole("group", { name: "Тип поиска", exact: true })
        .locator('button[aria-pressed="true"]');
      expect(
        await chip
          .locator(".orbit-button__icon")
          .evaluate((el) => getComputedStyle(el).padding),
      ).toBe("0px");
      await nav("Для тебя");
      if (size.width < 760) await page.locator(".mobile-queue-button").click();
      else if (!(await page.locator(".queue-panel").count()))
        await page
          .locator(".player-right")
          .getByRole("button", { name: "Показать очередь", exact: true })
          .click();
      const alignment = await page.locator(".queue-panel").evaluate((el) => {
        const close = el
          .querySelector(".queue-header button")!
          .getBoundingClientRect();
        const clear = el
          .querySelector(".queue-source button")!
          .getBoundingClientRect();
        return Math.abs(close.right - clear.right);
      });
      expect(alignment).toBeLessThanOrEqual(1);
    } finally {
      await app.close();
      fs.rmSync(profile, { recursive: true, force: true });
    }
  });
