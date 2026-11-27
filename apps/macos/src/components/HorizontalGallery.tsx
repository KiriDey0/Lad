// SPDX-License-Identifier: AGPL-3.0-only
import { useLayoutEffect, useRef, type ReactNode } from "react";
import clsx from "clsx";
export function HorizontalGallery({
  children,
  label,
  className,
  artists = false,
  cardWidth,
}: {
  children: ReactNode;
  label: string;
  className?: string;
  artists?: boolean;
  cardWidth?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const node = ref.current;
    if (!node) return;
    const resize = () => {
      const width = node.clientWidth,
        gap = 18,
        min = artists ? 160 : 126,
        wide = artists ? 5 : 6;
      const count =
        width >= wide * min + (wide - 1) * gap
          ? wide
          : width >= 4 * min + 3 * gap
            ? 4
            : width >= 3 * min + 2 * gap
              ? 3
              : 2.15;
      node.style.setProperty(
        "--gallery-card-width",
        cardWidth
          ? `${cardWidth}px`
          : `${(width - (count - 1) * gap) / count}px`,
      );
    };
    const observer = new ResizeObserver(resize);
    observer.observe(node);
    resize();
    const wheel = (e: WheelEvent) => {
      if (!e.shiftKey || Math.abs(e.deltaX) > Math.abs(e.deltaY) || !e.deltaY)
        return;
      const previous = node.scrollLeft;
      const delta =
        e.deltaY *
        (e.deltaMode === 1 ? 24 : e.deltaMode === 2 ? node.clientWidth : 1);
      node.scrollLeft += delta;
      if (node.scrollLeft !== previous) e.preventDefault();
    };
    node.addEventListener("wheel", wheel, { passive: false });
    return () => {
      observer.disconnect();
      node.removeEventListener("wheel", wheel);
    };
  }, [artists, cardWidth]);
  return (
    <div
      ref={ref}
      className={clsx(
        "horizontal-gallery",
        artists && "artist-gallery",
        className,
      )}
      role="region"
      aria-label={label}
      tabIndex={0}
      onKeyDown={(e) => {
        if (
          e.target !== e.currentTarget ||
          !["ArrowLeft", "ArrowRight"].includes(e.key)
        )
          return;
        e.preventDefault();
        const node = e.currentTarget;
        node.scrollBy({
          left:
            (e.key === "ArrowRight" ? 1 : -1) *
            (node.firstElementChild?.getBoundingClientRect().width || 180),
          behavior: matchMedia("(prefers-reduced-motion: reduce)").matches
            ? "instant"
            : "smooth",
        });
      }}
    >
      {children}
    </div>
  );
}
