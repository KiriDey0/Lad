// SPDX-License-Identifier: AGPL-3.0-only
export const neutralWavePalette = [[213, 225, 238], [235, 222, 204]];
export function luminance(rgb) {
  const c = rgb.map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4);
  return c[0] * .2126 + c[1] * .7152 + c[2] * .0722;
}
function visibleColor(rgb) {
  if (luminance(rgb) >= .48) return rgb.map(Math.round);
  let low = 0, high = 1;
  for (let i = 0; i < 12; i++) {
    const amount = (low + high) / 2;
    if (luminance(rgb.map(v => v + (255 - v) * amount)) < .48) low = amount;
    else high = amount;
  }
  return rgb.map(v => Math.round(v + (255 - v) * high));
}
/** Sample the whole artwork. Achromatic art has an intentional bright fallback. */
export function coverWavePalette(data, width, height) {
  const fallback = () => ({colors: neutralWavePalette.map(c => c.slice()), lineGain: 1.25, achromatic: true});
  if (!width || !height || data.length < width * height * 4) return fallback();
  const bins = new Map();
  let samples = 0;
  for (let y = 0; y < height; y += 2) for (let x = 0; x < width; x += 2) {
    const i = (y * width + x) * 4;
    if (data[i + 3] < 128) continue;
    samples++;
    const rgb = [data[i], data[i + 1], data[i + 2]];
    const chroma = (Math.max(...rgb) - Math.min(...rgb)) / 255;
    if (chroma < .12) continue;
    const id = (rgb[0] >> 5) * 64 + (rgb[1] >> 5) * 8 + (rgb[2] >> 5);
    const bin = bins.get(id) || {sum: [0, 0, 0], count: 0};
    bin.count++;
    rgb.forEach((v, j) => bin.sum[j] += v);
    bins.set(id, bin);
  }
  const candidates = [...bins.values()].filter(b => b.count >= Math.max(3, samples * .003))
    .map(b => {
      const color = b.sum.map(v => v / b.count);
      const chroma = (Math.max(...color) - Math.min(...color)) / 255;
      return {color, score: b.count * (.2 + chroma) ** 2};
    }).sort((a, b) => b.score - a.score);
  if (!candidates.length) return fallback();
  const primary = candidates[0];
  const second = candidates.slice(1).find(b => b.score >= primary.score * .15 &&
    Math.hypot(...b.color.map((v, j) => v - primary.color[j])) >= 80);
  const secondary = second?.color || primary.color.map((v, j) => v * .75 + neutralWavePalette[1][j] * .25);
  return {colors: [visibleColor(primary.color), visibleColor(secondary)], lineGain: 1, achromatic: false};
}
