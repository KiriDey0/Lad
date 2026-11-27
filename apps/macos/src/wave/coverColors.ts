// SPDX-License-Identifier: AGPL-3.0-only
import {
  coverWavePalette,
  neutralWavePalette,
} from "../../../../shared/wave/palette.mjs";

// The wave and mobile controls share both palette extraction and its cached result.
const cache = new Map<
  string,
  Promise<{ colors: number[][]; lineGain: number }>
>();
export function loadCoverColors(cover?: string | null) {
  const fallback = () => ({
    colors: neutralWavePalette.map((color: number[]) => color.slice()),
    lineGain: 1.25,
  });
  if (!cover) return Promise.resolve(fallback());
  const cached = cache.get(cover);
  if (cached) return cached;
  const result = new Promise<{ colors: number[][]; lineGain: number }>(
    (resolve) => {
      const image = new Image();
      image.crossOrigin = "anonymous";
      image.onerror = () => {
        cache.delete(cover);
        resolve(fallback());
      };
      image.onload = () => {
        try {
          const canvas = document.createElement("canvas");
          canvas.width = canvas.height = 96;
          const ctx = canvas.getContext("2d")!;
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
          resolve(
            coverWavePalette(ctx.getImageData(0, 0, 96, 96).data, 96, 96),
          );
        } catch {
          cache.delete(cover);
          resolve(fallback());
        }
      };
      image.src = cover;
    },
  );
  cache.set(cover, result);
  if (cache.size > 32) cache.delete(cache.keys().next().value!);
  return result;
}
