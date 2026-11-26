import type { AudioSettings, AudioPreset, AudioProfile } from "./types";

export const AUDIO_FREQUENCIES = [31.5, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];

export const AUDIO_PRESETS: AudioPreset[] = [
  { id: "flat", name: "Ровный", description: "Без окраски", gains: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0] },
  { id: "lad-warm", name: "Тёплый свет", description: "Мягкий низ и спокойный верх", gains: [2.5, 2, 1, 0, -0.5, 0, 0.5, 1, 1, 0.5] },
  { id: "rock", name: "Реп / рок", description: "Ударный низ и гитары", gains: [3.5, 3, 1.5, -1, -1.5, 0, 1.5, 2, 2, 1.5] },
  { id: "night", name: "Ночной", description: "Сдержанный максимум для тихого прослушивания", gains: [-2, -1, 0, 1, 1.5, 1, 0.5, -0.5, -1, -1.5] },
  { id: "voice", name: "Голос", description: "Разборчивее подкасты и вокал", gains: [-2, -1, 0, 1.5, 2, 2.5, 2, 1, 0, -1] },
];

export const AUDIO_PROFILES: AudioProfile[] = [
  { id: "mac", name: "Этот Mac", detail: "Встроенный аудиовыход", icon: "monitor" },
  { id: "headphones", name: "Наушники", detail: "Профиль для близкого прослушивания", icon: "headphones" },
  { id: "bluetooth", name: "Bluetooth", detail: "С запасом по пикам и задержке", icon: "bluetooth" },
  { id: "station", name: "Станция", detail: "Профиль передачи на колонку", icon: "speaker" },
];

export const defaultAudioSettings: AudioSettings = {
  enabled: true,
  profile: "mac",
  preset: "lad-warm",
  gains: [...AUDIO_PRESETS[1].gains],
  preamp: -1,
  limiter: true,
  loudness: false,
  stereoWidth: 100,
  reverb: false,
};

export function loadAudioSettings(): AudioSettings {
  try {
    const raw = JSON.parse(localStorage.getItem("lad-audio-settings") || "null");
    if (!raw || typeof raw !== "object") return defaultAudioSettings;
    const gains = Array.isArray(raw.gains) && raw.gains.length === 10
      ? raw.gains.map((value: unknown) => Number.isFinite(Number(value)) ? Math.max(-12, Math.min(12, Number(value))) : 0)
      : defaultAudioSettings.gains;
    return {
      ...defaultAudioSettings,
      ...raw,
      enabled: raw.enabled !== false,
      gains,
      preamp: Number.isFinite(Number(raw.preamp)) ? Math.max(-12, Math.min(6, Number(raw.preamp))) : defaultAudioSettings.preamp,
      stereoWidth: Number.isFinite(Number(raw.stereoWidth)) ? Math.max(0, Math.min(150, Number(raw.stereoWidth))) : defaultAudioSettings.stereoWidth,
      profile: AUDIO_PROFILES.some((item) => item.id === raw.profile) ? raw.profile : defaultAudioSettings.profile,
      preset: AUDIO_PRESETS.some((item) => item.id === raw.preset) ? raw.preset : "custom",
    };
  } catch {
    return defaultAudioSettings;
  }
}

export function saveAudioSettings(settings: AudioSettings) {
  localStorage.setItem("lad-audio-settings", JSON.stringify(settings));
}

export function formatDb(value: number) {
  return `${value > 0 ? "+" : ""}${value.toFixed(1)} dB`;
}
