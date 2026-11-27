import { OrbitToggle as OrbitSwitch } from "./OrbitToggle";
import { TabBar, TabPanel, Slider } from "@kiridey/orbit-ui";
import {
  Activity,
  Bluetooth,
  Check,
  ChevronLeft,
  ChevronRight,
  Gauge,
  Headphones,
  Monitor,
  RotateCcw,
  Save,
  ShieldCheck,
  SlidersHorizontal,
  Speaker,
  Volume2,
  Waves,
  Zap,
} from "lucide-react";
import clsx from "clsx";
import {
  AUDIO_FREQUENCIES,
  AUDIO_PRESETS,
  AUDIO_PROFILES,
  defaultAudioSettings,
  formatDb,
} from "../audioSettings";
import type { AudioProfile, AudioSettings, State } from "../types";

type Props = {
  settings: AudioSettings;
  onChange: (value: AudioSettings) => void;
  onTab: (tab: "overview" | "equalizer") => void;
  state: State;
  notice: (message: string) => void;
};

const profileIcons = {
  monitor: Monitor,
  headphones: Headphones,
  bluetooth: Bluetooth,
  speaker: Speaker,
};

function Switch({
  value,
  label,
  onChange,
}: {
  value: boolean;
  label: string;
  onChange: () => void;
}) {
  return (
    <OrbitSwitch
      className="lad-toggle"
      label={label}
      checked={value}
      onChange={onChange}
    />
  );
}
function AudioTabs({
  active,
  onTab,
}: {
  active: "overview" | "equalizer";
  onTab: Props["onTab"];
}) {
  return (
    <div className="audio-tabs-orbit">
      <TabBar
        id="audio-settings"
        label="Настройки звука"
        value={active}
        onValueChange={(value) => onTab(value as typeof active)}
        material="solid"
        items={[
          { value: "overview", label: "Профиль", icon: <Gauge /> },
          {
            value: "equalizer",
            label: "Эквалайзер",
            icon: <SlidersHorizontal />,
          },
        ]}
      />
    </div>
  );
}
function SettingSlider({
  label,
  value,
  min,
  max,
  step,
  suffix = "",
  onChange,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  suffix?: string;
  onChange: (value: number) => void;
}) {
  return (
    <Slider
      label={label}
      value={value}
      min={min}
      max={max}
      step={step}
      unit={suffix}
      onValueChange={onChange}
    />
  );
}

