// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useRef, useState, type PointerEvent } from "react";
import {
  Button,
  Popover,
  PopoverClose,
  Slider,
  TextField,
} from "@kiridey/orbit-ui";
import { Check, ChevronDown, X } from "lucide-react";

const presets = [
  ["Лавандовый", "#BFCAF5"],
  ["Шалфей", "#A8C8B3"],
  ["Песочный", "#DECBA1"],
  ["Розовый", "#E7A8B0"],
  ["Голубой", "#A8CFE8"],
  ["Сиреневый", "#CBB5E9"],
  ["Персиковый", "#E8B59D"],
  ["Нейтральный", "#D9DDE4"],
];
const rgb = (hex: string) =>
  [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
const hex = (values: number[]) =>
  "#" +
  values
    .map((v) => Math.round(v).toString(16).padStart(2, "0"))
    .join("")
    .toUpperCase();
function hsv(color: string, fallback: number) {
  const [r, g, b] = rgb(color).map((v) => v / 255);
  const max = Math.max(r, g, b),
    min = Math.min(r, g, b),
    d = max - min;
  const h = !d
    ? fallback
    : ((max === r
        ? (g - b) / d
        : max === g
          ? (b - r) / d + 2
          : (r - g) / d + 4) *
        60 +
        360) %
      360;
  return { h, s: max ? d / max : 0, v: max };
}
function fromHsv(h: number, s: number, v: number) {
  const c = v * s,
    x = c * (1 - Math.abs(((h / 60) % 2) - 1)),
    m = v - c;
  const bands = [
    [c, x, 0],
    [x, c, 0],
    [0, c, x],
    [0, x, c],
    [x, 0, c],
    [c, 0, x],
  ];
  return hex(bands[Math.floor((h % 360) / 60)].map((n) => (n + m) * 255));
}

export function AccentColorPicker({
  value,
  onChange,
}: {
  value: string;
  onChange: (value: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [hue, setHue] = useState(() => hsv(value, 230).h);
  const [draft, setDraft] = useState(value.toUpperCase());
  const [channels, setChannels] = useState(() => rgb(value).map(String));
  const [hexError, setHexError] = useState(false);
  const [rgbError, setRgbError] = useState(false);
  const pointer = useRef<number | null>(null);
  const color = hsv(value, hue);
  const latest = useRef({ color, onChange });
  latest.current = { color, onChange };
  useEffect(() => {
    setDraft(value.toUpperCase());
    setChannels(rgb(value).map(String));
    setHexError(false);
    setRgbError(false);
    setHue((previous) => hsv(value, previous).h);
  }, [value]);
  const move = (event: PointerEvent<HTMLDivElement>) => {
    if (pointer.current !== event.pointerId) return;
    const box = event.currentTarget.getBoundingClientRect();
    const s = Math.max(0, Math.min(1, (event.clientX - box.left) / box.width));
    const v =
      1 - Math.max(0, Math.min(1, (event.clientY - box.top) / box.height));
    latest.current.onChange(fromHsv(latest.current.color.h, s, v));
  };
  return (
    <Popover
      label="Цвет акцента"
      open={open}
      onOpenChange={setOpen}
      align="end"
      trigger={
        <Button
          variant="secondary"
          className="lad-color-trigger"
          aria-label="Выбрать цвет акцента"
        >
          <span
            className="lad-color-chip"
            style={{ background: value }}
            aria-hidden="true"
          />
          <span>{value.toUpperCase()}</span>
          <ChevronDown size={16} />
        </Button>
      }
    >
      <div className="lad-color-picker">
        <div className="lad-color-heading">
          <div>
            <strong>Цвет акцента</strong>
            <p>Изменения видны сразу</p>
          </div>
          <PopoverClose asChild>
            <Button iconOnly variant="ghost" aria-label="Закрыть палитру">
              <X size={18} />
            </Button>
          </PopoverClose>
        </div>
        <div
          className="lad-color-plane"
          aria-hidden="true"
          style={{ backgroundColor: `hsl(${color.h} 100% 50%)` }}
          onPointerDown={(event) => {
            if (!event.isPrimary || event.button !== 0) return;
            pointer.current = event.pointerId;
            event.currentTarget.setPointerCapture(event.pointerId);
            move(event);
          }}
          onPointerMove={move}
          onPointerUp={(event) => {
            move(event);
            pointer.current = null;
          }}
          onPointerCancel={() => {
            pointer.current = null;
          }}
          onLostPointerCapture={() => {
            pointer.current = null;
          }}
        >
          <span
            className="lad-color-cursor"
            style={{
              left: `${color.s * 100}%`,
              top: `${(1 - color.v) * 100}%`,
              background: value,
            }}
          />
        </div>
        <div className="lad-color-hue">
          <Slider
            label="Цветовой тон"
            min={0}
            max={359}
            step={1}
            unit="°"
            value={Math.round(color.h)}
            onValueChange={(h) => {
              setHue(h);
              onChange(fromHsv(h, color.s, color.v));
            }}
          />
        </div>
        <div
          className="lad-color-presets"
          role="group"
          aria-label="Готовые цвета"
        >
          {presets.map(([name, seed]) => (
            <Button
              key={seed}
              iconOnly
              variant="ghost"
              aria-label={name}
              aria-pressed={value.toUpperCase() === seed}
              className="lad-color-preset"
              onClick={() => onChange(seed)}
            >
              <span style={{ background: seed }}>
                {value.toUpperCase() === seed && <Check size={16} />}
              </span>
            </Button>
          ))}
        </div>
        <TextField
          label="HEX"
          value={draft}
          spellCheck={false}
          autoComplete="off"
          maxLength={7}
          error={hexError ? "Нужны # и 6 символов: 0–9, A–F" : undefined}
          onChange={(event) => {
            const next = event.target.value;
            setDraft(next);
            setHexError(false);
            if (/^#?[\da-f]{6}$/i.test(next))
              onChange(
                (next.startsWith("#") ? next : "#" + next).toUpperCase(),
              );
          }}
          onBlur={() => setHexError(!/^#?[\da-f]{6}$/i.test(draft))}
        />
        <div className="lad-color-rgb" role="group" aria-label="RGB">
          {channels.map((channel, index) => (
            <TextField
              key={index}
              label={["R", "G", "B"][index]}
              value={channel}
              inputMode="numeric"
              maxLength={3}
              autoComplete="off"
              onChange={(event) => {
                const next = channels.map((n, i) =>
                  i === index ? event.target.value : n,
                );
                setChannels(next);
                setRgbError(false);
                if (next.every((n) => /^\d{1,3}$/.test(n) && Number(n) <= 255))
                  onChange(hex(next.map(Number)));
              }}
              onBlur={() =>
                setRgbError(
                  channels.some((n) => !/^\d{1,3}$/.test(n) || Number(n) > 255),
                )
              }
            />
          ))}
        </div>
        {rgbError && (
          <p className="lad-color-error" role="alert">
            Значения RGB — от 0 до 255
          </p>
        )}
        <p className="lad-color-note">
          Оттенок адаптируется, чтобы текст и кнопки оставались читаемыми.
        </p>
      </div>
    </Popover>
  );
}
