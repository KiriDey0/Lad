// SPDX-License-Identifier: AGPL-3.0-only
import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
  type PointerEvent,
} from "react";
import { Heart, LoaderCircle, Music2, Pause, Play } from "lucide-react";
import { Art, IconButton } from "./ui";
import { loadCoverColors } from "../wave/coverColors";
import type { State, Track } from "../types";

type Action = <T = unknown>(
  method: string,
  params?: Record<string, unknown>,
) => Promise<T | undefined>;
const occurrence = (track?: Track | null) =>
  track?.queueEntryId || track?.id || "";
function useCoverTone(cover?: string | null) {
  const [tone, setTone] = useState("rgb(213 225 238)");
  useEffect(() => {
    let cancelled = false;
    void loadCoverColors(cover).then(({ colors }) => {
      if (!cancelled) setTone(`rgb(${colors[0].map(Math.round).join(" ")})`);
    });
    return () => {
      cancelled = true;
    };
  }, [cover]);
  return tone;
}
function neighbor(state: State, direction: number) {
  const { tracks, index, repeat } = state.queue;
  for (let step = 1; step < tracks.length; step++) {
    let target = index + direction * step;
    if (target < 0 || target >= tracks.length) {
      if (repeat !== "all") return null;
      target = ((target % tracks.length) + tracks.length) % tracks.length;
    }
    const track = tracks[target];
    if (track.available !== false && !(state.blockExplicit && track.explicit))
      return { track, index: target };
  }
  return null;
}
function TrackCard({
  track,
  current,
  progress,
  playing,
  busy,
  onOpen,
  onLike,
  onToggle,
}: {
  track?: Track | null;
  current: boolean;
  progress: number;
  playing: boolean;
  busy: boolean;
  onOpen: () => void;
  onLike: () => void;
  onToggle: () => void;
}) {
  const color = useCoverTone(track?.cover);
  return (
    <div
      className="mobile-track-card"
      data-current={current}
      data-empty={!track || undefined}
      aria-hidden={!current || undefined}
      style={{ "--mobile-card-color": color } as CSSProperties}
    >
      <div
        className="mobile-track-fill"
        aria-hidden="true"
        style={{ width: `${progress}%` }}
      />
      <button
        className="mobile-track-open"
        onClick={onOpen}
        tabIndex={current ? 0 : -1}
        disabled={!track}
        aria-label={
          track
            ? `Сейчас играет: ${track.title}, ${track.artist}. Открыть текст`
            : "Выбери музыку"
        }
      >
        {track ? (
          <Art item={track} size="sm" />
        ) : (
          <span className="mobile-idle-art">
            <Music2 size={22} />
          </span>
        )}
        <span className="mobile-track-name">
          <strong>{track?.title || "Твоя музыка ждёт"}</strong>
          <small>{track?.artist || "Выбери, с чего начать"}</small>
        </span>
      </button>
      <IconButton
        className="mobile-like"
        icon={Heart}
        label={track?.liked ? "Убрать из любимых" : "Добавить в любимые"}
        active={!!track?.liked}
        disabled={!track || !current}
        tabIndex={current ? 0 : -1}
        onClick={onLike}
        data-mobile-action="true"
      />
      <IconButton
        className="mobile-play"
        icon={busy ? LoaderCircle : playing ? Pause : Play}
        label={playing ? "Пауза" : "Продолжить"}
        disabled={!track || !current || busy}
        tabIndex={current ? 0 : -1}
        onClick={onToggle}
        data-mobile-action="true"
      />
    </div>
  );
}
export function MobileDock({
  state,
  act,
  onOpen,
  onLike,
  children,
}: {
  state: State;
  act: Action;
  onOpen: () => void;
  onLike: () => void;
  children: ReactNode;
}) {
  const track = state.playback.track;
  const tone = useCoverTone(track?.cover);
  const key = occurrence(track);
  const latest = useRef(state);
  latest.current = state;
  const viewport = useRef<HTMLDivElement>(null);
  const strip = useRef<HTMLDivElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const gesture = useRef<{
    id: number;
    x: number;
    y: number;
    time: number;
    dx: number;
    axis: "pending" | "horizontal" | "vertical";
    scale: number;
  } | null>(null);
  const suppressClick = useRef(false);
  const phaseRef = useRef("idle");
  const [phase, setPhase] = useState("idle");
  const [position, setPosition] = useState(state.playback.position);
  const updatePhase = (value: string) => {
    phaseRef.current = value;
    setPhase(value);
  };
  const reset = () => {
    clearTimeout(timer.current);
    gesture.current = null;
    if (strip.current) strip.current.style.setProperty("--swipe-x", "0px");
    updatePhase("idle");
  };
  useEffect(() => {
    reset();
    return () => clearTimeout(timer.current);
  }, [key, state.queue.index]);
  useEffect(() => {
    const update = () => {
      const p = latest.current.playback;
      const elapsed = p.playing
        ? Math.max(0, (Date.now() - p.positionAt) / 1000)
        : 0;
      setPosition(
        Math.min(
          p.duration,
          p.position +
            (latest.current.output === "station"
              ? Math.min(4, elapsed)
              : elapsed),
        ),
      );
    };
    update();
    const clock = setInterval(update, 250);
    return () => clearInterval(clock);
  }, [
    state.playback.position,
    state.playback.playing,
    state.playback.positionAt,
  ]);
  const previous = neighbor(state, -1),
    next = neighbor(state, 1);
  const stepWidth = () => (viewport.current?.clientWidth || 320) - 24;
  const settle = (direction: number) => {
    if (phaseRef.current === "settling" || phaseRef.current === "awaiting")
      return;
    const target = direction ? neighbor(latest.current, direction) : null;
    const startKey = occurrence(latest.current.playback.track);
    gesture.current = null;
    updatePhase("settling");
    strip.current?.style.setProperty(
      "--swipe-x",
      `${target ? -direction * stepWidth() : 0}px`,
    );
    timer.current = setTimeout(
      async () => {
        if (!target || occurrence(latest.current.playback.track) !== startKey) {
          reset();
          return;
        }
        updatePhase("awaiting");
        // Queue-entry identity also handles duplicate tracks and reordering during a gesture.
        const index = latest.current.queue.tracks.findIndex(
          (item) => occurrence(item) === occurrence(target.track),
        );
        if (index >= 0)
          await act("queue", {
            action: "jump",
            index,
            entryId: target.track.queueEntryId,
          });
        reset();
      },
      matchMedia("(prefers-reduced-motion: reduce)").matches ? 0 : 220,
    );
  };
  const down = (e: PointerEvent<HTMLDivElement>) => {
    if (!e.isPrimary || e.button !== 0 || phaseRef.current !== "idle") return;
    suppressClick.current = false;
    if (!track || (e.target as HTMLElement).closest("[data-mobile-action]"))
      return;
    gesture.current = {
      id: e.pointerId,
      x: e.clientX,
      y: e.clientY,
      time: performance.now(),
      dx: 0,
      axis: "pending",
      scale:
        e.currentTarget.getBoundingClientRect().width /
          e.currentTarget.offsetWidth || 1,
    };
  };
  const move = (e: PointerEvent<HTMLDivElement>) => {
    const g = gesture.current;
    if (!g || g.id !== e.pointerId) return;
    const x = (e.clientX - g.x) / g.scale,
      y = (e.clientY - g.y) / g.scale;
    if (g.axis === "pending" && Math.max(Math.abs(x), Math.abs(y)) >= 6) {
      g.axis = Math.abs(x) > Math.abs(y) * 1.15 ? "horizontal" : "vertical";
      if (g.axis === "horizontal") {
        e.currentTarget.setPointerCapture(e.pointerId);
        updatePhase("dragging");
        suppressClick.current = true;
      }
    }
    if (g.axis !== "horizontal") return;
    e.preventDefault();
    const available = x < 0 ? next : previous;
    g.dx = Math.max(
      -stepWidth(),
      Math.min(stepWidth(), available ? x : x * 0.18),
    );
    strip.current?.style.setProperty("--swipe-x", `${g.dx}px`);
  };
  const up = (e: PointerEvent<HTMLDivElement>, cancelled = false) => {
    const g = gesture.current;
    if (!g || g.id !== e.pointerId) return;
    if (g.axis !== "horizontal") {
      gesture.current = null;
      return;
    }
    const distance = Math.abs(g.dx),
      velocity = distance / Math.max(1, performance.now() - g.time);
    settle(
      !cancelled &&
        (distance > stepWidth() * 0.24 || (distance > 24 && velocity > 0.5))
        ? g.dx < 0
          ? 1
          : -1
        : 0,
    );
    if (e.currentTarget.hasPointerCapture(e.pointerId))
      e.currentTarget.releasePointerCapture(e.pointerId);
  };
  return (
    <div
      className="mobile-dock"
      style={{ "--mobile-track-color": tone } as CSSProperties}
    >
      <div
        className="mobile-player-viewport"
        ref={viewport}
        role="group"
        aria-label="Мини-плеер. Свайп для переключения трека"
        tabIndex={0}
        data-phase={phase}
        onPointerDown={down}
        onPointerMove={move}
        onPointerUp={(e) => up(e)}
        onPointerCancel={(e) => up(e, true)}
        onLostPointerCapture={(e) => {
          if (gesture.current?.id === e.pointerId) up(e, true);
        }}
        onClickCapture={(e) => {
          if (suppressClick.current && e.detail !== 0) {
            suppressClick.current = false;
            e.preventDefault();
            e.stopPropagation();
          }
        }}
        onKeyDown={(e) => {
          if (
            e.target === e.currentTarget &&
            ["ArrowLeft", "ArrowRight"].includes(e.key)
          ) {
            e.preventDefault();
            settle(e.key === "ArrowLeft" ? -1 : 1);
          }
        }}
      >
        <div className="mobile-player-strip" ref={strip}>
          {[previous?.track, track, next?.track].map((item, index) => (
            <TrackCard
              key={index}
              track={item}
              current={index === 1}
              progress={
                index === 1 && state.playback.duration
                  ? Math.min(
                      100,
                      Math.max(0, (position / state.playback.duration) * 100),
                    )
                  : 0
              }
              playing={index === 1 && state.playback.playing}
              busy={index === 1 && state.playback.transitioning}
              onOpen={onOpen}
              onLike={onLike}
              onToggle={() => void act("control", { action: "toggle" })}
            />
          ))}
        </div>
      </div>
      {children}
    </div>
  );
}
