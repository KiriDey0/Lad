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
      await page.screenshot({ path: path.join(shots, `lyrics-${width}.png`) });
      const navigate = async (label: string) => {
        if (width < 760)
          await page.getByRole("link", { name: label, exact: true }).click();
        else
          await page.getByRole("button", { name: label, exact: true }).click();
      };
      await navigate("Для тебя");
      await expect(page.locator(".wave-home")).toBeVisible();
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
      await page.locator(".player .main-play").click();
      await expect(page.locator(".player .main-play")).toHaveAttribute(
        "aria-label",
        "Пауза",
      );
      await expect(page.locator(".orbit-scrubber")).toHaveAttribute(
        "data-playing",
        "true",
      );
      await page.locator(".player .main-play").click();
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
      await noOverflow(page);
      await page.screenshot({
        path: path.join(shots, `collection-${width}.png`),
      });
      await navigate("Настройки");
      await expect(
        page.getByRole("heading", { name: "Настройки", exact: true }),
      ).toBeVisible();
      await noOverflow(page);
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
      await page.screenshot({ path: path.join(shots, `audio-${width}.png`) });
      await page.getByRole("tab", { name: "Эквалайзер", exact: true }).click();
      await noOverflow(page);
      await expect(
        page.getByRole("tabpanel", { name: "Эквалайзер", exact: true }),
      ).toBeVisible();
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
      expect(box.x + box.width).toBeLessThanOrEqual(width + 1);
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
