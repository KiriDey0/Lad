// SPDX-License-Identifier: AGPL-3.0-only
import { useLayoutEffect, useRef, useState, type ComponentProps } from "react";
import { createPortal } from "react-dom";
import { WaveScene } from "./WaveScene";

/** Only the decorative canvas escapes the page's scroll clip. */
export function WaveBackdrop(props: ComponentProps<typeof WaveScene>) {
  const anchor = useRef<HTMLDivElement>(null);
  const layer = useRef<HTMLDivElement>(null);
  const [main, setMain] = useState<HTMLElement | null>(null);
  useLayoutEffect(() => {
    setMain(anchor.current?.closest<HTMLElement>(".main") ?? null);
  }, []);
  useLayoutEffect(() => {
    const slot = anchor.current;
    const backdrop = layer.current;
    const scroll = slot?.closest<HTMLElement>(".page-content");
    if (!main || !slot || !backdrop || !scroll) return;
    const place = () => {
      const bounds = slot.getBoundingClientRect();
      const parent = main.getBoundingClientRect();
      const scale = parent.width / main.offsetWidth || 1;
      Object.assign(backdrop.style, {
        left: `${(bounds.left - parent.left) / scale - main.clientLeft}px`,
        top: `${(bounds.top - parent.top) / scale - main.clientTop}px`,
        width: `${bounds.width / scale}px`,
        height: `${bounds.height / scale}px`,
      });
    };
    place();
    const resize = new ResizeObserver(place);
    resize.observe(slot);
    resize.observe(main);
    scroll.addEventListener("scroll", place, { passive: true });
    window.addEventListener("resize", place);
    return () => {
      resize.disconnect();
      scroll.removeEventListener("scroll", place);
      window.removeEventListener("resize", place);
    };
  }, [main]);
  return (
    <>
      <div ref={anchor} className="wave-scene-anchor" aria-hidden="true" />
      {main &&
        createPortal(
          <div ref={layer} className="wave-backdrop" aria-hidden="true">
            <WaveScene {...props} />
          </div>,
          main,
        )}
    </>
  );
}
