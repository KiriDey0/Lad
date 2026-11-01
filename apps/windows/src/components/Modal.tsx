// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useRef, type ReactNode } from "react";
import clsx from "clsx";
import { X } from "lucide-react";
import { IconButton } from "./ui";
export function Modal({
  title,
  subtitle,
  close,
  children,
  footer,
}: {
  title: string;
  subtitle?: string;
  close: () => void;
  children: ReactNode;
  footer?: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const old = document.activeElement as HTMLElement;
    const target = ref.current;
    const first =
      target?.querySelector<HTMLInputElement>("input:not([disabled])") ||
      target?.querySelector<HTMLButtonElement>("button");
    first?.focus();
    const handle = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        close();
        return;
      }
      if (e.key !== "Tab") return;
      const items = [
        ...target!.querySelectorAll<HTMLElement>(
          "button:not([disabled]),input:not([disabled]),a,select",
        ),
      ];
      const i = items.indexOf(document.activeElement as HTMLElement);
      if (e.shiftKey && i <= 0) {
        e.preventDefault();
        items.at(-1)?.focus();
      }
      if (!e.shiftKey && i === items.length - 1) {
        e.preventDefault();
        items[0]?.focus();
      }
    };
    target?.addEventListener("keydown", handle);
    return () => {
      target?.removeEventListener("keydown", handle);
      old?.focus();
    };
  }, []);
  return (
    <div
      className="modal-overlay"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) close();
      }}
    >
      <div
        className={clsx("modal", footer && "modal-with-footer")}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        ref={ref}
      >
        <div className="modal-header">
          <div>
            <h2>{title}</h2>
            {subtitle && <p>{subtitle}</p>}
          </div>
          <IconButton icon={X} label="Закрыть окно" onClick={close} />
        </div>
        {footer ? <div className="modal-scroll">{children}</div> : children}
        {footer && <div className="modal-footer">{footer}</div>}
      </div>
    </div>
  );
}
