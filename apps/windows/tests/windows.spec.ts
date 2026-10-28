// SPDX-License-Identifier: AGPL-3.0-only
import { test, expect, _electron as electron, type ElectronApplication } from "@playwright/test";
import { spawn } from "node:child_process";
import path from "node:path";
import fs from "node:fs";

test.skip(process.platform !== "win32", "Native Windows lifecycle checks");
test("Windows secure storage, Unicode RPC, native controls, tray restore and single instance", async () => {
  const root = path.resolve(__dirname, "..");
  const profile = process.env.LAD_WINDOWS_PROFILE || path.join(root, "../../qa/windows-lifecycle");
  const executablePath = process.env.LAD_TEST_EXE;
  expect(executablePath).toBeTruthy();
  let app: ElectronApplication | undefined;
  try {
    app = await electron.launch({ executablePath, args: [], env: { ...process.env, LAD_USER_DATA: profile }, timeout: 60000 });
    const page = await app.firstWindow();
    await expect(page.getByRole("heading", {name: "Музыка здесь. Звук — где хочешь."})).toBeVisible();
    const storage = await app.evaluate(({ safeStorage, app }) => {
      const input = "Лад / Windows / тест 你好";
      const encrypted = safeStorage.encryptString(input);
      return { available: safeStorage.isEncryptionAvailable(), roundtrip: safeStorage.decryptString(encrypted) === input,
        ciphertextDiffers: !encrypted.includes(Buffer.from(input)), profile: app.getPath("userData") };
    });
    expect(storage.available).toBe(true);
    expect(storage.roundtrip).toBe(true);
    expect(storage.ciphertextDiffers).toBe(true);
    expect(path.normalize(storage.profile)).toBe(path.normalize(profile));
    await page.getByRole("button", { name: "Посмотреть демо" }).click();
    await page.evaluate(() => window.lad!.rpc("control", {action: "pause"}));
    const state = await page.evaluate(() => window.lad!.rpc("snapshot"));
    expect(state.playback.track.title).toBe("Первый свет");
    const chrome = await page.evaluate(() => ({ platform: window.lad!.platform,
      overlay: !!(navigator as any).windowControlsOverlay?.visible,
      dragRegion: getComputedStyle(document.querySelector(".titlebar")!).getPropertyValue("-webkit-app-region") }));
    expect(chrome.platform).toBe("win32");
    expect(chrome.overlay).toBe(true);
    expect(chrome.dragRegion).toBe("drag");
    const out = process.env.LAD_QA_DIR || path.join(root,"../../qa");
    fs.mkdirSync(out,{recursive:true});
    const capture = async (name: string) => {
      const encoded = await app!.evaluate(async ({BrowserWindow}) =>
        (await BrowserWindow.getAllWindows()[0].webContents.capturePage()).toPNG().toString("base64"));
      fs.writeFileSync(path.join(out,name),Buffer.from(encoded,"base64"));
    };
    await capture("windows-now-playing.png");
    await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].close());
    await expect.poll(() => app!.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].isVisible())).toBe(false);
    const extra = spawn(executablePath!, [], { env: { ...process.env, LAD_USER_DATA: profile }, stdio: "ignore" });
    await new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => { extra.kill(); reject(new Error("Second instance did not exit")); }, 15000);
      extra.once("error", (e) => {clearTimeout(timeout); reject(e)});
      extra.once("exit", (code) => {clearTimeout(timeout); code === 0 ? resolve() : reject(new Error(`Second exit ${code}`))});
    });
    await expect.poll(() => app!.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].isVisible())).toBe(true);
    expect(await app.evaluate(({BrowserWindow}) => BrowserWindow.getAllWindows().length)).toBe(1);
    const exitMenu = await app.evaluate(({Menu}) => {
      const menu = Menu.getApplicationMenu();
      return menu?.items.some(i => i.submenu?.items.some(child => child.label === "Выйти из Лада"));
    });
    expect(exitMenu).toBe(true);
    await page.getByRole("button", { name: "Устройство воспроизведения", exact:true }).click();
    await capture("windows-device-glass.png");
    await expect(page.getByRole("dialog")).toBeVisible();
  } finally { await app?.close(); }
});
