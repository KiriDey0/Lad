// SPDX-License-Identifier: AGPL-3.0-only
import {
  createContext,
  useContext,
  useEffect,
  useState,
  type MouseEvent,
  type ReactNode,
} from "react";
import {
  Play,
  ListEnd,
  ListStart,
  Heart,
  Disc3,
  Mic2,
  Copy,
  ExternalLink,
  Trash2,
} from "lucide-react";
import type { Card, State, Track } from "../types";
import { bridge } from "../bridge";
import {
  ContextMenu,
  menuAnchor,
  type MenuAnchor,
  type MenuRow,
} from "./ContextMenu";
import { ExplicitBadge } from "./ExplicitBadge";

type MenuTarget = MenuAnchor & {
  track: Track;
  play?: () => void;
  queueIndex?: number;
};
type Actions = {
  open: (card: Card) => void;
  menu: (
    event: MouseEvent<HTMLElement>,
    track: Track,
    play?: () => void,
    queueIndex?: number,
  ) => void;
};
const TrackActions = createContext<Actions | null>(null);
export function useTrackActions() {
  const value = useContext(TrackActions);
  if (!value) throw new Error("TrackActionsProvider is required");
  return value;
}
export function trackArtists(track: Track) {
  return track.artists?.length
    ? track.artists
    : track.artistId
      ? [{ id: track.artistId, name: track.artist }]
      : [];
}
export function TrackAlbumLink({
  track,
  children,
}: {
  track: Track;
  children: ReactNode;
}) {
  const { open } = useTrackActions();
  return track.albumId ? (
    <button
      className="metadata-link"
      aria-label={`Открыть альбом ${track.album || track.title}`}
      onClick={(e) => {
        e.stopPropagation();
        open({
          id: track.albumId!,
          type: "album",
          title: track.album || track.title,
          subtitle: track.artist,
          cover: track.cover,
          art: track.art,
        });
      }}
    >
      {children}
    </button>
  ) : (
    <>{children}</>
  );
}
export function ArtistLinks({ track }: { track: Track }) {
  const { open } = useTrackActions();
  const artists = trackArtists(track);
  return (
    <>
      {artists.length
        ? artists.map((artist, i) => (
            <span key={`${artist.id}:${i}`}>
              {i > 0 && ", "}
              <button
                className="metadata-link"
                aria-label={`Открыть исполнителя ${artist.name}`}
                onClick={(e) => {
                  e.stopPropagation();
                  open({ id: artist.id, type: "artist", title: artist.name });
                }}
              >
                {artist.name}
              </button>
            </span>
          ))
        : track.artist}
    </>
  );
}
export function TrackMetadata({ track }: { track: Track }) {
  return (
    <>
      <strong>
        <TrackAlbumLink track={track}>{track.title}</TrackAlbumLink>{" "}
        <ExplicitBadge track={track} />
      </strong>
      <small>
        <ArtistLinks track={track} />
      </small>
    </>
  );
}
export function TrackActionsProvider({
  state,
  open,
  play,
  add,
  like,
  act,
  notice,
  children,
}: {
  state: State;
  open: (card: Card) => void;
  play: (tracks: Track[], index: number, source: string) => void;
  add: (track: Track, next?: boolean) => void;
  like: (track: Track) => void;
  act: (method: string, params?: Record<string, unknown>) => Promise<unknown>;
  notice: (message: string) => void;
  children: ReactNode;
}) {
  const [target, setTarget] = useState<MenuTarget | null>(null);
  const menu: Actions["menu"] = (event, track, run, queueIndex) => {
    setTarget({ ...menuAnchor(event), track, play: run, queueIndex });
  };
  useEffect(() => setTarget(null), [state.profile?.uid, state.demo]);
  const rows: MenuRow[] = [];
  if (target) {
    const t = target.track;
    const blocked =
      t.available === false || (state.blockExplicit && !!t.explicit);
    const isLiked =
      state.playback.track?.id === t.id
        ? (state.playback.track.liked ?? t.liked)
        : (state.queue.tracks.find((x) => x.id === t.id)?.liked ?? t.liked);
    rows.push(
      {
        label: "Включить",
        icon: Play,
        disabled: blocked,
        run: target.play || (() => play([t], 0, "Выбранный трек")),
      },
      {
        label: "Играть следующим",
        icon: ListStart,
        disabled: blocked,
        run: () => add(t, true),
      },
      {
        label: "Добавить в конец очереди",
        icon: ListEnd,
        disabled: blocked,
        run: () => add(t),
      },
      {
        label: isLiked ? "Убрать из любимых" : "Нравится",
        icon: Heart,
        separator: true,
        run: () => like({ ...t, liked: !!isLiked }),
      },
    );
    if (t.albumId)
      rows.push({
        label: "Перейти к альбому",
        icon: Disc3,
        separator: true,
        run: () =>
          open({
            id: t.albumId!,
            type: "album",
            title: t.album || t.title,
            cover: t.cover,
            art: t.art,
            subtitle: t.artist,
          }),
      });
    for (const artist of trackArtists(t))
      rows.push({
        label: `К исполнителю: ${artist.name}`,
        icon: Mic2,
        run: () => open({ id: artist.id, type: "artist", title: artist.name }),
      });
    const url = `https://music.yandex.ru/${t.albumId ? `album/${encodeURIComponent(t.albumId)}/` : ""}track/${encodeURIComponent(t.id)}`;
    rows.push(
      {
        label: "Скопировать ссылку",
        icon: Copy,
        separator: true,
        disabled: state.demo,
        run: async () => {
          await bridge.copy(url);
          notice("Ссылка на трек скопирована");
        },
      },
      {
        label: "Открыть в Яндекс Музыке",
        icon: ExternalLink,
        disabled: state.demo,
        run: () => bridge.open(url),
      },
    );
    const i = target.queueIndex;
    if (i !== undefined && state.queue.tracks[i]?.id === t.id)
      rows.push({
        label: "Удалить из очереди",
        icon: Trash2,
        separator: true,
        disabled: state.queue.owned && state.queue.index === i,
        run: () => act("queue", { action: "remove", index: i }),
      });
  }
  return (
    <TrackActions.Provider value={{ open, menu }}>
      {children}
      {target && (
        <ContextMenu
          anchor={target}
          title={target.track.title}
          label={`Действия с треком ${target.track.title}`}
          rows={rows}
          close={() => setTarget(null)}
          notice={notice}
        />
      )}
    </TrackActions.Provider>
  );
}
