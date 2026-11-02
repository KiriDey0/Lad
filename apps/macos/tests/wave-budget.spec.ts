// SPDX-License-Identifier: AGPL-3.0-only
import { test, expect, _electron } from "@playwright/test";
import { buildSync } from "esbuild";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

for (const renderer of ["webgl", "canvas"])
  test(
    renderer +
      ": wave sleeps at rest, wakes on changes and respects 30/20 FPS on a 144 Hz clock",
    async () => {
      const dir = fs.mkdtempSync(path.join(os.tmpdir(), "lad-budget-")),
        main = path.join(dir, "main.cjs"),
        root = path.resolve(__dirname, "..");
      fs.writeFileSync(
        main,
        'const {app,BrowserWindow}=require("electron");app.setPath("userData",' +
          JSON.stringify(dir) +
          ');app.whenReady().then(()=>new BrowserWindow({show:false}).loadURL("about:blank"));',
      );
      const bundle = buildSync({
        stdin: {
          contents:
            'import React from "react";import {createRoot} from "react-dom/client";import {flushSync} from "react-dom";import {WaveScene} from ' +
            JSON.stringify(root + "/src/wave/WaveScene.tsx") +
            ';const root=createRoot(document.getElementById("root"));let p={playing:true,local:true,intensity:100};window.setWave=(change)=>{p={...p,...change};flushSync(()=>root.render(<WaveScene {...p}/>));};window.setWave({});',
          loader: "tsx",
          resolveDir: root,
        },
        bundle: true,
        write: false,
        jsx: "automatic",
      });
      const app = await _electron.launch({ args: [main] });
      try {
        const page = await app.firstWindow();
        page.on("pageerror", (e) => console.log("WAVE ERROR", e.message));
        await page.setContent(
          '<style>canvas{width:600px;height:600px}</style><div id="root"></div>',
        );
        await page.evaluate(() => {
          const w = window as any;
          let now = 0,
            id = 0;
          const pending = new Map<number, FrameRequestCallback>();
          w.requestAnimationFrame = (fn: FrameRequestCallback) => {
            pending.set(++id, fn);
            return id;
          };
          w.cancelAnimationFrame = (n: number) => pending.delete(n);
          w.advance = (hz: number, seconds: number) => {
            for (let i = 0; i < Math.round(hz * seconds); i++) {
              now += 1000 / hz;
              const list = [...pending.values()];
              pending.clear();
              for (const fn of list) fn(now);
            }
            return { pending: pending.size, draws: w.draws };
          };
          w.draws = 0;
          const clear = CanvasRenderingContext2D.prototype.clearRect;
          CanvasRenderingContext2D.prototype.clearRect = function (
            ...args: Parameters<typeof clear>
          ) {
            w.draws++;
            return clear.apply(this, args);
          };
          const glClear = WebGLRenderingContext.prototype.clear;
          WebGLRenderingContext.prototype.clear = function (mask: number) {
            w.draws++;
            return glClear.call(this, mask);
          };
          w.lad = {
            wavePower: async () => ({ fps: 30 }),
            onEvent: (fn: any) => {
              w.powerEvent = fn;
              return () => {};
            },
          };
          Object.defineProperty(document, "hidden", {
            configurable: true,
            value: false,
          });
        });
        if (renderer === "canvas")
          await page.evaluate(() => {
            const native = HTMLCanvasElement.prototype.getContext;
            HTMLCanvasElement.prototype.getContext = function (
              kind: any,
              ...args: any[]
            ) {
              return kind === "webgl"
                ? null
                : (native as any).call(this, kind, ...args);
            } as any;
          });
        await page.addScriptTag({ content: bundle.outputFiles[0].text });
        await expect
          .poll(() =>
            page.evaluate(() => !!document.querySelector("canvas")?.width),
          )
          .toBe(true);
        await page.waitForTimeout(50);
        const running = await page.evaluate(() =>
          (window as any).advance(144, 3),
        );
        expect(running.draws).toBeGreaterThan(85);
        expect(running.draws).toBeLessThanOrEqual(92);
        await page.evaluate(() => (window as any).setWave({ playing: false }));
        const asleep = await page.evaluate(() =>
          (window as any).advance(144, 3),
        );
        expect(asleep.pending).toBe(0);
        expect(
          (await page.evaluate(() => (window as any).advance(144, 2))).draws,
        ).toBe(asleep.draws);
        await page.evaluate(() =>
          (window as any).setWave({ intensity: 0, playing: true }),
        );
        const off = await page.evaluate(() => (window as any).advance(144, 2));
        expect(off.pending).toBe(0);
        expect(off.draws - asleep.draws).toBe(1);
        await page.evaluate(() => {
          (window as any).powerEvent({ event: "wavePower", data: { fps: 20 } });
          (window as any).setWave({ intensity: 100 });
        });
        const battery = await page.evaluate(() =>
          (window as any).advance(144, 3),
        );
        expect(battery.draws - off.draws).toBeGreaterThan(55);
        expect(battery.draws - off.draws).toBeLessThanOrEqual(62);
        await page.evaluate(() => {
          Object.defineProperty(document, "hidden", {
            configurable: true,
            value: true,
          });
          document.dispatchEvent(new Event("visibilitychange"));
        });
        const hidden = await page.evaluate(() =>
          (window as any).advance(144, 3),
        );
        expect(hidden.pending).toBe(0);
        expect(hidden.draws).toBe(battery.draws);
        await page.evaluate(() => {
          Object.defineProperty(document, "hidden", {
            configurable: true,
            value: false,
          });
          document.dispatchEvent(new Event("visibilitychange"));
        });
        expect(
          (await page.evaluate(() => (window as any).advance(144, 1))).draws,
        ).toBeGreaterThan(hidden.draws);
        if (renderer === "webgl") {
          await page.evaluate(() => {
            const gl = document.querySelector("canvas")!.getContext("webgl")!;
            (window as any).lostContext = gl.getExtension("WEBGL_lose_context");
            (window as any).lostContext.loseContext();
          });
          await expect.poll(() => page.evaluate(() => document.querySelector("canvas")!.getContext("webgl")!.isContextLost())).toBe(true);
          expect((await page.evaluate(() => (window as any).advance(144, 1))).pending).toBe(0);
          await page.evaluate(() => (window as any).lostContext.restoreContext());
          await expect.poll(() => page.evaluate(() => document.querySelector("canvas")!.getContext("webgl")!.isContextLost())).toBe(false);
          expect((await page.evaluate(() => (window as any).advance(144, 1))).pending).toBe(1);
          expect(await page.evaluate(() => document.querySelector("canvas")!.getContext("webgl")!.getError())).toBe(0);
        }
      } finally {
        await app.close();
        fs.rmSync(dir, { recursive: true, force: true });
      }
    },
  );
