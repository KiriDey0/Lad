// SPDX-License-Identifier: AGPL-3.0-only
import {
  initialState,
  type Bridge,
  type State,
  type Track,
  type LadEvent,
  type Card,
} from "./types";
export const demoTracks: Track[] = [
  "Первый свет",
  "Тихий город",
  "Между станциями",
  "После полуночи",
  "Ближе к свету",
  "До следующего утра",
].map((title, i) => ({
  id: `demo-${i}`,
  title,
  artist: "Лад Session",
  artistId: "demo-artist",
  artists: [{ id: "demo-artist", name: "Лад Session" }],
  album: "Тихий город",
  albumId: "demo",
  duration: 216 + i * 9,
  art: i,
  available: true,
  liked: i % 2 === 0,
}));
const cards: Card[] = [
  "Первый свет",
  "Тихий город",
  "После полуночи",
  "Ближе к свету",
].map((title, i) => ({
  id: `demo-${i}`,
  type: "playlist",
  title,
  subtitle: "Лад Session · пример",
  art: i,
}));
export function browserBridge(): Bridge {
  let savedAlbums: Card[] = [];
  let state: State = structuredClone(initialState);
  const listeners = new Set<(e: LadEvent) => void>();
  const emit = () =>
    listeners.forEach((fn) =>
      fn({ event: "state", data: structuredClone(state) }),
    );
  const start = (index: number) => {
    state.queue.index = index;
    state.queue.owned = true;
    state.playback = {
      ...state.playback,
      track: state.queue.tracks[index],
      position: 0,
      positionAt: Date.now(),
      duration: state.queue.tracks[index].duration,
      playing: true,
    };
  };
  setInterval(() => {
    if (state.demo && state.playback.playing) {
      const p = state.playback;
      const pos = p.position + (Date.now() - p.positionAt) / 1000;
      if (pos >= p.duration) {
        if (state.queue.index < state.queue.tracks.length - 1)
          start(state.queue.index + 1);
        else p.playing = false;
      }
      emit();
    }
  }, 1000);
  return {
    platform: "browser",
    onEvent(fn) {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    async login() {
      throw new Error(
        "Вход в аккаунт доступен в настольном приложении Лад. Здесь можно посмотреть демо.",
      );
    },
    async open(url) {
      window.open(url, "_blank", "noopener,noreferrer");
    },
    async copy(text) {
      await navigator.clipboard.writeText(text);
    },
    async rpc<T = any>(
      method: string,
      p: Record<string, any> = {},
    ): Promise<T> {
      let out: any;
      switch (method) {
        case "snapshot":
          out = state;
          break;
        case "demo":
          state = {
            ...structuredClone(initialState),
            demo: true,
            profile: { name: "Режим знакомства", uid: "demo", plus: true },
            queue: {
              tracks: structuredClone(demoTracks),
              index: 0,
              source: "Лад Session · пример",
              owned: true,
              repeat: "off",
              shuffled: false,
            },
          };
          state.playback = {
            ...state.playback,
            track: demoTracks[0],
            duration: 216,
          };
          out = state;
          break;
        case "logout":
          state = structuredClone(initialState);
          out = state;
          break;
        case "setVolumeSync":
          state.syncVolume = !!p.enabled;
          out = state;
          break;
        case "setContentFilter":
          state.blockExplicit = !!p.enabled;
          if (state.blockExplicit && state.playback.track?.explicit)
            state.playback.playing = false;
          out = state;
          break;
        case "home":
          out = {
            cards,
            tracks: demoTracks,
            tracksTitle: "Продолжить слушать",
          };
          break;
        case "library":
          out = { playlists: cards, albums: savedAlbums };
          break;
        case "albumLike":
          savedAlbums = savedAlbums.filter((c) => c.id !== p.id);
          if (p.liked) savedAlbums.push({ ...p.card, id: p.id, type: "album" });
          out = true;
          break;
        case "liked":
          out = { tracks: demoTracks.filter((t) => t.liked), total: 3 };
          break;
        case "search": {
          const tracks = demoTracks.filter((t) =>
            (t.title + " " + t.artist)
              .toLowerCase()
              .includes(String(p.query).toLowerCase()),
          );
          out = {
            tracks,
            albums: [],
            playlists: [],
            artists: [],
            page: 0,
            total: tracks.length,
          };
          break;
        }
        case "collection":
        case "link":
          out = {
            title: p.type === "album" ? "Тихий город" : "Лад Session",
            tracks: demoTracks,
          };
          break;
        case "wave":
          state.queue.tracks = structuredClone(demoTracks);
          state.queue.source = "Моя волна · пример";
          start(0);
          out = state;
          break;
        case "devices":
          out = [
            {
              id: "demo-midi",
              name: "Станция Миди · пример",
              platform: "yandexmidi",
            },
          ];
          break;
        case "output":
          state.output = p.output;
          out = state;
          break;
        case "connect":
          state.deviceId = p.id;
          state.connection = "connected";
          state.devices = [{ id: p.id, name: "Станция Миди · пример" }];
          out = state;
          break;
        case "play":
          state.queue.tracks = structuredClone(p.tracks);
          state.queue.source = p.source;
          start(p.index || 0);
          out = state;
          break;
        case "queue":
          if (p.action === "jump") start(p.index);
          if (p.action === "add") {
            state.queue.tracks.splice(
              p.next ? state.queue.index + 1 : state.queue.tracks.length,
              0,
              ...p.tracks,
            );
          }
          if (p.action === "remove" && p.index !== state.queue.index) {
            state.queue.tracks.splice(p.index, 1);
            if (p.index < state.queue.index) state.queue.index--;
          }
          if (p.action === "move") {
            const current = state.queue.tracks[state.queue.index];
            state.queue.tracks.splice(
              p.to,
              0,
              ...state.queue.tracks.splice(p.from, 1),
            );
            state.queue.index = state.queue.tracks.indexOf(current);
          }
          if (p.action === "clear") {
            state.queue.tracks = state.playback.track
              ? [state.playback.track]
              : [];
            state.queue.index = state.queue.tracks.length ? 0 : -1;
          }
          out = state;
          break;
        case "control": {
          const playback = state.playback;
          playback.position = Math.min(
            playback.duration,
            playback.position +
              (playback.playing
                ? (Date.now() - playback.positionAt) / 1000
                : 0),
          );
          playback.positionAt = Date.now();
          if (p.action === "toggle") playback.playing = !playback.playing;
          if (p.action === "play") playback.playing = true;
          if (p.action === "pause") playback.playing = false;
          if (p.action === "seek") playback.position = p.value;
          if (p.action === "volume") playback.volume = p.value;
          if (
            p.action === "next" &&
            state.queue.index < state.queue.tracks.length - 1
          )
            start(state.queue.index + 1);
          if (p.action === "previous" && state.queue.index > 0)
            start(state.queue.index - 1);
          if (p.action === "repeat") state.queue.repeat = p.value;
          if (p.action === "shuffle") state.queue.shuffled = p.value;
          out = state;
          break;
        }
        case "lyrics":
          out = {
            synced: true,
            lines: [
              "За окном засыпает город",
              "И стихают его голоса",
              "Мы останемся на своей орбите",
              "Пусть играет ещё одна",
              "Свет проходит сквозь наши окна",
              "И ведёт нас до самого утра",
            ].map((text, i) => ({ text, time: i * 32 })),
            writers: ["Демонстрационный текст Лад"],
          };
          break;
        case "like":
          for (const t of [...demoTracks, ...state.queue.tracks])
            if (t.id === p.id) t.liked = p.liked;
          out = true;
          break;
        default:
          throw new Error("Это действие доступно в настольном приложении Лад.");
      }
      emit();
      return structuredClone(out) as T;
    },
  };
}
