// SPDX-License-Identifier: AGPL-3.0-only
import { converter, formatHex, clampChroma } from "culori";
export type Theme = {
  base: "dark" | "oled";
  mode: "default" | "accent" | "tinted";
  seed: string;
  transparency: boolean;
  lyricOffset: number;
  scale: number;
  waveIntensity: number;
  waveSmoothing: number;
};
export const defaultTheme: Theme = {
  base: "dark",
  mode: "default",
  seed: "#E2E6EC",
  transparency: true,
  lyricOffset: 0,
  scale: 85,
  waveIntensity: 100,
  waveSmoothing: 50,
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
    theme.waveIntensity = Number.isFinite(theme.waveIntensity) ? Math.max(0, Math.min(100, theme.waveIntensity)) : 100;
    theme.waveSmoothing = Number.isFinite(theme.waveSmoothing) ? Math.max(0, Math.min(100, theme.waveSmoothing)) : 50;
    return theme;
  } catch {
    return defaultTheme;
  }
}
export function applyTheme(theme: Theme) {
  const root = document.documentElement;
  const vars: Record<string, string> = {
    bg: "#0C0D0F",
    surface: "#17191D",
    raised: "#282B31",
    border: "#FFFFFF24",
    text: "#F1F3F5",
    muted: "#AAAFB9",
    accent: "#E2E6EC",
    tonal: "#30353D",
    "on-accent": "#0C0D0F",
    danger: "#E7A8B0",
    success: "#ACCEBC",
    warning: "#DECBA1",
    focus: "#E2E6EC",
    "accent-pressed": "#C0C4CB",
  };
  if (theme.mode !== "default") {
    const seed = converter("oklch")(theme.seed);
    if (seed) {
      const c = Math.min(seed.c || 0, 0.13),
        h = c < 0.008 ? 0 : seed.h || 0;
      const color = (l: number, chroma: number) =>
        formatHex(
          clampChroma({ mode: "oklch", l, c: chroma, h }, "oklch", "rgb"),
        ) || "#E2E6EC";
      vars.accent = color(0.82, c);
      vars.tonal = color(0.32, Math.min(c, 0.06));
      vars.focus = vars.accent;
      vars["accent-pressed"] = color(0.76, c);
      if (theme.mode === "tinted")
        for (const [key, chroma] of [
          ["bg", 0.014],
          ["surface", 0.022],
          ["raised", 0.03],
        ] as const) {
          const l = converter("oklch")(vars[key])?.l || 0.15;
          vars[key] = color(l, Math.min(c, chroma));
        }
    }
  }
  if (theme.base === "oled") vars.bg = "#000000";
  vars["accent-soft"] = vars.accent + "22";
  vars["on-accent-soft"] = "#0C0D0F1A";
  vars["glass-fill"] = vars.surface + (theme.transparency ? "80" : "");
  vars["glass-popup-fill"] = vars.surface + (theme.transparency ? "BF" : "");
  vars["glass-border"] = "#FFFFFF30";
  for (const [key, value] of Object.entries(vars))
    root.style.setProperty("--" + key, value);
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
