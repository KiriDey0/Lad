// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useId, useState } from "react";
import { createPortal } from "react-dom";
import type { Track } from "../types";

export function ExplicitBadge({ track }: { track: Track }) {
  const id = useId();
  const [position, setPosition] = useState<{
    left: number;
    top: number;
  } | null>(null);
  useEffect(() => {
    if (!position) return;
    const hide = () => setPosition(null);
    window.addEventListener("scroll", hide, true);
    window.addEventListener("resize", hide);
    return () => {
      window.removeEventListener("scroll", hide, true);
      window.removeEventListener("resize", hide);
    };
  }, [position]);
  if (!track.explicit) return null;
  const show = (element: HTMLElement) => {
    const rect = element.getBoundingClientRect();
    setPosition({
      left: Math.max(12, Math.min(rect.left, innerWidth - 252)),
      top:
        rect.bottom + 94 < innerHeight
          ? rect.bottom + 8
          : Math.max(12, rect.top - 86),
    });
  };
  return (
    <>
      <span
        className="explicit"
        tabIndex={0}
        aria-label="Контент 18+"
        aria-describedby={position ? id : undefined}
        onMouseEnter={(e) => show(e.currentTarget)}
        onMouseLeave={() => setPosition(null)}
        onFocus={(e) => show(e.currentTarget)}
        onBlur={() => setPosition(null)}
        onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => {
          if (e.key === "Escape") setPosition(null);
          if (["Enter", " ", "Escape"].includes(e.key)) {
            e.preventDefault();
            e.stopPropagation();
          }
        }}
      >
        E
      </span>
      {position &&
        createPortal(
          <div
            id={id}
            role="tooltip"
            className="explicit-tooltip"
            style={position}
          >
            <strong>Контент 18+</strong>
            <span>Трек отмечен Яндексом как контент для взрослых.</span>
          </div>,
          document.body,
        )}
    </>
  );
}