export function AudioSettingsPage({
  settings,
  onChange,
  onTab,
  state,
  notice,
}: Props) {
  const profile =
    AUDIO_PROFILES.find((item) => item.id === settings.profile) ||
    AUDIO_PROFILES[0];
  const ProfileIcon = profileIcons[profile.icon];
  const set = (patch: Partial<AudioSettings>) =>
    onChange({ ...settings, ...patch });
  const choosePreset = (presetId: string) => {
    const preset = AUDIO_PRESETS.find((item) => item.id === presetId);
    if (preset)
      onChange({ ...settings, preset: preset.id, gains: [...preset.gains] });
  };
  return (
    <div className="audio-settings-page">
      <div className="page-heading audio-page-heading">
        <div>
          <span className="eyebrow">ЗВУК ЛАДА</span>
          <h1>Профиль звука</h1>
          <p>
            Один набор настроек для каждого выхода. Изменения применяются сразу
            и сохраняются локально.
          </p>
        </div>
        <div className="audio-master-toggle">
          <span>Обработка звука</span>
          <Switch
            value={settings.enabled}
            label="Обработка звука"
            onChange={() => set({ enabled: !settings.enabled })}
          />
        </div>
      </div>
      <AudioTabs active="overview" onTab={onTab} />
      <TabPanel id="audio-settings" value="overview">
        <section className="audio-hero-card">
          <div className="audio-hero-top">
            <div className="audio-icon-box">
              <ProfileIcon size={22} />
            </div>
            <div>
              <span className="eyebrow">АКТИВНЫЙ ВЫХОД</span>
              <h2>{profile.name}</h2>
              <p>{profile.detail}</p>
            </div>
            <span className="audio-live">
              <span /> Применяется сейчас
            </span>
          </div>
          <div className="audio-meter">
            <div className="audio-meter-scale">
              <span>-12</span>
              <span>-6</span>
              <span>0</span>
              <span>+6 dB</span>
            </div>
            <div className="audio-meter-track">
              <div
                className="audio-meter-fill"
                style={{
                  width: `${Math.max(24, Math.min(86, 58 + settings.preamp * 3))}%`,
                }}
              />
            </div>
            <div className="audio-meter-caption">
              <span>Предусиление</span>
              <strong>{formatDb(settings.preamp)}</strong>
            </div>
          </div>
        </section>
        <section className="audio-settings-section">
          <div className="section-title">
            <h2>Куда слушаем</h2>
            <span className="subtle-label">Профиль сохраняется отдельно</span>
          </div>
          <div className="audio-profile-grid">
            {AUDIO_PROFILES.map((item) => {
              const Icon = profileIcons[item.icon];
              return (
                <button
                  key={item.id}
                  className={clsx(
                    "audio-profile-card",
                    settings.profile === item.id && "active",
                  )}
                  onClick={() => set({ profile: item.id })}
                >
                  <span className="audio-profile-icon">
                    <Icon size={18} />
                  </span>
                  <span>
                    <strong>{item.name}</strong>
                    <small>{item.detail}</small>
                  </span>
                  {settings.profile === item.id && <Check size={18} />}
                </button>
              );
            })}
          </div>
        </section>
        <section className="audio-settings-section">
          <div className="section-title">
            <h2>Готовые пресеты</h2>
            <button
              className="text-action"
              onClick={() => {
                set({ preset: "custom" });
                notice("Можно настроить свой профиль на вкладке «Эквалайзер»");
              }}
            >
              Свой профиль <ChevronRight size={15} />
            </button>
          </div>
          <div className="audio-preset-grid">
            {AUDIO_PRESETS.slice(0, 4).map((preset) => (
              <button
                key={preset.id}
                className={clsx(
                  "audio-preset-card",
                  settings.preset === preset.id && "active",
                )}
                onClick={() => choosePreset(preset.id)}
              >
                <span className="audio-preset-graph">
                  {preset.gains.map((gain, index) => (
                    <i
                      key={index}
                      style={{
                        height: `${18 + Math.abs(gain) * 2.4}px`,
                        transform: `translateY(${gain > 0 ? -gain * 1.2 : 0}px)`,
                      }}
                    />
                  ))}
                </span>
                <strong>{preset.name}</strong>
                <small>{preset.description}</small>
                {settings.preset === preset.id && <Check size={16} />}
              </button>
            ))}
          </div>
        </section>
        <section className="audio-settings-section audio-two-columns">
          <div className="audio-control-card">
            <div className="audio-control-heading">
              <span className="audio-control-icon">
                <Volume2 size={18} />
              </span>
              <div>
                <strong>Уровень</strong>
                <small>Без клиппинга и внезапных пиков</small>
              </div>
            </div>
            <SettingSlider
              label="Предусиление"
              value={settings.preamp}
              min={-12}
              max={6}
              step={0.5}
              suffix=" dB"
              onChange={(value) => set({ preamp: value, preset: "custom" })}
            />
            <div className="audio-inline-toggle">
              <span>
                <strong>Лимитер пиков</strong>
                <small>Оставляет запас для громких записей</small>
              </span>
              <Switch
                value={settings.limiter}
                label="Лимитер пиков"
                onChange={() => set({ limiter: !settings.limiter })}
              />
            </div>
          </div>
          <div className="audio-control-card">
            <div className="audio-control-heading">
              <span className="audio-control-icon">
                <Waves size={18} />
              </span>
              <div>
                <strong>Характер</strong>
                <small>Мягкая коррекция без лишнего DSP</small>
              </div>
            </div>
            <div className="audio-inline-toggle">
              <span>
                <strong>Loudness</strong>
                <small>Поддержать бас и верх на тихой громкости</small>
              </span>
              <Switch
                value={settings.loudness}
                label="Loudness"
                onChange={() => set({ loudness: !settings.loudness })}
              />
            </div>
            <div className="audio-inline-toggle">
              <span>
                <strong>Ширина стерео</strong>
                <small>Осторожно: для наушников лучше 100%</small>
              </span>
              <strong className="audio-value">{settings.stereoWidth}%</strong>
            </div>
            <input
              aria-label="Ширина стерео"
              type="range"
              min="0"
              max="150"
              step="5"
              value={settings.stereoWidth}
              onChange={(event) =>
                set({ stereoWidth: Number(event.target.value) })
              }
            />
          </div>
        </section>
        <div className="audio-note">
          <ShieldCheck size={18} />
          <span>
            Лад хранит эти параметры на этом Mac. Для Станции и
            Bluetooth-профиля настройки остаются готовыми, а обработка
            применяется к локальному аудио в приложении.
          </span>
        </div>
      </TabPanel>
    </div>
  );
}

