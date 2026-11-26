// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ArrowDown, ArrowUp, AudioLines, GripVertical, Info, ListMusic, Play, Trash2, X } from "lucide-react";
import clsx from "clsx";
import type { State, Track } from "../types";
import { Art, duration, Empty, IconButton } from "./ui";
import { TrackMetadata, useTrackActions } from "./TrackActions";

type Act = (method: string, params?: Record<string, unknown>) => Promise<any>;
type Drag = { id: string; track: Track; pointer: number; x: number; y: number; grab: number; height: number; left: number; width: number; order: string[]; source: string };
const key = (t: Track, i: number) => t.queueEntryId || `${i}:${t.id}`;

export function QueuePanel({ state, act, close }: { state: State; act: Act; close: () => void }) {
  const actions = useTrackActions();
  const q = state.queue;
  const scroll = useRef<HTMLDivElement>(null);
  const floating = useRef<HTMLDivElement>(null);
  const positioned = useRef(false);
  const active = useRef<Drag | null>(null);
  const press = useRef<{ id: number; x: number; y: number; row: HTMLElement; track: Track; index: number; timer?: ReturnType<typeof setTimeout> } | null>(null);
  const [drag, setDrag] = useState<Drag | null>(null);
  const [order, setOrder] = useState<string[] | null>(null);
  const [saving, setSaving] = useState(false);
  const keys = q.tracks.map(key);
  const signature = keys.join("|");
  const indices = new Map(keys.map((id, i) => [id, i]));
  const byId = new Map(q.tracks.map((t, i) => [key(t, i), t]));
  const shown = order?.filter(id => byId.has(id)) ?? keys;
  const rects = useRef(new Map<string, number>());
  const layout = useRef<{ id: string; top: number; height: number }[]>([]);

  useLayoutEffect(() => {
    const list = scroll.current;
    if (!list || positioned.current || !q.tracks.length) return;
    positioned.current = true;
    const row = list.querySelector<HTMLElement>(`[data-queue-index="${q.index}"]`);
    if (row) list.scrollTop = Math.max(0, row.offsetTop - list.clientHeight * 2 / 3);
  }, [q.tracks.length > 0]);

  // FLIP uses layout positions, not animated hit rectangles, so rows never fight the drag target.
  useLayoutEffect(() => {
    const list = scroll.current;
    if (!list) return;
    const next = new Map<string, number>();
    const positions: { id: string; top: number; height: number }[] = [];
    list.querySelectorAll<HTMLElement>(".queue-row").forEach(row => {
      const id = row.dataset.entry!;
      const top = row.offsetTop;
      next.set(id, top);
      positions.push({ id, top, height: row.offsetHeight });
      const before = rects.current.get(id);
      if (order && before !== undefined && before !== top && id !== active.current?.id && !matchMedia("(prefers-reduced-motion: reduce)").matches) {
        row.getAnimations().forEach(a => a.cancel());
        row.animate([{ transform: `translateY(${before - top}px)` }, { transform: "translateY(0)" }], { duration: 170, easing: "ease-out" });
      }
    });
    rects.current = next;
    layout.current = positions;
  }, [shown.join("|")]);

  const release = (commit: boolean) => {
    if (press.current?.timer) clearTimeout(press.current.timer);
    press.current = null;
    const current = active.current;
    active.current = null;
    setDrag(null);
    if (!current) return;
    const from = keys.indexOf(current.id), to = current.order.indexOf(current.id);
    if (commit && from >= 0 && to >= 0 && from !== to && current.source === signature) {
      const before = current.order[to + 1] ?? null;
      setSaving(true);
      void act("queue", { action: "move", entryId: current.track.queueEntryId, beforeEntryId: before,
        from, to }).finally(() => { setSaving(false); setOrder(null); });
    } else setOrder(null);
  };
  const finishRef = useRef(release); finishRef.current = release;
  useEffect(() => {
    if (active.current && active.current.source !== signature) finishRef.current(false);
  }, [signature]);
  useEffect(() => {
    const cancel = () => finishRef.current(false);
    const escape = (event: KeyboardEvent) => { if (event.key === "Escape" && active.current) { event.stopPropagation(); cancel(); } };
    window.addEventListener("blur", cancel);
    window.addEventListener("keydown", escape, true);
    return () => { cancel(); window.removeEventListener("blur", cancel); window.removeEventListener("keydown", escape, true); };
  }, []);

  useEffect(() => {
    if (!drag) return;
    let frame = 0, last = performance.now();
    const tick = (now: number) => {
      const current = active.current, list = scroll.current;
      if (!current || !list) return;
      const bounds = list.getBoundingClientRect();
      const edge = Math.min(64, bounds.height / 3);
      const ratio = current.y < bounds.top + edge ? -Math.min(1, (bounds.top + edge - current.y) / edge)
        : current.y > bounds.bottom - edge ? Math.min(1, (current.y - bounds.bottom + edge) / edge) : 0;
      list.scrollTop += ratio * 800 * Math.min(.05, (now - last) / 1000);
      last = now;
      if (floating.current) floating.current.style.top = `${current.y - current.grab}px`;
      const center = current.y - current.grab + current.height / 2 - bounds.top + list.scrollTop;
      const rows = layout.current;
      let lo = 0, hi = rows.length;
      while (lo < hi) {
        const mid = (lo + hi) >>> 1;
        if (center >= rows[mid].top + rows[mid].height) lo = mid + 1; else hi = mid;
      }
      const to = Math.min(lo, rows.length - 1), candidate = rows[to];
      if (candidate && candidate.id !== current.id) {
        const next = [...current.order];
        next.splice(to, 0, next.splice(next.indexOf(current.id), 1)[0]);
        current.order = next;
        setOrder(next);
      }
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [drag?.id]);

  const begin = () => {
    const pending = press.current;
    if (!pending || active.current || saving || !scroll.current) return;
    const bounds = pending.row.getBoundingClientRect();
    const current: Drag = { id: key(pending.track, pending.index), track: pending.track, pointer: pending.id,
      x: pending.x, y: pending.y, grab: pending.y - bounds.top, height: bounds.height,
      left: bounds.left, width: bounds.width, order: [...keys], source: signature };
    scroll.current.setPointerCapture(pending.id);
    active.current = current; setDrag(current); setOrder(current.order);
  };
  const command = (action: string, track: Track, index: number, extra: Record<string, unknown> = {}) =>
    void act("queue", { action, entryId: track.queueEntryId, index, ...extra });

  return <aside className={clsx("queue-panel", drag && "queue-reordering")}>
    <div className="queue-header"><h2>Очередь <span>{q.tracks.length || ""}</span></h2><IconButton icon={X} label="Скрыть очередь" onClick={close}/></div>
    <div className="queue-source"><span>{q.owned ? q.source : q.tracks.length ? "Сохранённая очередь Лад" : "Пока ничего не выбрано"}</span>
      {q.tracks.length > 0 && <IconButton icon={Trash2} label="Очистить следующие треки" onClick={() => void act("queue", { action: "clear" })}/>}</div>
    {state.output === "station" && !q.owned && state.playback.track && <div className="inline-note"><Info size={16}/><span>Музыку включили вне «Лада». Станция не передаёт полный список своей очереди.</span></div>}
    <div ref={scroll} className="queue-scroll"
      onPointerMove={e => {
        const current = active.current;
        if (current) { e.preventDefault(); current.y = e.clientY; return; }
        const pending = press.current;
        if (pending && e.pointerId === pending.id && Math.hypot(e.clientX - pending.x, e.clientY - pending.y) > 5) {
          if (e.pointerType === "mouse") begin(); else { clearTimeout(pending.timer); press.current = null; }
        }
      }}
      onPointerUp={() => release(true)} onPointerCancel={() => release(false)}
      onLostPointerCapture={() => { if (active.current) release(false); }}
      onClickCapture={e => { if (drag) { e.preventDefault(); e.stopPropagation(); } }}>
      {!q.tracks.length ? <Empty icon={ListMusic} title="Поставь что-нибудь">Найди трек или открой плейлист — очередь будет здесь.</Empty>
      : shown.map(id => {
        const t = byId.get(id)!; const i = indices.get(id)!;
        return <div key={id} data-entry={id} data-queue-index={i}
          className={clsx("queue-row", q.index === i && q.owned && "queue-current", i < q.index && q.owned && "queue-played", drag?.id === id && "queue-placeholder")}
          onContextMenu={e => { if (drag) e.preventDefault(); else actions.menu(e, t, () => command("jump", t, i), i); }}
          onPointerDown={e => {
            if (e.button !== 0 || saving || (e.target as HTMLElement).closest("button:not(.queue-grip),a")) return;
            press.current = { id: e.pointerId, x: e.clientX, y: e.clientY, row: e.currentTarget, track: t, index: i };
            if (e.pointerType !== "mouse") press.current.timer = setTimeout(begin, 350);
          }}>
          <button className="queue-grip" aria-label={`Переставить ${t.title}`} style={{ touchAction: "none" }}><GripVertical size={14}/></button>
          <button className="queue-art-play" disabled={t.available === false || (state.blockExplicit && t.explicit)} onClick={() => command("jump", t, i)} aria-label={`Включить ${t.title} из очереди`}><Art item={t} size="xs"/><Play size={16}/></button>
          <div className="queue-track"><TrackMetadata track={t}/></div>
          {q.index === i && q.owned ? <AudioLines className="queue-playing" size={17}/> : <span className="queue-track-time">{duration(t.duration)}</span>}
          <div className="queue-actions">
            <IconButton icon={ArrowUp} label={`Поднять ${t.title}`} disabled={i === 0 || saving} onClick={() => command("move", t, i, { from: i, to: i - 1, delta: -1 })}/>
            <IconButton icon={ArrowDown} label={`Опустить ${t.title}`} disabled={i === q.tracks.length - 1 || saving} onClick={() => command("move", t, i, { from: i, to: i + 1, delta: 1 })}/>
            <IconButton icon={X} label={`Удалить ${t.title} из очереди`} disabled={q.index === i && q.owned || saving} onClick={() => command("remove", t, i)}/>
          </div>
        </div>;
      })}
    </div>
    <div className="queue-bottom"><GripVertical size={15}/><span>Перетаскивай треки, меняй порядок</span></div>
    {drag && createPortal(<div className="queue-row queue-floating" ref={floating} aria-label={`Переносимый трек: ${drag.track.title}`}
      style={{ position: "fixed", top: drag.y - drag.grab, left: drag.left, width: drag.width, height: drag.height }}>
      <GripVertical size={14}/><Art item={drag.track} size="xs"/><div className="queue-track"><strong>{drag.track.title}</strong><small>{drag.track.artist}</small></div>
    </div>, document.body)}
  </aside>;
}
