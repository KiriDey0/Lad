// SPDX-License-Identifier: AGPL-3.0-only
const percent = (value, fallback) => Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : fallback;

/** 50% retains the original light level; 100% provides twice the light before clipping. */
export const waveBrightness = value => percent(value, 100) / 50;

/** Independent from brightness and listening volume. 50% retains the approved motion. */
export function waveSmoothing(value = 50) {
  const p = percent(value, 50);
  const calm = Math.max(0, (p - 50) / 50);
  return {
    response: 10 ** ((p - 50) / 50),
    direct: Math.max(0, (50 - p) / 50),
    travel: 1 - calm * .7,
    flow: 1 - calm * .75,
  };
}