export function EqualizerPage({ settings, onChange, onTab, state }: Props) {
  const set = (patch: Partial<AudioSettings>) =>
    onChange({ ...settings, ...patch });
  const values = settings.gains;
  const setBand = (index: number, value: number) =>
    set({
      gains: values.map((gain, item) => (item === index ? value : gain)),
      preset: "custom",
    });
  const reset = () =>
    onChange({ ...defaultAudioSettings, profile: settings.profile });
  return (
    <div className="audio-settings-page equalizer-page">
      <div className="page-heading audio-page-heading">
        <div>
          <button className="audio-back-link" onClick={() => onTab("overview")}>
            <ChevronLeft size={17} /> Звук
          </button>
          <span className="eyebrow">ТОЧНАЯ НАСТРОЙКА</span>
          <h1>Эквалайзер</h1>
          <p>
            10 полос, реальный dB-диапазон и профильные параметры. Меняй — звук
            меняется сразу.
          </p>
        </div>
        <div className="audio-preset-select">
          <span>Пресет</span>
          <select
            value={settings.preset}
            onChange={(event) => {
              const preset = AUDIO_PRESETS.find(
                (item) => item.id === event.target.value,
              );
              if (preset)
                onChange({
                  ...settings,
                  preset: preset.id,
                  gains: [...preset.gains],
                });
            }}
          >
            <option value="custom">Свой профиль</option>
            {AUDIO_PRESETS.map((preset) => (
              <option key={preset.id} value={preset.id}>
                {preset.name}
              </option>
            ))}
          </select>
        </div>
      </div>
      <AudioTabs active="equalizer" onTab={onTab} />
      <TabPanel id="audio-settings" value="equalizer">
        <section className="equalizer-card">
          <div className="equalizer-toolbar">
            <div>
              <strong>
                {settings.preset === "custom"
                  ? "Свой профиль"
                  : AUDIO_PRESETS.find((item) => item.id === settings.preset)
                      ?.name}
              </strong>
              <small>
                {settings.enabled
                  ? "Обработка включена"
                  : "Обработка выключена"}{" "}
                · профиль{" "}
                {settings.profile === "mac" ? "Этот Mac" : settings.profile}
              </small>
            </div>
            <div className="equalizer-actions">
              <button className="icon-text-button" onClick={reset}>
                <RotateCcw size={16} /> Сбросить
              </button>
              <button
                className="icon-text-button primary"
                onClick={() => set({ preset: "custom" })}
              >
                <Save size={16} /> Сохранить как своё
              </button>
            </div>
          </div>
          <div className="eq-scale">
            <span>+12 dB</span>
            <span>+6</span>
            <span>0</span>
            <span>-6</span>
            <span>-12 dB</span>
          </div>
          <div className="equalizer-bands">
            {AUDIO_FREQUENCIES.map((frequency, index) => {
              const value = values[index] || 0;
              const top = `${50 - (value / 12) * 41.5}%`;
              return (
                <div className="eq-band" key={frequency}>
                  <input
                    className="eq-number"
                    aria-label={`${frequency} Hz значение`}
                    type="number"
                    min="-12"
                    max="12"
                    step="0.5"
                    value={value}
                    onChange={(event) => {
                      const next = Number(event.target.value);
                      if (Number.isFinite(next))
                        setBand(index, Math.max(-12, Math.min(12, next)));
                    }}
                  />
                  <div className="eq-rail">
                    <div className="eq-zero" />
                    <div
                      className="eq-active"
                      style={{
                        top: value >= 0 ? top : "50%",
                        height: `${(Math.abs(value) / 12) * 50}%`,
                      }}
                    />
                    <input
                      className="eq-range"
                      aria-label={`${frequency} Hz полоса`}
                      type="range"
                      min="-12"
                      max="12"
                      step="0.5"
                      value={value}
                      onChange={(event) =>
                        setBand(index, Number(event.target.value))
                      }
                    />
                  </div>
                  <strong>
                    {frequency >= 1000 ? `${frequency / 1000} k` : frequency}
                  </strong>
                  <small>Hz</small>
                </div>
              );
            })}
          </div>
          <div className="eq-summary">
            <span>
              <Activity size={15} />{" "}
              {values.filter((value) => value !== 0).length} полос изменено
            </span>
            <span>Максимум {formatDb(Math.max(...values))}</span>
            <span>Минимум {formatDb(Math.min(...values))}</span>
          </div>
        </section>
        <section className="audio-settings-section audio-two-columns">
          <div className="audio-control-card">
            <div className="audio-control-heading">
              <span className="audio-control-icon">
                <Zap size={18} />
              </span>
              <div>
                <strong>Быстрые параметры</strong>
                <small>Можно менять без выхода со страницы</small>
              </div>
            </div>
            <SettingSlider
              label="Предусиление"
              value={settings.preamp}
              min={-12}
              max={6}
              step={0.5}
              suffix=" dB"
              onChange={(value) => set({ preamp: value, preset: "custom" })}
            />
            <div className="audio-inline-toggle">
              <span>
                <strong>Лимитер</strong>
                <small>Защита от цифрового клиппинга</small>
              </span>
              <Switch
                value={settings.limiter}
                label="Лимитер"
                onChange={() => set({ limiter: !settings.limiter })}
              />
            </div>
          </div>
          <div className="audio-control-card">
            <div className="audio-control-heading">
              <span className="audio-control-icon">
                <Waves size={18} />
              </span>
              <div>
                <strong>Дополнительно</strong>
                <small>Эффекты выключены по умолчанию</small>
              </div>
            </div>
            <div className="audio-inline-toggle">
              <span>
                <strong>Loudness</strong>
                <small>Тихое прослушивание</small>
              </span>
              <Switch
                value={settings.loudness}
                label="Loudness"
                onChange={() => set({ loudness: !settings.loudness })}
              />
            </div>
            <div className="audio-inline-toggle">
              <span>
                <strong>Реверберация</strong>
                <small>Экспериментальный эффект</small>
              </span>
              <Switch
                value={settings.reverb}
                label="Реверберация"
                onChange={() => set({ reverb: !settings.reverb })}
              />
            </div>
          </div>
        </section>
        <div className="audio-note">
          <ShieldCheck size={18} />
          <span>
            Изменения применяются к локальному воспроизведению на этом Mac через
            Web Audio. Стеклянный интерфейс не является картинкой — ползунки
            реально меняют фильтры.
          </span>
        </div>
      </TabPanel>
    </div>
  );
}
