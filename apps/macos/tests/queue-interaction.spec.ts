// SPDX-License-Identifier: AGPL-3.0-only
import { test, expect, _electron as electron, type ElectronApplication, type Page } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';
let app: ElectronApplication, page: Page, profile: string;
const snapshot = () => page.evaluate(() => window.lad!.rpc('snapshot'));
test.beforeAll(async () => {
  profile = fs.mkdtempSync(path.join(os.tmpdir(), 'lad-queue-ui-'));
  app = await electron.launch({ executablePath: process.env.LAD_TEST_EXE || undefined, args: process.env.LAD_TEST_EXE ? [] : [path.resolve(__dirname, '..')], env: { ...process.env, LAD_USER_DATA: profile, LAD_PYTHON: process.env.LAD_PYTHON } });
  page = await app.firstWindow();
  await page.waitForLoadState('domcontentloaded');
  await page.evaluate(() => window.lad!.rpc('snapshot'));
  await page.getByRole('button', { name: 'Посмотреть демо', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Первый свет', exact: true })).toBeVisible();
  await page.evaluate(() => window.lad!.rpc('control', { action: 'pause' }));
});
test.afterAll(async () => { await app?.close(); fs.rmSync(profile, {recursive: true, force: true}); });
test.describe.configure({ mode: 'serial' });

async function fillQueue(count: number, index: number) {
  await page.evaluate(async ({ count, index }) => {
    const s = await window.lad!.rpc('snapshot');
    const template = s.queue.tracks[0];
    await window.lad!.rpc('play', { tracks: Array.from({ length: count }, (_, n) => ({ ...template, title: `Трек ${n}` })), index, source: 'QA' });
    await window.lad!.rpc('control', { action: 'pause' });
  }, { count, index });
  if (await page.locator('.queue-panel').count()) {
    await expect(page.locator('.queue-scroll > .queue-row')).toHaveCount(count);
    await expect(page.locator('.queue-current .queue-track strong')).toHaveText(`Трек ${index}`);
  }
}

test('opening queue anchors current occurrence at two thirds and never follows manual scrolling', async () => {
  await page.getByRole('button', { name: 'Скрыть очередь', exact: true }).click();
  await fillQueue(100, 40);
  await page.getByRole('button', { name: 'Показать очередь', exact: true }).click();
  const scroll = page.locator('.queue-scroll');
  const row = page.locator('.queue-current');
  const ratio = await row.evaluate(el => {
    const list = el.parentElement!;
    return (el.getBoundingClientRect().top - list.getBoundingClientRect().top) / list.clientHeight;
  });
  expect(ratio).toBeCloseTo(2/3, 1);
  await scroll.evaluate(el => el.scrollTop = 0);
  await page.evaluate(() => window.lad!.rpc('control', { action: 'seek', value: 25 }));
  expect(await scroll.evaluate(el => el.scrollTop)).toBe(0);
});

test('duplicate menu removes the selected occurrence even after another row moves', async () => {
  await fillQueue(5, 0);
  const before = await snapshot();
  const lastId = before.queue.tracks[4].queueEntryId;
  const firstD = before.queue.tracks[1].queueEntryId;
  await page.locator('.queue-row').nth(4).click({ button: 'right' });
  await page.evaluate(() => window.lad!.rpc('queue', { action: 'move', from: 0, to: 2 }));
  await page.getByRole('menuitem', { name: 'Удалить из очереди', exact: true }).click();
  await expect.poll(async () => (await snapshot()).queue.tracks.length).toBe(4);
  const after = await snapshot();
  expect(after.queue.tracks.some((t: any) => t.queueEntryId === lastId)).toBe(false);
  expect(after.queue.tracks.some((t: any) => t.queueEntryId === firstD)).toBe(true);
  expect(after.queue.tracks[after.queue.index].queueEntryId).toBe(before.queue.tracks[0].queueEntryId);
});

test('drag survives stationary edge autoscroll, makes a row-sized gap, and moves that exact occurrence', async () => {
  await fillQueue(100, 40);
  const scroll = page.locator('.queue-scroll');
  await scroll.evaluate(el => el.scrollTop = 0);
  const before = await snapshot();
  const movingId = before.queue.tracks[1].queueEntryId;
  const grip = page.locator('.queue-row').nth(1).locator('.queue-grip');
  const box = (await grip.boundingBox())!;
  await page.mouse.move(box.x + box.width/2, box.y + box.height/2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width/2, box.y + box.height/2 + 10);
  await expect(page.locator('.queue-floating')).toBeVisible();
  await page.locator('.queue-placeholder .queue-grip').dispatchEvent('lostpointercapture', {pointerId:1,bubbles:true});
  await scroll.evaluate(el => el.releasePointerCapture(1));
  await page.locator('.queue-placeholder .queue-grip').dispatchEvent('pointerout', {pointerId:1,bubbles:true,relatedTarget:null,clientX:box.x+5,clientY:box.y+5});
  await expect(page.locator('.queue-floating')).toBeVisible();
  const list = (await scroll.boundingBox())!;
  await page.mouse.move(box.x + box.width/2, list.y + list.height - 8, {steps: 8});
  await expect.poll(() => scroll.evaluate(el => el.scrollTop), { timeout: 15000 }).toBeGreaterThan(900);
  await expect(page.locator('.queue-floating')).toBeVisible();
  await expect(page.locator('.queue-placeholder')).toHaveCount(1);
  const float = (await page.locator('.queue-floating').boundingBox())!;
  const gap = (await page.locator('.queue-placeholder').boundingBox())!;
  expect(gap.height).toBeCloseTo(float.height, 0);
  await page.screenshot({ path: '/tmp/lad-queue-drag.png' });
  await page.mouse.up();
  await expect(page.locator('.queue-floating')).toHaveCount(0);
  await expect.poll(async () => (await snapshot()).queue.tracks.findIndex((t: any) => t.queueEntryId === movingId)).toBeGreaterThan(15);
  const after = await snapshot();
  expect(after.queue.tracks).toHaveLength(100);
  expect(new Set(after.queue.tracks.map((t: any) => t.queueEntryId)).size).toBe(100);
  expect(after.queue.tracks[after.queue.index].queueEntryId).toBe(before.queue.tracks[40].queueEntryId);
});

test('cancelling a drag restores order without a playback command', async () => {
  const before = await snapshot();
  await page.locator('.queue-scroll').evaluate(el => el.scrollTop = 0);
  const grip = (await page.locator('.queue-row').nth(1).locator('.queue-grip').boundingBox())!;
  await page.mouse.move(grip.x+5, grip.y+5); await page.mouse.down();
  await page.mouse.move(grip.x+5, grip.y+120, {steps: 8});
  await expect(page.locator('.queue-floating')).toBeVisible();
  await page.keyboard.press('Escape'); await page.mouse.up();
  await expect(page.locator('.queue-floating')).toHaveCount(0);
  expect((await snapshot()).queue.tracks.map((t: any) => t.queueEntryId)).toEqual(before.queue.tracks.map((t: any) => t.queueEntryId));
});

test('a second pointer cannot finish or replace an active drag', async () => {
  await fillQueue(30, 0);
  const scroll = page.locator('.queue-scroll');
  await scroll.evaluate(el => el.scrollTop = 0);
  const before = await snapshot();
  const grip = (await page.locator('.queue-row').nth(1).locator('.queue-grip').boundingBox())!;
  await page.mouse.move(grip.x+5, grip.y+5); await page.mouse.down();
  await page.mouse.move(grip.x+5, grip.y+100, {steps:8});
  await expect(page.locator('.queue-floating')).toBeVisible();
  const other = page.locator('.queue-row:not(.queue-placeholder) .queue-grip').first();
  await other.dispatchEvent('pointerdown', {pointerId:2,pointerType:'touch',button:0,buttons:1,isPrimary:false});
  await other.dispatchEvent('pointerup', {pointerId:2,pointerType:'touch',button:0,buttons:0,isPrimary:false});
  await expect(page.locator('.queue-floating')).toBeVisible();
  await page.keyboard.press('Escape'); await page.mouse.up();
  await expect(page.locator('.queue-floating')).toHaveCount(0);
  expect((await snapshot()).queue.tracks.map((t:any)=>t.queueEntryId)).toEqual(before.queue.tracks.map((t:any)=>t.queueEntryId));
});

test('holding a touch drag keeps the card alive while the viewport scrolls', async () => {
  await fillQueue(100, 40);
  await page.locator('.queue-scroll').evaluate(el => el.scrollTop = 0);
  const id = (await snapshot()).queue.tracks[1].queueEntryId;
  const box = (await page.locator('.queue-row').nth(1).locator('.queue-grip').boundingBox())!;
  const bounds = (await page.locator('.queue-scroll').boundingBox())!;
  const session = await page.context().newCDPSession(page);
  const point = {x:box.x+box.width/2,y:box.y+box.height/2};
  let touching = false;
  try {
    await session.send('Emulation.setTouchEmulationEnabled', {enabled:true,maxTouchPoints:2});
    await session.send('Input.dispatchTouchEvent', {type:'touchStart',touchPoints:[point]});
    touching = true;
    await expect(page.locator('.queue-floating')).toBeVisible();
    await session.send('Input.dispatchTouchEvent', {type:'touchMove',touchPoints:[{x:point.x,y:bounds.y+bounds.height-6}]});
    await expect.poll(() => page.locator('.queue-scroll').evaluate(el=>el.scrollTop), {timeout:15000}).toBeGreaterThan(900);
    await expect(page.locator('.queue-floating')).toBeVisible();
    await session.send('Input.dispatchTouchEvent', {type:'touchEnd',touchPoints:[]});
    touching = false;
    await expect.poll(async () => (await snapshot()).queue.tracks.findIndex((t:any)=>t.queueEntryId===id)).toBeGreaterThan(15);
  } finally {
    if (touching) await session.send('Input.dispatchTouchEvent', {type:'touchCancel',touchPoints:[]});
    await session.send('Emulation.setTouchEmulationEnabled', {enabled:false});
    await session.detach();
  }
});

test('wave can draw above the hero boundary without shrinking cover or simulation', async () => {
  await page.getByRole('button', { name: 'Для тебя', exact: true }).click();
  await page.getByRole('button', { name: 'Включить Мою волну', exact: true }).click();
  const hero = page.locator('.wave-home');
  await expect(hero).toBeVisible();
  const geometry = await hero.evaluate(el => {
    const canvas = el.closest('.main')!.querySelector('.wave-backdrop canvas')!;
    return {overflow: getComputedStyle(el).overflow, bleed: el.getBoundingClientRect().top-canvas.getBoundingClientRect().top, height: canvas.getBoundingClientRect().height};
  });
  expect(geometry.overflow).toBe('visible');
  expect(geometry.bleed).toBeGreaterThanOrEqual(150);
  expect(geometry.height).toBeGreaterThanOrEqual(770);
  await page.screenshot({ path: '/tmp/lad-wave-bleed.png' });
});
