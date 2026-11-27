// SPDX-License-Identifier: AGPL-3.0-only
export const INTERFACE_BASE_ZOOM = 0.85;
export const INTERFACE_SCALE_BASIS = 85;
export const MIN_INTERFACE_SCALE = 75;
export const MAX_INTERFACE_SCALE = 140;
export const interfaceZoom = (displayPercent: number) =>
  (displayPercent / 100) * INTERFACE_BASE_ZOOM;
export function interfaceScaleOptions(current: number) {
  return [
    ...new Set([...Array.from({ length: 14 }, (_, i) => 75 + i * 5), current]),
  ].sort((a, b) => a - b);
}
export const stepInterfaceScale = (current: number, delta: number) =>
  Math.max(
    MIN_INTERFACE_SCALE,
    Math.min(MAX_INTERFACE_SCALE, Math.round(current / 5) * 5 + delta),
  );
