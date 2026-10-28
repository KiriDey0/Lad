// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useRef, useState } from "react";
import { duration } from "./ui";

export function TrackProgress({
  position,
  length,
  resetKey,
  seek,
}: {
  position: number;
  length: number;
  resetKey: string;
  seek: (value: number) => void;
}) {
  const [preview, setPreview] = useState<number | null>(null);
  const dragging = useRef(false);
  const desired = useRef<number | null>(null);
  const onSeek = useRef(seek);
  onSeek.current = seek;
  const cancel = () => {
    dragging.current = false;
    desired.current = null;
    setPreview(null);
  };
  const finish = () => {
    if (!dragging.current) return;
    const value = desired.current;
    cancel();
    if (value !== null) onSeek.current(value);
  };
  useEffect(() => {
    cancel();
  }, [resetKey]);
  useEffect(() => {
    window.addEventListener("pointerup", finish);
    window.addEventListener("pointercancel", cancel);
    window.addEventListener("blur", cancel);
    return () => {
      window.removeEventListener("pointerup", finish);
      window.removeEventListener("pointercancel", cancel);
      window.removeEventListener("blur", cancel);
    };
  }, []);
  return (
    <div className="progress-row">
      <span>{duration(preview ?? position)}</span>
      <input
        aria-label="Позиция трека"
        type="range"
        min="0"
        max={length || 1}
        step="0.1"
        value={preview ?? position}
        disabled={!length}
        onPointerDown={(e) => {
          dragging.current = true;
          try {
            e.currentTarget.setPointerCapture(e.pointerId);
          } catch {}
        }}
        onChange={(e) => {
          const value = Number(e.target.value);
          if (dragging.current) {
            desired.current = value;
            setPreview(value);
          } else {
            // Keyboard and accessibility changes have no pointerup to finish them.
            onSeek.current(value);
          }
        }}
        onPointerUp={finish}
        onPointerCancel={cancel}
        onLostPointerCapture={cancel}
        onBlur={cancel}
      />
      <span>{duration(length)}</span>
    </div>
  );
}
