// SPDX-License-Identifier: AGPL-3.0-only
import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type MouseEvent,
} from "react";
import { createPortal } from "react-dom";
import type { LucideIcon } from "lucide-react";
export type MenuAnchor = {
  x: number;
  y: number;
  focus: HTMLElement | null;
  scrollPositions: { element: HTMLElement; top: number; left: number }[];
};
export type MenuRow = {
  label: string;
  icon: LucideIcon;
  run: () => unknown;
  disabled?: boolean;
  separator?: boolean;
};
export function menuAnchor(event: MouseEvent<HTMLElement>): MenuAnchor {
  event.preventDefault();
  event.stopPropagation();
  window.dispatchEvent(new Event("lad:menu-open"));
  const rect = event.currentTarget.getBoundingClientRect();
  const scrollPositions: MenuAnchor["scrollPositions"] = [];
  for (
    let element: HTMLElement | null = event.currentTarget;
    element;
    element = element.parentElement
  ) {
    scrollPositions.push({
      element,
      top: element.scrollTop,
      left: element.scrollLeft,
    });
  }
  return {
    scrollPositions,
    x: event.clientX || rect.left,
    y: event.clientY || rect.bottom,
    focus:
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null,
  };
}
export function ContextMenu({
  anchor,
  title,
  label,
  rows,
  close,
  notice,
}: {
  anchor: MenuAnchor;
  title: string;
  label: string;
  rows: MenuRow[];
  close: () => void;
  notice: (message: string) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [point, setPoint] = useState({ left: 0, top: 0 });
  useLayoutEffect(() => {
    if (!ref.current) return;
    const rect = ref.current.getBoundingClientRect();
    setPoint({
      left: Math.max(8, Math.min(anchor.x, innerWidth - rect.width - 8)),
      top: Math.max(8, Math.min(anchor.y, innerHeight - rect.height - 8)),
    });
    ref.current
      .querySelector<HTMLButtonElement>("button:not(:disabled)")
      ?.focus({ preventScroll: true });
  }, [anchor]);
  useEffect(() => {
    const outside = (event: Event) => {
      if (ref.current?.contains(event.target as Node)) return;
      if (event.type === "scroll") {
        const position = anchor.scrollPositions.find(
          (p) => p.element === event.target,
        );
        // The right click can arrive before the scroll event from revealing its target.
        if (
          position &&
          position.element.scrollTop === position.top &&
          position.element.scrollLeft === position.left
        )
          return;
      }
      close();
    };
    window.addEventListener("pointerdown", outside, true);
    window.addEventListener("scroll", outside, true);
    window.addEventListener("resize", close);
    window.addEventListener("blur", close);
    window.addEventListener("lad:menu-open", close);
    return () => {
      window.removeEventListener("pointerdown", outside, true);
      window.removeEventListener("scroll", outside, true);
      window.removeEventListener("resize", close);
      window.removeEventListener("blur", close);
      window.removeEventListener("lad:menu-open", close);
    };
  }, [close]);
  return createPortal(
    <div
      ref={ref}
      className="track-context-menu"
      role="menu"
      aria-label={label}
      style={point}
      onContextMenu={(e) => e.preventDefault()}
      onKeyDown={(e) => {
        const buttons = [
          ...e.currentTarget.querySelectorAll<HTMLButtonElement>(
            "button:not(:disabled)",
          ),
        ];
        const index = buttons.indexOf(
          document.activeElement as HTMLButtonElement,
        );
        if (["ArrowDown", "ArrowUp", "Home", "End"].includes(e.key)) {
          e.preventDefault();
          buttons[
            e.key === "Home"
              ? 0
              : e.key === "End"
                ? buttons.length - 1
                : (index + (e.key === "ArrowDown" ? 1 : -1) + buttons.length) %
                  buttons.length
          ]?.focus();
        }
        if (e.key === "Escape" || e.key === "Tab") {
          e.preventDefault();
          close();
          anchor.focus?.focus({ preventScroll: true });
        }
        e.stopPropagation();
      }}
    >
      <div className="context-track-title">{title}</div>
      {rows.map(({ label, icon: Icon, run, disabled, separator }) => (
        <button
          type="button"
          role="menuitem"
          key={label}
          className={separator ? "context-separated" : ""}
          disabled={disabled}
          onClick={() => {
            close();
            Promise.resolve()
              .then(run)
              .catch((error) =>
                notice(
                  error instanceof Error
                    ? error.message
                    : "Не удалось выполнить действие",
                ),
              );
          }}
        >
          <Icon size={18} />
          <span>{label}</span>
        </button>
      ))}
    </div>,
    document.body,
  );
}
