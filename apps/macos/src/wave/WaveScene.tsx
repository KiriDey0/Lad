// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useRef } from "react";
import { WebSimulation } from "../../../../shared/wave/web-simulation.mjs";
import { WaveMotion } from "../../../../shared/wave/audio-features.mjs";
import { sampleWaveAudio, setWaveAnalysisEnabled } from "./audio";
import type { Playback } from "../types";
import { bridge } from "../bridge";
import { WaveRenderer } from "./renderer";
import { useRemoteWaveAudio } from "./remote";
import { waveBrightness } from "../../../../shared/wave/appearance.mjs";
import {
  coverWavePalette,
  neutralWavePalette as neutral,
} from "../../../../shared/wave/palette.mjs";
export function WaveScene({
  cover,
  playing,
  local,
  intensity = 100,
  smoothing = 50,
  quality = "auto",
  remote,
}: {
  cover?: string | null;
  playing: boolean;
  local: boolean;
  intensity?: number;
  smoothing?: number;
  quality?: "auto" | "eco" | "smooth";
  remote?: Playback;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const wake = useRef<() => void>(() => {});
  const fps = useRef(30);
  useEffect(() => {
    wake.current();
  }, [playing, intensity, smoothing, quality]);
  useRemoteWaveAudio(remote, canvas, intensity);
  const palette = useRef(neutral.map((c) => c.slice()));
  const lineGain = useRef(1.25);
  const latest = useRef({ playing, local, intensity, smoothing, quality });
  latest.current = { playing, local, intensity, smoothing, quality };
  useEffect(() => {
    if (!cover) {
      palette.current = neutral.map((c) => c.slice());
      lineGain.current = 1.25;
      wake.current();
      return;
    }
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
        const extracted = coverWavePalette(data, 96, 96);
        palette.current = extracted.colors;
        lineGain.current = extracted.lineGain;
        wake.current();
      } catch {
        palette.current = neutral.map((c) => c.slice());
        lineGain.current = 1.25;
        wake.current();
      }
    };
    image.onerror = () => {
      if (!cancelled) {
        palette.current = neutral.map((c) => c.slice());
        lineGain.current = 1.25;
        wake.current();
      }
    };
    image.src = cover;
    return () => {
      cancelled = true;
      image.onload = null;
      image.onerror = null;
    };
  }, [cover]);
  useEffect(() => {
    const el = canvas.current!;
    const sim = new WebSimulation(),
      motion = new WaveMotion();
    const colors = neutral.map((c) => c.slice());
    const reduced = matchMedia("(prefers-reduced-motion: reduce)");
    let last = 0,
      frame = 0,
      visible = true,
      pause = 1,
      w = 0,
      h = 0,
      next = 0;
    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(tick);
    };
    const resume = () => {
      last = 0;
      next = 0;
      schedule();
    };
    wake.current = resume;
    const renderer = new WaveRenderer(el, resume);
    const power = (value: { fps: number }) => {
      fps.current = [20, 30, 60].includes(value.fps) ? value.fps : 30;
      resume();
    };
    let disposed = false;
    void bridge
      .wavePower?.()
      .then((value) => {
        if (!disposed) power(value);
      })
      .catch(() => {});
    let activityKey = "";
    let frames = 0,
      work = 0,
      late = 0,
      sampledAt = 0,
      framePressure = 0;
    const activity = (active: boolean, force = false) => {
      const mode = latest.current.quality,
        key = mode + ":" + active + ":" + framePressure;
      if (!force && key === activityKey) return;
      activityKey = key;
      void bridge
        .wavePower?.({ mode, active, pressure: active ? framePressure : 0 })
        .then((value) => {
          if (!disposed && fps.current !== value.fps) power(value);
        })
        .catch(() => {});
    };
    const unsubscribe = bridge.onEvent?.((event) => {
      if (event.event === "wavePower") power(event.data);
    });
    const resize = new ResizeObserver(() => {
      const box = el.getBoundingClientRect();
      w = box.width;
      h = box.height;
      const dpr = Math.min(2, devicePixelRatio);
      renderer.resize(w, h, dpr);
      resume();
    });
    resize.observe(el);
    const intersection = new IntersectionObserver((e) => {
      visible = e[0].isIntersecting;
      resume();
    });
    intersection.observe(el);
    sim.step(0.016);
    const tick = (now: number) => {
      frame = 0;
      if (!visible || document.hidden || !renderer.available()) {
        activity(false);
        setWaveAnalysisEnabled(false);
        last = 0;
        return;
      }
      const p = latest.current;
      activity(p.playing && p.intensity > 0 && !reduced.matches);
      setWaveAnalysisEnabled(p.playing && p.intensity > 0 && !reduced.matches);
      if (p.intensity <= 0) {
        renderer.clear();
        last = 0;
        return;
      }
      if (now < next - 0.25) {
        schedule();
        return;
      }
      const started = performance.now();
      const interval = 1000 / fps.current;
      if (last && now - last > interval * 1.75) late++;
      next = now + interval - (next ? Math.max(0, now - next) % interval : 0);
      const dt = last ? Math.min(0.05, (now - last) / 1000) : 0.016;
      last = now;
      pause += (Number(!p.playing) - pause) * (1 - Math.exp(-dt * 4));
      const f = p.playing
        ? sampleWaveAudio(dt)
        : {
            bass: 0,
            mid: 0,
            high: 0,
            level: 0,
            spectrum: new Float32Array(8),
          };
      const audio = motion.update(f, dt, 0.75, p.smoothing);
      if (!reduced.matches && pause < 0.999)
        sim.step(dt * (1 - pause), audio, p.smoothing);
      const strength = waveBrightness(p.intensity) * (1 - pause * 0.82);
      colors.forEach((c, i) =>
        c.forEach(
          (v, j) =>
            (c[j] = v + (palette.current[i][j] - v) * (1 - Math.exp(-dt * 2))),
        ),
      );
      renderer.draw(sim, colors, strength, lineGain.current);
      if (p.playing && !reduced.matches) {
        frames++;
        work += performance.now() - started;
        if (!sampledAt) sampledAt = now;
        if (now - sampledAt >= 2000 && frames >= 8) {
          const mean = work / frames,
            misses = late / frames;
          framePressure =
            mean > 12 || misses > 0.55 ? 2 : mean > 5 || misses > 0.2 ? 1 : 0;
          activity(true, true);
          sampledAt = now;
          frames = work = late = 0;
        }
      } else {
        frames = work = late = sampledAt = framePressure = 0;
      }
      const settling =
        Math.abs(Number(!p.playing) - pause) > 0.001 ||
        colors.some((c, i) =>
          c.some((v, j) => Math.abs(v - palette.current[i][j]) > 0.1),
        );
      if ((p.playing && !reduced.matches) || settling) schedule();
      else last = 0;
    };
    document.addEventListener("visibilitychange", resume);
    reduced.addEventListener("change", resume);
    schedule();
    return () => {
      activity(false);
      disposed = true;
      setWaveAnalysisEnabled(false);
      wake.current = () => {};
      unsubscribe?.();
      document.removeEventListener("visibilitychange", resume);
      reduced.removeEventListener("change", resume);
      cancelAnimationFrame(frame);
      renderer.dispose();
      resize.disconnect();
      intersection.disconnect();
    };
  }, []);
  return <canvas ref={canvas} className="wave-scene" aria-hidden="true" />;
}
