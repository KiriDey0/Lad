// SPDX-License-Identifier: AGPL-3.0-only
import { useRef, type ReactNode } from "react";
import { Dialog } from "@kiridey/orbit-ui";
import clsx from "clsx";
export function Modal({
  title,
  subtitle,
  close,
  children,
  footer,
  className,
}: {
  title: string;
  subtitle?: string;
  close: () => void;
  children: ReactNode;
  footer?: ReactNode;
  className?: string;
}) {
  const previous = useRef(
    document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null,
  );
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) close();
      }}
      title={title}
      description={subtitle}
      restoreFocus={() =>
        previous.current?.isConnected && previous.current.focus()
      }
    >
      <div className={clsx("lad-dialog-content", className)}>
        <div
          className="modal-scroll"
          tabIndex={className === "release-history-modal" ? 0 : undefined}
        >
          {children}
        </div>
        {footer && <div className="modal-footer">{footer}</div>}
      </div>
    </Dialog>
  );
}
