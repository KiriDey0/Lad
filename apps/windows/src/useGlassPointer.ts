// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect } from "react";
/** A small highlight in the glass material, without React renders on pointer movement. */
export function useGlassPointer() {
  useEffect(() => {
    const motion = window.matchMedia("(prefers-reduced-motion: reduce)");
    const fine = window.matchMedia("(hover: hover) and (pointer: fine)");
    let panel: HTMLElement | null = null;
    let frame = 0;
    let x = 0,
      y = 0;
    const clear = () => {
      if (panel) delete panel.dataset.pointerActive;
      panel = null;
    };
    const move = (event: PointerEvent) => {
      if (motion.matches || !fine.matches) {
        clear();
        return;
      }
      const target =
        event.target instanceof Element
          ? event.target.closest<HTMLElement>(".player,.modal,.toast")
          : null;
      if (target !== panel) {
        clear();
        panel = target;
      }
      if (!panel) return;
      const bounds = panel.getBoundingClientRect();
      x = event.clientX - bounds.left;
      y = event.clientY - bounds.top;
      if (!frame)
        frame = requestAnimationFrame(() => {
          frame = 0;
          if (!panel) return;
          panel.style.setProperty("--pointer-x", `${x}px`);
          panel.style.setProperty("--pointer-y", `${y}px`);
          panel.dataset.pointerActive = "true";
        });
    };
    document.addEventListener("pointermove", move, { passive: true });
    document.addEventListener("pointerleave", clear);
    window.addEventListener("blur", clear);
    motion.addEventListener("change", clear);
    return () => {
      clear();
      cancelAnimationFrame(frame);
      document.removeEventListener("pointermove", move);
      document.removeEventListener("pointerleave", clear);
      window.removeEventListener("blur", clear);
      motion.removeEventListener("change", clear);
    };
  }, []);
}
