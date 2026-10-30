// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useRef } from "react";
import { WebSimulation } from "../../../../shared/wave/web-simulation.mjs";
import { WaveMotion } from "../../../../shared/wave/audio-features.mjs";
import { sampleWaveAudio } from "./audio";
import type { Playback } from "../types";
import { useRemoteWaveAudio } from "./remote";
import {waveBrightness} from "../../../../shared/wave/appearance.mjs";
const neutral = [
  [166, 182, 192],
  [190, 180, 165],
];
export function WaveScene({
  cover,
  playing,
  local,
  intensity = 100,
  smoothing = 50,
  remote,
}: {
  cover?: string | null;
  playing: boolean;
  local: boolean;
  intensity?: number;
  smoothing?: number;
  remote?: Playback;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  useRemoteWaveAudio(remote, canvas, intensity);
  const palette = useRef(neutral.map((c) => c.slice()));
  const latest = useRef({ playing, local, intensity, smoothing });
  latest.current = { playing, local, intensity, smoothing };
  useEffect(() => {
    palette.current = neutral.map((c) => c.slice());
    if (!cover) return;
    let cancelled = false;
    const image = new Image();
    image.crossOrigin = "anonymous";
    image.onload = () => {
      if (cancelled) return;
      try {
        const c = document.createElement("canvas");
        c.width = c.height = 96;
        const ctx = c.getContext("2d")!;
        const side = Math.min(image.width, image.height);
        ctx.drawImage(
          image,
          (image.width - side) / 2,
          (image.height - side) / 2,
          side,
          side,
          0,
          0,
          96,
          96,
        );
        const data = ctx.getImageData(0, 0, 96, 96).data;
        const sums = [
          [0, 0, 0, 0],
          [0, 0, 0, 0],
        ];
        for (let y = 0; y < 96; y += 2)
          for (let x = 0; x < 96; x += 2) {
            if (x > 11 && x < 84 && y > 11 && y < 84) continue;
            const o = (y * 96 + x) * 4,
              r = data[o],
              g = data[o + 1],
              b = data[o + 2],
              l = (r + g + b) / 765;
            if (l < 0.09 || l > 0.92) continue;
            const a = sums[y < 48 ? 0 : 1];
            a[0] += r;
            a[1] += g;
            a[2] += b;
            a[3]++;
          }
        palette.current = sums.map((a, i) =>
          a[3]
            ? a
                .slice(0, 3)
                .map((v) => Math.max(42, Math.min(210, (v / a[3]) * 1.2)))
            : neutral[i].slice(),
        );
      } catch {
        /* Some cover servers deny pixel access: keep the neutral C4 palette. */
      }
    };
    image.src = cover;
    return () => {
      cancelled = true;
      image.onload = null;
    };
  }, [cover]);
  useEffect(() => {
    const el = canvas.current!,
      ctx = el.getContext("2d");
    if (!ctx) return;
    const sim = new WebSimulation(),
      motion = new WaveMotion();
    const colors = neutral.map((c) => c.slice());
    const reduced = matchMedia("(prefers-reduced-motion: reduce)");
    let last = 0,
      frame = 0,
      visible = true,
      pause = 1,
      w = 0,
      h = 0;
    const resize = new ResizeObserver(() => {
      const box = el.getBoundingClientRect();
      w = box.width;
      h = box.height;
      const dpr = Math.min(2, devicePixelRatio);
      el.width = Math.round(w * dpr);
      el.height = Math.round(h * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    });
    resize.observe(el);
    const intersection = new IntersectionObserver((e) => {
      visible = e[0].isIntersecting;
      last = 0;
    });
    intersection.observe(el);
    sim.step(0.016);
    const tick = (now: number) => {
      frame = requestAnimationFrame(tick);
      if (!visible || document.hidden) {
        last = 0;
        return;
      }
      const dt = last ? Math.min(0.05, (now - last) / 1000) : 0.016;
      last = now;
      const p = latest.current;
      pause += (Number(!p.playing) - pause) * (1 - Math.exp(-dt * 4));
      const f =
        p.playing
          ? sampleWaveAudio(dt)
          : {
              bass: 0,
              mid: 0,
              high: 0,
              level: 0,
              spectrum: new Float32Array(8),
            };
      const audio = motion.update(f, dt, 0.75, p.smoothing);
      if (!reduced.matches && pause < 0.999) sim.step(dt * (1 - pause), audio, p.smoothing);
      ctx.clearRect(0, 0, w, h);
      const size = Math.min(w, h) * 1.08,
        cx = w * 0.5,
        cy = h * 0.42;
      const strength =
        waveBrightness(p.intensity) * (1 - pause * 0.82);
      colors.forEach((c, i) =>
        c.forEach(
          (v, j) =>
            (c[j] = v + (palette.current[i][j] - v) * (1 - Math.exp(-dt * 2))),
        ),
      );
      const tint = colors[0].map((v, i) =>
        Math.round(v * 0.55 + colors[1][i] * 0.45),
      );
      ctx.globalCompositeOperation = "lighter";
      ctx.lineWidth = 0.8;
      for (let i = 0; i < sim.lineCount; i += 2) {
        const o = i * 3;
        ctx.strokeStyle = `rgba(${tint.join(",")},${Math.min(1, sim.lines[o + 2] * strength * 1.65)})`;
        ctx.beginPath();
        ctx.moveTo(cx + sim.lines[o] * size, cy + sim.lines[o + 1] * size);
        ctx.lineTo(cx + sim.lines[o + 3] * size, cy + sim.lines[o + 4] * size);
        ctx.stroke();
      }
      for (let i = 0; i < sim.count; i++) {
        const o = i * 4,
          x = cx + sim.points[o] * size,
          y = cy + sim.points[o + 1] * size,
          r = sim.points[o + 2] * size * 0.5,
          depth = sim.points[o + 3];
        const color = colors[1].map((v, j) =>
          Math.round(v + (colors[0][j] - v) * depth),
        );
        ctx.fillStyle = `rgba(${color.join(",")},${Math.min(1, Math.min(1, (0.2 + depth * 0.48) * 2) * strength)})`;
        ctx.beginPath();
        ctx.arc(x, y, Math.max(0.5, r * 0.42), 0, Math.PI * 2);
        ctx.fill();
        ctx.fillStyle = `rgba(${color.join(",")},${0.07 * strength})`;
        ctx.beginPath();
        ctx.arc(x, y, r * 1.4, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.globalCompositeOperation = "source-over";
    };
    frame = requestAnimationFrame(tick);
    return () => {
      cancelAnimationFrame(frame);
      resize.disconnect();
      intersection.disconnect();
    };
  }, []);
  return <canvas ref={canvas} className="wave-scene" aria-hidden="true" />;
}
