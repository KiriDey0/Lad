// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useRef } from "react";
import { bridge } from "../bridge";
import type { State, Track } from "../types";
export function AudioEngine({
  state,
  notice,
}: {
  state: State;
  notice: (text: string) => void;
}) {
  const audioRef = useRef<HTMLAudioElement>(null);
  const generation = useRef(0);
  const lastReport = useRef(0);
  const desired = useRef<{ position: number; paused: boolean }>({
    position: 0,
    paused: true,
  });
  useEffect(() => {
    const audio = audioRef.current!;
    const report = () => {
      void bridge
        .rpc("localState", {
          generation: generation.current,
          position: audio.currentTime || 0,
          duration: Number.isFinite(audio.duration) ? audio.duration : 0,
          playing: !audio.paused && !audio.ended,
          loading: audio.readyState < 3,
        })
        .catch(() => {});
    };
    const update = () => {
      if (Date.now() - lastReport.current > 750) {
        lastReport.current = Date.now();
        report();
      }
    };
    const loaded = () => {
      if (desired.current.position)
        audio.currentTime = Math.min(
          desired.current.position,
          audio.duration || Infinity,
        );
      if (!desired.current.paused)
        void audio
          .play()
          .catch(() =>
            notice("Не удалось запустить звук. Нажми «Продолжить» ещё раз."),
          );
      report();
    };
    const ended = () => {
      report();
      void bridge
        .rpc("localEnded", { generation: generation.current })
        .catch((e) => notice(e.message));
    };
    const error = () => {
      if (audio.getAttribute("src"))
        void bridge
          .rpc("localError", { generation: generation.current })
          .catch(() => {});
    };
    audio.addEventListener("loadedmetadata", loaded);
    audio.addEventListener("timeupdate", update);
    audio.addEventListener("play", report);
    audio.addEventListener("pause", report);
    audio.addEventListener("waiting", report);
    audio.addEventListener("playing", report);
    audio.addEventListener("seeked", report);
    audio.addEventListener("ended", ended);
    audio.addEventListener("error", error);
    const unsubscribe = bridge.onEvent(({ event, data }) => {
      if (event !== "local") return;
      if (data.action === "load") {
        audio.pause();
        generation.current = data.generation;
        desired.current = { position: data.position || 0, paused: data.paused };
        audio.volume = data.volume;
        audio.src = data.url;
        audio.load();
      }
      if (data.action === "pause") audio.pause();
      if (data.action === "play")
        void audio
          .play()
          .catch(() =>
            notice(
              "Аудиоплеер не смог продолжить трек. Запусти его из очереди ещё раз.",
            ),
          );
      if (data.action === "seek") {
        audio.currentTime = data.value;
        report();
      }
      if (data.action === "volume") {
        audio.volume = data.value;
        report();
      }
    });
    return () => {
      unsubscribe();
      for (const [name, fn] of [
        ["loadedmetadata", loaded],
        ["timeupdate", update],
        ["play", report],
        ["pause", report],
        ["waiting", report],
        ["playing", report],
        ["seeked", report],
        ["ended", ended],
        ["error", error],
      ] as const)
        audio.removeEventListener(name, fn);
    };
  }, [notice]);
  useEffect(() => {
    if (!("mediaSession" in navigator)) return;
    const t = state.playback.track;
    navigator.mediaSession.metadata = t
      ? new MediaMetadata({
          title: t.title,
          artist: t.artist,
          album: t.album,
          artwork: t.cover
            ? [{ src: t.cover, sizes: "600x600", type: "image/jpeg" }]
            : [],
        })
      : null;
    navigator.mediaSession.playbackState = state.playback.playing
      ? "playing"
      : "paused";
    for (const [name, action] of [
      ["play", "play"],
      ["pause", "pause"],
      ["nexttrack", "next"],
      ["previoustrack", "previous"],
    ] as const)
      navigator.mediaSession.setActionHandler(name, () => {
        void bridge.rpc("control", { action }).catch((e) => notice(e.message));
      });
    navigator.mediaSession.setActionHandler("seekto", (e) => {
      void bridge
        .rpc("control", { action: "seek", value: e.seekTime || 0 })
        .catch(() => {});
    });
  }, [state.playback.track?.id, state.playback.playing, notice]);
  return <audio ref={audioRef} preload="auto" className="hidden" />;
}
