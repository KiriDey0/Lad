// SPDX-License-Identifier: AGPL-3.0-only
import { test, expect, _electron as electron } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';

test('release history stays compact, scrolls internally and retries a failed request', async () => {
  test.setTimeout(90000);
  const root = path.resolve(__dirname, '..');
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'lad-history-'));
  const app = await electron.launch({ args: [root], env: { ...process.env, LAD_USER_DATA: profile }, timeout: 60000 });
  try {
    const page = await app.firstWindow();
    await page.waitForLoadState('domcontentloaded');
    await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setContentSize(1280, 800));
    await page.getByRole('button', { name: 'Посмотреть демо', exact: true }).click();
    await expect(page.locator('.header-location')).toHaveText('Сейчас играет');
    await expect(page.getByRole('heading', { name: 'Первый свет', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Настройки', exact: true }).click();
    await app.evaluate(({ ipcMain }) => {
      ipcMain.removeHandler('lad:release-history');
      ipcMain.handle('lad:release-history', async () => Array.from({ length: 12 }, (_, i) => ({
        version: `0.6.${12 - i}`, date: '2026-11-16T12:00:00Z',
        notes: '## Что нового\n\n- Улучшили управление музыкой.\n- Исправили эквалайзер и ввод значений с клавиатуры.\n\nНастройки и очередь сохраняются после обновления.',
      })));
    });
    const trigger = page.getByRole('button', { name: 'История версий', exact: true });
    await trigger.click();
    const dialog = page.getByRole('dialog', { name: 'История версий', exact: true });
    await expect(dialog.locator('article')).toHaveCount(12);
    await expect(dialog).toHaveCSS('max-height', '640px');
    await expect.poll(async () => (await dialog.boundingBox())!.height).toBeLessThanOrEqual(640);
    expect(await dialog.innerText()).not.toContain('##');
    const box = await dialog.boundingBox();
    expect(box!.width).toBeLessThanOrEqual(560);
    expect(box!.height).toBeLessThanOrEqual(640);
    const header = await dialog.locator('.orbit-dialog__head').boundingBox();
    const scroll = dialog.locator('.modal-scroll');
    expect(await scroll.evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true);
    await scroll.focus();
    await page.keyboard.press('PageDown');
    await expect.poll(() => scroll.evaluate(el => el.scrollTop)).toBeGreaterThan(0);
    expect((await dialog.locator('.orbit-dialog__head').boundingBox())!.y).toBe(header!.y);
    await page.screenshot({ path: '/tmp/lad-history-desktop.png' });
    await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setContentSize(1050, 600));
    const small = await dialog.boundingBox();
    expect(small!.y).toBeGreaterThanOrEqual(0);
    expect(small!.y + small!.height).toBeLessThanOrEqual(await page.evaluate(() => innerHeight));
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();
    await app.evaluate(({ ipcMain }) => {
      ipcMain.removeHandler('lad:release-history');
      ipcMain.handle('lad:release-history', () => { throw Error('Нет подключения'); });
    });
    await trigger.click();
    await expect(dialog.getByRole('alert')).toContainText('Нет подключения');
    await app.evaluate(({ ipcMain }) => {
      ipcMain.removeHandler('lad:release-history');
      ipcMain.handle('lad:release-history', async () => []);
    });
    await dialog.getByRole('button', { name: 'Попробовать снова' }).click();
    await expect(dialog.getByText('Публичных выпусков пока нет.')).toBeVisible();
    await dialog.getByRole('button', { name: 'Закрыть' }).click();
    await expect(dialog).toHaveCount(0);
  } finally {
    await app.close();
    fs.rmSync(profile, { recursive: true, force: true });
  }
});
