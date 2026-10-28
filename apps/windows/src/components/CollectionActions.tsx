// SPDX-License-Identifier: AGPL-3.0-only
import {
  createContext,
  useContext,
  useEffect,
  useRef,
  useState,
  type MouseEvent,
  type ReactNode,
} from "react";
import {
  ArrowUpRight,
  ExternalLink,
  ListEnd,
  ListStart,
  MoreHorizontal,
  Pin,
  PinOff,
  Play,
  Heart,
  Share2,
} from "lucide-react";
import type { Card, Collection, State, Track } from "../types";
import { bridge } from "../bridge";
import {
  ContextMenu,
  menuAnchor,
  type MenuAnchor,
  type MenuRow,
} from "./ContextMenu";
import { IconButton } from "./ui";

type Actions = {
  menu: (event: MouseEvent<HTMLElement>, card: Card) => void;
  pinned: (card: Card) => boolean;
  toggle: (card: Card) => void;
  ready: boolean;
  albumSaved: (card: Card) => boolean;
  albumReady: boolean;
  likeAlbum: (card: Card) => Promise<void>;
};
const CollectionActions = createContext<Actions | null>(null);
export function useCollectionActions() {
  const value = useContext(CollectionActions);
  if (!value) throw new Error("CollectionActionsProvider is required");
  return value;
}
export function CollectionControls({ card }: { card: Card }) {
  const actions = useCollectionActions();
  if (card.type === "artist" || card.id === "link") return null;
  const pinned = actions.pinned(card);
  return (
    <div className="collection-controls">
      {card.type === "album" && (
        <IconButton
          icon={Heart}
          active={actions.albumSaved(card)}
          label={
            actions.albumSaved(card)
              ? "Убрать альбом из коллекции"
              : "Добавить альбом в коллекцию"
          }
          disabled={!actions.albumReady}
          onClick={() => void actions.likeAlbum(card)}
        />
      )}
      <IconButton
        icon={pinned ? PinOff : Pin}
        active={pinned}
        label={
          pinned ? "Открепить из «Твоей музыки»" : "Закрепить в «Твоей музыке»"
        }
        disabled={!actions.ready}
        onClick={() => actions.toggle(card)}
      />
      <IconButton
        icon={MoreHorizontal}
        label={`Действия: ${card.title}`}
        onClick={(e) => actions.menu(e, card)}
      />
    </div>
  );
}
export function CollectionActionsProvider({
  state,
  open,
  play,
  act,
  notice,
  pinned,
  toggle,
  ready,
  albumSaved,
  albumReady,
  likeAlbum,
  children,
}: {
  state: State;
  open: (card: Card) => void;
  play: (tracks: Track[], index: number, source: string) => Promise<void>;
  act: <T = any>(
    method: string,
    params?: Record<string, unknown>,
  ) => Promise<T | undefined>;
  notice: (message: string) => void;
  pinned: (card: Card) => boolean;
  toggle: (card: Card) => void;
  ready: boolean;
  albumSaved: (card: Card) => boolean;
  albumReady: boolean;
  likeAlbum: (card: Card) => Promise<void>;
  children: ReactNode;
}) {
  const [target, setTarget] = useState<(MenuAnchor & { card: Card }) | null>(
    null,
  );
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const stateRef = useRef(state);
  stateRef.current = state;
  const menu: Actions["menu"] = (event, card) => {
    if (card.type === "artist" || card.id === "link") return;
    setTarget({ ...menuAnchor(event), card });
  };
  const enqueue = async (card: Card, mode: "play" | "next" | "end") => {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    const scope = `${state.demo}:${state.profile?.uid}`;
    try {
      const data = await bridge.rpc<Collection>("collection", {
        type: card.type,
        id: card.id,
        owner: card.owner,
      });
      const current = stateRef.current;
      if (scope !== `${current.demo}:${current.profile?.uid}`) return;
      const tracks = data.tracks.filter(
        (t) => t.available !== false && !(current.blockExplicit && t.explicit),
      );
      if (!tracks.length) {
        notice(
          current.blockExplicit
            ? "Нет доступных треков с учётом фильтра 18+"
            : "В этой подборке нет доступных треков",
        );
        return;
      }
      if (mode === "play") await play(tracks, 0, data.title || card.title);
      else {
        const result = await act("queue", {
          action: "add",
          tracks,
          next: mode === "next",
        });
        if (result)
          notice(
            mode === "next"
              ? `«${card.title}» будет играть следующим`
              : `«${card.title}» добавлен в конец очереди`,
          );
      }
    } finally {
      pending.current = false;
      setBusy(false);
    }
  };
  const saveAlbum = async (card: Card) => {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    try {
      await likeAlbum(card);
    } finally {
      pending.current = false;
      setBusy(false);
    }
  };
  useEffect(() => setTarget(null), [state.profile?.uid, state.demo]);
  const rows: MenuRow[] = [];
  if (target) {
    const card = target.card;
    const isPinned = pinned(card);
    const url =
      card.type === "album"
        ? `https://music.yandex.ru/album/${encodeURIComponent(card.id)}`
        : `https://music.yandex.ru/users/${encodeURIComponent(card.ownerLogin || card.owner || "")}/playlists/${encodeURIComponent(card.id)}`;
    rows.push(
      { label: "Открыть", icon: ArrowUpRight, run: () => open(card) },
      {
        label: "Слушать",
        icon: Play,
        disabled: busy,
        run: () => enqueue(card, "play"),
      },
      {
        label: "Играть следующим",
        icon: ListStart,
        disabled: busy,
        run: () => enqueue(card, "next"),
      },
      {
        label: "В конец очереди",
        icon: ListEnd,
        disabled: busy,
        run: () => enqueue(card, "end"),
      },
      ...(card.type === "album"
        ? [
            {
              label: albumSaved(card)
                ? "Убрать альбом из коллекции"
                : "Добавить альбом в коллекцию",
              icon: Heart,
              separator: true,
              disabled: !albumReady || busy,
              run: () => saveAlbum(card),
            },
          ]
        : []),
      {
        label: isPinned
          ? "Открепить из «Твоей музыки»"
          : "Закрепить в «Твоей музыке»",
        icon: isPinned ? PinOff : Pin,
        separator: true,
        disabled: !ready,
        run: () => toggle(card),
      },
      {
        label: "Поделиться · скопировать ссылку",
        icon: Share2,
        separator: true,
        disabled: state.demo || (card.type === "playlist" && !card.owner),
        run: async () => {
          await bridge.copy(url);
          notice("Ссылка скопирована");
        },
      },
      {
        label: "Открыть в Яндекс Музыке",
        icon: ExternalLink,
        disabled: state.demo || (card.type === "playlist" && !card.owner),
        run: () => bridge.open(url),
      },
    );
  }
  return (
    <CollectionActions.Provider
      value={{
        menu,
        pinned,
        toggle,
        ready,
        albumSaved,
        albumReady: albumReady && !busy,
        likeAlbum: saveAlbum,
      }}
    >
      {children}
      {target && (
        <ContextMenu
          anchor={target}
          title={target.card.title}
          label={`Действия ${target.card.type === "album" ? "с альбомом" : "с плейлистом"} ${target.card.title}`}
          rows={rows}
          close={() => setTarget(null)}
          notice={notice}
        />
      )}
    </CollectionActions.Provider>
  );
}
