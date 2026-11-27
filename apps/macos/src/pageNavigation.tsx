// SPDX-License-Identifier: AGPL-3.0-only
import {
  createContext,
  useCallback,
  useContext,
  useLayoutEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type Dispatch,
  type SetStateAction,
} from "react";

export class PageMemory {
  private values = new Map<string, unknown>();
  private listeners = new Map<string, Set<() => void>>();
  read<T>(key: string, initial: T | (() => T)): T {
    if (!this.values.has(key))
      this.values.set(
        key,
        typeof initial === "function" ? (initial as () => T)() : initial,
      );
    return this.values.get(key) as T;
  }
  write<T>(key: string, value: SetStateAction<T>) {
    const previous = this.values.get(key) as T;
    const next =
      typeof value === "function" ? (value as (v: T) => T)(previous) : value;
    if (Object.is(previous, next)) return;
    this.values.set(key, next);
    this.listeners.get(key)?.forEach((listener) => listener());
  }
  subscribe(key: string, listener: () => void) {
    const listeners = this.listeners.get(key) || new Set();
    this.listeners.set(key, listeners);
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  }
}
export const PageMemoryContext = createContext<PageMemory | null>(null);
export function usePageState<T>(
  key: string,
  initial: T | (() => T),
  explicit?: PageMemory,
): [T, Dispatch<SetStateAction<T>>] {
  const context = useContext(PageMemoryContext);
  const fallback = useRef<PageMemory | null>(null);
  if (!fallback.current) fallback.current = new PageMemory();
  const memory = explicit || context || fallback.current;
  const value = useSyncExternalStore(
    useCallback((listener) => memory.subscribe(key, listener), [memory, key]),
    () => memory.read(key, initial),
  );
  return [value, useCallback((next) => memory.write(key, next), [memory, key])];
}
export function usePageRef<T>(key: string, initial: T) {
  return usePageState(key, () => ({ current: initial }))[0];
}
type Position = {
  top: number;
  galleries: { label: string; index: number; left: number }[];
};
type Visit<Route> = { route: Route; memory: PageMemory; position: Position };
const visit = <Route,>(route: Route): Visit<Route> => ({
  route,
  memory: new PageMemory(),
  position: { top: 0, galleries: [] },
});

export function usePageNavigation<Route>(initial: Route, scope: string) {
  const [entry, setEntry] = useState(() => visit(initial));
  const current = useRef(entry);
  current.current = entry;
  const history = useRef<Visit<Route>[]>([]);
  const scroller = useRef<HTMLDivElement>(null);
  const save = useCallback(() => {
    const node = scroller.current;
    if (!node) return;
    const counts = new Map<string, number>();
    current.current.position = {
      top: node.scrollTop,
      galleries: Array.from(
        node.querySelectorAll<HTMLElement>(".horizontal-gallery"),
      ).map((gallery) => {
        const label = gallery.getAttribute("aria-label") || "";
        const index = counts.get(label) || 0;
        counts.set(label, index + 1);
        return { label, index, left: gallery.scrollLeft };
      }),
    };
  }, []);
  const navigate = useCallback(
    (route: Route, seed: Record<string, unknown> = {}) => {
      save();
      history.current.push(current.current);
      // Retain only a bounded, in-memory browsing history, never another account's data.
      if (history.current.length > 40) history.current.shift();
      const next = visit(route);
      Object.entries(seed).forEach(([key, value]) =>
        next.memory.write(key, value),
      );
      setEntry(next);
    },
    [save],
  );
  const back = useCallback(() => {
    const previous = history.current.pop();
    if (previous) setEntry(previous);
    else setEntry(visit(initial));
  }, []);
  const previousScope = useRef(scope);
  useLayoutEffect(() => {
    if (previousScope.current === scope) return;
    const enteringAccount = previousScope.current === "local-device";
    previousScope.current = scope;
    history.current = [];
    // Login may deliberately open a destination (for example demo lyrics).
    // Clear account data without overriding that simultaneous transition.
    setEntry(visit(enteringAccount ? current.current.route : initial));
  }, [scope]);
  useLayoutEffect(() => {
    const node = scroller.current;
    if (!node) return;
    const position = entry.position;
    let active = true;
    let settled = 0;
    const restore = () => {
      if (!active) return;
      node.scrollTop = position.top;
      const galleries = Array.from(
        node.querySelectorAll<HTMLElement>(".horizontal-gallery"),
      );
      let complete = Math.abs(node.scrollTop - position.top) < 1;
      for (const saved of position.galleries) {
        const gallery = galleries.filter(
          (g) => (g.getAttribute("aria-label") || "") === saved.label,
        )[saved.index];
        if (gallery) {
          if (saved.left) gallery.style.scrollSnapType = "none";
          gallery.scrollLeft = saved.left;
          complete &&= Math.abs(gallery.scrollLeft - saved.left) < 1;
        } else complete = false;
      }
      // Once restored, leave subsequent automatic scrolling (for example lyrics) alone.
      cancelAnimationFrame(settled);
      if (complete && !node.querySelector('[aria-label="Загрузка"]'))
        settled = requestAnimationFrame(stop);
    };
    // Async lists and images can change the scroll range after the first render.
    const observer = new MutationObserver(restore);
    observer.observe(node, { childList: true, subtree: true });
    const resize = new ResizeObserver(restore);
    Array.from(node.children).forEach((child) => resize.observe(child));
    const stop = () => {
      active = false;
      cancelAnimationFrame(settled);
      observer.disconnect();
      resize.disconnect();
    };
    for (const event of ["wheel", "touchstart", "pointerdown", "keydown"])
      document.addEventListener(event, stop, { passive: true, capture: true });
    restore();
    const frame = requestAnimationFrame(restore);
    return () => {
      stop();
      cancelAnimationFrame(frame);
      for (const event of ["wheel", "touchstart", "pointerdown", "keydown"])
        document.removeEventListener(event, stop, true);
    };
  }, [entry]);
  return { route: entry.route, memory: entry.memory, scroller, navigate, back };
}
