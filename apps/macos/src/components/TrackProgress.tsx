// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useRef, useState } from "react";
import { duration } from "./ui";
import { Scrubber } from "@kiridey/orbit-ui";

export function TrackProgress({
  position,
  length,
  resetKey,
  seek,
  playing = false,
}: {
  position: number;
  length: number;
  resetKey: string;
  seek: (value: number) => void;
  playing?: boolean;
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
    <div
      className="progress-row"
      onPointerDownCapture={() => {
        dragging.current = true;
      }}
      onPointerUpCapture={finish}
      onPointerCancelCapture={cancel}
      onBlurCapture={cancel}
    >
      <Scrubber
        key={resetKey}
        label="Позиция трека"
        time={preview ?? position}
        duration={length}
        playing={playing}
        disabled={!length}
        onSeek={(value) => {
          if (dragging.current) {
            desired.current = value;
            setPreview(value);
          } else onSeek.current(value);
        }}
      />
      <span className="progress-total">{duration(length)}</span>
    </div>
  );
}
