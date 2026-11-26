// SPDX-License-Identifier: AGPL-3.0-only
import { resolveOrbitTheme } from "@kiridey/orbit-ui";
import type { CSSProperties } from "react";
export type Theme = {
  base: "dark" | "oled";
  mode: "default" | "accent" | "tinted";
  seed: string;
  transparency: boolean;
  lyricOffset: number;
  scale: number;
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
  waveIntensity: 100,
  waveSmoothing: 50,
  waveQuality: "auto",
};
export function loadTheme(): Theme {
  try {
    const theme = {
      ...defaultTheme,
      ...JSON.parse(localStorage.getItem("orbit-theme") || "{}"),
    };
    theme.scale =
      typeof theme.scale === "number" && Number.isFinite(theme.scale)
        ? Math.max(75, Math.min(115, Math.round(theme.scale / 5) * 5))
        : defaultTheme.scale;
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
export function orbitTokens(theme: Theme): CSSProperties {
  return {
    ...resolveOrbitTheme({
      style: "orbit",
      base: theme.base,
      colorMode: theme.mode,
      seed: theme.seed,
    }).resolvedTokens,
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
    void window.lad.setScale(theme.scale / 100).catch(() => {});
  else {
    root.style.zoom = String(theme.scale / 100);
    root.style.setProperty(
      "--scaled-viewport-height",
      `${10000 / theme.scale}dvh`,
    );
  }
  localStorage.setItem("orbit-theme", JSON.stringify(theme));
}
