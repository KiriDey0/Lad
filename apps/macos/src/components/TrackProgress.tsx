// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { duration } from "./ui";
import { Scrubber } from "@kiridey/orbit-ui";

export function TrackProgress({
  position,
  length,
  resetKey,
  seek,
  playing = false,
}: {
  position: number;
  length: number;
  resetKey: string;
  seek: (value: number) => unknown | Promise<unknown>;
  playing?: boolean;
}) {
  const root = useRef<HTMLDivElement>(null);
  const [preview, setPreview] = useState<number | null>(null);
  const dragging = useRef(false);
  const desired = useRef<number | null>(null);
  const previewRef = useRef<number | null>(null);
  const live = useRef({ position, length });
  live.current = { position, length };
  const pending = useRef<{ value: number; id: number } | null>(null);
  const queued = useRef<{ value: number; id: number } | null>(null);
  const sending = useRef(false);
  const sequence = useRef(0);
  const timeout = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const onSeek = useRef(seek);
  onSeek.current = seek;
  const show = (value: number | null) => {
    previewRef.current = value;
    setPreview(value);
  };
  const clearPending = () => {
    clearTimeout(timeout.current);
    pending.current = null;
    show(null);
  };
  const cancel = () => {
    if (!dragging.current) return;
    dragging.current = false;
    desired.current = null;
    show(pending.current?.value ?? null);
  };
  const commit = (value: number) => {
    const command = {
      value: Math.min(live.current.length, Math.max(0, value)),
      id: ++sequence.current,
    };
    pending.current = queued.current = command;
    show(command.value);
    clearTimeout(timeout.current);
    timeout.current = setTimeout(() => {
      if (pending.current?.id === command.id) clearPending();
    }, 3000);
    if (sending.current) return;
    sending.current = true;
    void (async () => {
      try {
        while (queued.current) {
          const next = queued.current;
          queued.current = null;
          try {
            await onSeek.current(next.value);
          } catch {
            if (pending.current?.id === next.id) clearPending();
          }
        }
      } finally {
        sending.current = false;
      }
    })();
  };
  const finish = () => {
    if (!dragging.current) return;
    const value = desired.current;
    dragging.current = false;
    desired.current = null;
    if (value !== null) commit(value);
  };
  useEffect(() => {
    dragging.current = false;
    desired.current = queued.current = null;
    clearPending();
    return () => {
      clearTimeout(timeout.current);
      queued.current = null;
    };
  }, [resetKey]);
  useEffect(() => {
    if (
      !dragging.current &&
      pending.current &&
      Math.abs(position - pending.current.value) < 1.5
    )
      clearPending();
  }, [position]);
  // Keep Orbit's range control and accessibility; adapt its progress artwork.
  // Keep wavelength in CSS pixels, so resizing never stretches the oscillation.
  useLayoutEffect(() => {
    const art = root.current?.querySelector<HTMLElement>(
      ".orbit-scrubber__art",
    );
    const svg = art?.querySelector("svg"),
      path = svg?.querySelector("path");
    if (!art || !svg || !path) return;
    const draw = () => {
      const period = 32;
      const width = (Math.ceil(art.clientWidth / period) + 2) * period;
      const points = Array.from({ length: width / 2 + 1 }, (_, index) => {
        const x = index * 2;
        return `${index ? "L" : "M"}${x} ${(10 + 2.4 * Math.sin((x / period) * Math.PI * 2)).toFixed(3)}`;
      });
      svg.setAttribute("width", String(width));
      svg.setAttribute("viewBox", `0 0 ${width} 20`);
      path.setAttribute("d", points.join(" "));
    };
    draw();
    const observer = new ResizeObserver(draw);
    observer.observe(art);
    return () => observer.disconnect();
  }, [resetKey]);
  useEffect(() => {
    window.addEventListener("pointerup", finish);
    window.addEventListener("pointercancel", cancel);
    window.addEventListener("blur", cancel);
    return () => {
      window.removeEventListener("pointerup", finish);
      window.removeEventListener("pointercancel", cancel);
      window.removeEventListener("blur", cancel);
    };
  }, []);
  return (
    <div
      ref={root}
      className="progress-row"
      onPointerDownCapture={(event) => {
        if (
          event.isPrimary &&
          event.button === 0 &&
          (event.target as HTMLElement).matches('input[type="range"]')
        ) {
          dragging.current = true;
          desired.current = null;
        }
      }}
      onKeyDownCapture={(event) => {
        if (
          !(event.target as HTMLElement).matches('input[type="range"]') ||
          !length
        )
          return;
        const current = previewRef.current ?? live.current.position;
        const step = Math.max(5, live.current.length / 10);
        const value = (
          {
            ArrowLeft: current - 5,
            ArrowDown: current - 5,
            ArrowRight: current + 5,
            ArrowUp: current + 5,
            Home: 0,
            End: live.current.length,
            PageDown: current - step,
            PageUp: current + step,
          } as Record<string, number>
        )[event.key];
        if (value !== undefined) {
          event.preventDefault();
          event.stopPropagation();
          commit(value);
        }
      }}
      onPointerUpCapture={finish}
      onPointerCancelCapture={cancel}
      onBlurCapture={cancel}
    >
      <Scrubber
        key={resetKey}
        label="Позиция трека"
        time={preview ?? position}
        duration={length}
        playing={playing && preview === null}
        disabled={!length}
        onSeek={(value) => {
          if (dragging.current) {
            desired.current = value;
            show(value);
          } else commit(value);
        }}
      />
      <span className="progress-total">{duration(length)}</span>
    </div>
  );
}
