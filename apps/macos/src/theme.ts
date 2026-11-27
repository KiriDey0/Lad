// SPDX-License-Identifier: AGPL-3.0-only
import {
  interfaceZoom,
  INTERFACE_SCALE_BASIS,
  MIN_INTERFACE_SCALE,
  MAX_INTERFACE_SCALE,
} from "./interfaceScale";
import { resolveOrbitTheme } from "@kiridey/orbit-ui";
import type { CSSProperties } from "react";
export type Theme = {
  base: "dark" | "oled";
  mode: "default" | "accent" | "tinted";
  seed: string;
  transparency: boolean;
  lyricOffset: number;
  scale: number;
  scaleBasis: 85;
  waveIntensity: number;
  waveSmoothing: number;
  waveQuality: "auto" | "eco" | "smooth";
};
export const defaultTheme: Theme = {
  base: "dark",
  mode: "default",
  seed: "#BFCAF5",
  transparency: true,
  lyricOffset: 0,
  scale: 100,
  scaleBasis: INTERFACE_SCALE_BASIS,
  waveIntensity: 100,
  waveSmoothing: 50,
  waveQuality: "auto",
};
export function loadTheme(): Theme {
  try {
    const saved = JSON.parse(localStorage.getItem("orbit-theme") || "{}");
    const theme = { ...defaultTheme, ...saved };
    // Migrate the old absolute zoom without changing the user's actual size.
    const previous =
      typeof saved.scale === "number" && Number.isFinite(saved.scale)
        ? saved.scale
        : 85;
    const display =
      saved.scaleBasis === INTERFACE_SCALE_BASIS ? previous : previous / 0.85;
    theme.scale = Math.max(
      MIN_INTERFACE_SCALE,
      Math.min(MAX_INTERFACE_SCALE, display),
    );
    theme.scaleBasis = INTERFACE_SCALE_BASIS;
    theme.waveIntensity = Number.isFinite(theme.waveIntensity)
      ? Math.max(0, Math.min(100, theme.waveIntensity))
      : 100;
    theme.waveSmoothing = Number.isFinite(theme.waveSmoothing)
      ? Math.max(0, Math.min(100, theme.waveSmoothing))
      : 50;
    if (!["auto", "eco", "smooth"].includes(theme.waveQuality))
      theme.waveQuality = "auto";
    return theme;
  } catch {
    return defaultTheme;
  }
}
// Orbit's resolver returns palette deltas; reset every color it can change.
const orbitColorDefaults: Record<string, string> = {
  "--orbit-canvas": "#000000",
  "--orbit-surface": "#131417",
  "--orbit-field": "#16171a",
  "--orbit-raised": "#202227",
  "--orbit-elevated": "#282b32",
  "--orbit-accent": "#bfcaf5",
  "--orbit-hover": "#cfd8ff",
  "--orbit-pressed": "#a4b1e4",
  "--orbit-tonal": "#292f46",
  "--orbit-on-tonal": "#dce4ff",
  "--orbit-row-selected": "#222631",
};
export function orbitTokens(theme: Theme): CSSProperties {
  const colors: Record<string, string> = {
    ...orbitColorDefaults,
    ...(resolveOrbitTheme({
      style: "orbit",
      base: theme.base,
      colorMode: theme.mode,
      seed: theme.seed,
    }).resolvedTokens as Record<string, string>),
  };
  // Keep OLED truly black in every color mode, and Dark visibly graphite.
  colors["--orbit-canvas"] =
    theme.base === "oled"
      ? "#000000"
      : theme.mode === "tinted"
        ? `color-mix(in srgb,#111318 85%,${colors["--orbit-tonal"]} 15%)`
        : "#111318";
  if (theme.base === "dark") {
    for (const role of ["surface", "field", "raised", "elevated"])
      colors[`--orbit-${role}`] =
        `color-mix(in srgb,${colors[`--orbit-${role}`]} 94%,white 6%)`;
  }
  return {
    ...colors,
    "--orbit-glass": "color-mix(in srgb,var(--orbit-elevated) 64%,transparent)",
    "--orbit-mobile-glass":
      "color-mix(in srgb,var(--orbit-elevated) 72%,transparent)",
    "--orbit-action-glass":
      "color-mix(in srgb,var(--orbit-elevated) 48%,transparent)",
    ...(!theme.transparency
      ? {
          "--orbit-glass": "var(--orbit-elevated)",
          "--orbit-mobile-glass": "var(--orbit-elevated)",
          "--orbit-action-glass": "var(--orbit-elevated)",
        }
      : {}),
  } as CSSProperties;
}
export function applyTheme(theme: Theme) {
  const root = document.documentElement;
  for (const [name, value] of Object.entries(orbitTokens(theme)))
    root.style.setProperty(name, String(value));
  const roles: Record<string, string> = {
    bg: "canvas",
    surface: "surface",
    raised: "raised",
    border: "rim",
    text: "text",
    muted: "muted",
    accent: "accent",
    tonal: "tonal",
    "on-accent": "on-accent",
    danger: "danger",
    success: "success",
    warning: "warning",
    focus: "focus",
    "accent-pressed": "pressed",
    "glass-fill": "action-glass",
    "glass-popup-fill": "elevated",
    "glass-border": "rim",
  };
  for (const [name, role] of Object.entries(roles))
    root.style.setProperty("--" + name, "var(--orbit-" + role + ")");
  root.style.setProperty(
    "--accent-soft",
    "color-mix(in srgb,var(--orbit-accent) 14%,transparent)",
  );
  root.style.setProperty(
    "--on-accent-soft",
    "color-mix(in srgb,var(--orbit-on-accent) 12%,transparent)",
  );
  root.dataset.transparency = String(theme.transparency);
  if (window.lad?.setScale)
    void window.lad.setScale(interfaceZoom(theme.scale)).catch(() => {});
  else {
    root.style.zoom = String(interfaceZoom(theme.scale));
    root.style.setProperty(
      "--scaled-viewport-height",
      `${100 / interfaceZoom(theme.scale)}dvh`,
    );
  }
  localStorage.setItem("orbit-theme", JSON.stringify(theme));
}
