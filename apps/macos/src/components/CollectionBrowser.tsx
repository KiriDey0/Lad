// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useState, type ReactNode } from "react";
import {
  ChevronLeft,
  ChevronRight,
  Disc3,
  Folder,
  FolderPlus,
  Heart,
  ListMusic,
  RefreshCw,
  Search,
  Trash2,
  Users,
  type LucideIcon,
} from "lucide-react";
import { bridge } from "../bridge";
import type { Card, State, Track } from "../types";
import { Art, Button, Empty, IconButton, Skeleton } from "./ui";
import { useCollectionActions } from "./CollectionActions";

type Library = {
  playlists: Card[];
  albums: Card[];
  artists?: Card[];
  ownPlaylists?: Card[];
  likedPlaylists?: Card[];
  artistsError?: string;
};
type FolderInfo = {
  id: string;
  name: string;
  path: string;
  status: string;
  count: number;
  error: string;
};
type Summary = { folders: FolderInfo[]; total: number; scanning: boolean };
type Section =
  "overview" | "playlists" | "albums" | "artists" | "tracks" | "folders";
const names: Record<Section, string> = {
  overview: "Коллекция",
  playlists: "Плейлисты",
  albums: "Альбомы",
  artists: "Исполнители",
  tracks: "Треки",
  folders: "Папки",
};
const emptyLibrary: Library = {
  playlists: [],
  albums: [],
  artists: [],
  ownPlaylists: [],
  likedPlaylists: [],
};

export function CollectionBrowser({
  state,
  refresh,
  open,
  favorite,
  tracksView,
  notice,
}: {
  state: State;
  refresh: number;
  open: (card: Card) => void;
  favorite: () => void;
  tracksView: (tracks: Track[], title: string, compact?: boolean, collectionId?:string) => ReactNode;
  notice: (message: string) => void;
}) {
  const actions = useCollectionActions();
  const [source, setSource] = useState("all");
  const [section, setSection] = useState<Section>("overview");
  const [likedGroup, setLikedGroup] = useState(false);
  const [query, setQuery] = useState("");
  const [revision, setRevision] = useState(0);
  const [remoteRevision, setRemoteRevision] = useState(0);
  const [remote, setRemote] = useState<Library>(emptyLibrary);
  const [local, setLocal] = useState<Library>(emptyLibrary);
  const [summary, setSummary] = useState<Summary>({
    folders: [],
    total: 0,
    scanning: false,
  });
  const [liked, setLiked] = useState<{ tracks: Track[]; total: number }>({
    tracks: [],
    total: 0,
  });
  const [localLiked, setLocalLiked] = useState<{
    tracks: Track[];
    total: number;
  }>({ tracks: [], total: 0 });
  const [localTracks, setLocalTracks] = useState<Track[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [localError, setLocalError] = useState("");
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    const off = bridge.onEvent(({ event }) => {
      if (event === "libraryChanged") {
        clearTimeout(timer);
        timer = setTimeout(() => setRevision((v) => v + 1), 250);
      }
    });
    return () => {
      off();
      clearTimeout(timer);
    };
  }, []);
  useEffect(() => {
    let cancelled = false;
    if (!state.profile) {
      setRemote(emptyLibrary);
      setLiked({ tracks: [], total: 0 });
      setError("");
      return;
    }
    setLoading(true);
    setError("");
    Promise.all([
      bridge.rpc<Library>("library"),
      bridge.rpc<{ tracks: Track[]; total: number }>("liked", { limit: 8 }),
    ])
      .then(([library, likes]) => {
        if (!cancelled) {
          setRemote(library);
          setLiked(likes);
        }
      })
      .catch((e) => {
        if (!cancelled) setError(e.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [state.profile?.uid, state.demo, refresh, remoteRevision]);
  useEffect(() => {
    let cancelled = false;
    setLocalError("");
    Promise.all([
      bridge.rpc<Summary>("localSummary"),
      bridge.rpc<{ cards: Card[] }>("localBrowse", {
        kind: "albums",
        limit: 5000,
      }),
      bridge.rpc<{ cards: Card[] }>("localBrowse", {
        kind: "artists",
        limit: 5000,
      }),
      bridge.rpc<{ cards: Card[] }>("localBrowse", { kind: "playlists" }),
      bridge.rpc<{ tracks: Track[]; total: number }>("localLiked"),
      section === "tracks"
        ? bridge.rpc<{ tracks: Track[] }>("localBrowse", {
            kind: "tracks",
            limit: 5000,
          })
        : Promise.resolve({ tracks: [] }),
    ])
      .then(([s, albums, artists, playlists, likes, ts]) => {
        if (!cancelled) {
          setSummary(s);
          setLocal({
            albums: albums.cards,
            artists: artists.cards,
            playlists: playlists.cards,
            ownPlaylists: playlists.cards,
            likedPlaylists: [],
          });
          setLocalLiked(likes);
          setLocalTracks(ts.tracks);
        }
      })
      .catch((e) => {
        if (!cancelled) setLocalError(e.message);
      })
      .finally(() => {
        if (!cancelled && !state.profile) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [revision, section, refresh, state.profile?.uid]);
  const localAction = async (action: "add" | "restore", id?: string) => {
    try {
      if (!bridge.localFiles) {
        notice("Добавление папки доступно в установленном Ладе");
        return;
      }
      await bridge.localFiles(action, id);
      setRevision((v) => v + 1);
    } catch (e) {
      notice((e as Error).message);
    }
  };
  const run = async (method: string, params: Record<string, unknown> = {}) => {
    try {
      await bridge.rpc(method, params);
      setRevision((v) => v + 1);
    } catch (e) {
      notice((e as Error).message);
    }
  };
  const remoteVisible = source !== "local",
    localVisible = source !== "yandex";
  const albums = [
    ...(remoteVisible ? remote.albums : []),
    ...(localVisible ? local.albums : []),
  ];
  const artists = [
    ...(remoteVisible ? remote.artists || [] : []),
    ...(localVisible ? local.artists || [] : []),
  ];
  const own = [
    ...(remoteVisible ? remote.ownPlaylists || remote.playlists : []),
    ...(localVisible ? local.playlists : []),
  ];
  const saved = remoteVisible ? remote.likedPlaylists || [] : [];
  const favorites = [
    ...(remoteVisible ? liked.tracks : []),
    ...(localVisible ? localLiked.tracks : []),
  ].slice(0, 8);
  const favoriteTotal =
    (remoteVisible ? liked.total : 0) + (localVisible ? localLiked.total : 0);
  const groups: { section: Section; Icon: LucideIcon; caption: string }[] = [
    {
      section: "playlists",
      Icon: ListMusic,
      caption: `${own.length + saved.length} плейлистов`,
    },
    { section: "albums", Icon: Disc3, caption: `${albums.length} альбомов` },
    {
      section: "artists",
      Icon: Users,
      caption: `${artists.length} исполнителей`,
    },
  ];
  if (localVisible)
    groups.push({
      section: "folders",
      Icon: Folder,
      caption: `${summary.folders.length} папок · ${summary.total} треков`,
    });
  const openFavorites = () =>
    state.demo || source === "yandex"
      ? favorite()
      : open({
          id: source === "local" ? "local-liked" : "local-all-liked",
          source: "local",
          type: "playlist",
          title: "Мне нравится",
        });
  const navigate = (value: Section) => {
    setSection(value);
    setQuery("");
  };
  const cardGrid = (cards: Card[], round = false) => {
    const filtered = cards.filter((c) =>
      (c.title + " " + (c.subtitle || ""))
        .toLocaleLowerCase()
        .includes(query.toLocaleLowerCase()),
    );
    return filtered.length ? (
      <div
        className={round ? "collection-artist-grid" : "collection-card-grid"}
      >
        {filtered.map((c) => (
          <button
            key={`${c.source}:${c.type}:${c.owner}:${c.id}`}
            className={`music-card${round ? " collection-artist" : ""}`}
            onClick={() => open(c)}
            onContextMenu={(e) => actions.menu(e, c)}
          >
            <Art item={c} />
            <strong title={c.title}>{c.title}</strong>
            <small>
              {c.source === "local"
                ? "На устройстве"
                : c.subtitle || "Исполнитель"}
            </small>
          </button>
        ))}
      </div>
    ) : (
      <Empty
        title={query ? "Ничего не найдено" : "Здесь пока пусто"}
        icon={round ? Users : Disc3}
      >
        {query
          ? "Попробуй другое название"
          : source === "local"
            ? "Добавь папку с музыкой"
            : "Сохранённая музыка появится здесь"}
      </Empty>
    );
  };
  return (
    <div className="collection-browser">
      <div className="page-heading collection-browser-heading">
        <div>
          {section !== "overview" && (
            <button
              className="collection-back"
              onClick={() => navigate("overview")}
            >
              <ChevronLeft size={16} />
              Коллекция
            </button>
          )}
          <h1>{names[section]}</h1>
        </div>
        <Button
          icon={FolderPlus}
          variant="secondary"
          onClick={() => void localAction("add")}
        >
          Добавить папку
        </Button>
      </div>
      <div className="filter-tabs" role="group" aria-label="Источник коллекции">
        {[
          ["all", "Всё"],
          ["yandex", "Яндекс Музыка"],
          ["local", "На устройстве"],
        ].map(([id, title]) => (
          <button
            key={id}
            className={source === id ? "active" : ""}
            aria-pressed={source === id}
            onClick={() => {
              setSource(id);
              setSection("overview");
              setQuery("");
            }}
          >
            {title}
          </button>
        ))}
      </div>
      {section !== "overview" && (
        <div className="collection-section-tabs">
          {(
            [
              "playlists",
              "albums",
              "artists",
              ...(localVisible ? ["tracks", "folders"] : []),
            ] as Section[]
          ).map((s) => (
            <button
              key={s}
              className={section === s ? "active" : ""}
              onClick={() => navigate(s)}
            >
              {names[s]}
            </button>
          ))}
        </div>
      )}
      {error && remoteVisible && (
        <div className="inline-error">
          <p>{error}</p>
          <Button
            variant="secondary"
            icon={RefreshCw}
            onClick={() => setRemoteRevision((v) => v + 1)}
          >
            Повторить
          </Button>
        </div>
      )}
      {localError && localVisible && <p role="alert">{localError}</p>}
      {summary.scanning && localVisible && (
        <p className="muted" role="status">
          Читаем папки · найдено {summary.total} треков
        </p>
      )}
      {loading ? (
        <Skeleton />
      ) : section === "overview" ? (
        <>
          <section className="liked-preview" aria-label="Любимые треки в коллекции">
            <button className="liked-preview-heading" onClick={openFavorites}>
              <span className="liked-cover">
                <Heart size={26} />
              </span>
              <span>
                <strong>Мне нравится</strong>
                <small>{favoriteTotal} треков</small>
              </span>
              <ChevronRight size={20} />
            </button>
            {favorites.length ? (
              tracksView(favorites, "Мне нравится", true, source==="yandex"?"liked":source==="local"?"local-liked":"local-all-liked")
            ) : (
              <p className="muted">Отмечай любимые треки сердечком</p>
            )}
          </section>
          <section className="music-section">
            <button
              className="collection-section-heading"
              onClick={() => navigate("artists")}
            >
              <h2>Любимые исполнители</h2>
              <ChevronRight size={20} />
            </button>
            {remoteVisible && remote.artistsError && (
              <p className="muted">{remote.artistsError}</p>
            )}
            {artists.length ? (
              cardGrid(artists.slice(0, 12), true)
            ) : (
              <p className="muted">
                Сохранённые исполнители и исполнители локальных альбомов
                появятся здесь
              </p>
            )}
          </section>
          <section className="music-section">
            <h2>В коллекции</h2>
            <div className="collection-group-grid">
              {groups.map(({ section: s, Icon, caption }) => (
                <button
                  key={s}
                  className="collection-group"
                  onClick={() => navigate(s as Section)}
                >
                  <Icon size={24} />
                  <span>
                    <strong>{names[s as Section]}</strong>
                    <small>{caption}</small>
                  </span>
                  <ChevronRight size={18} />
                </button>
              ))}
            </div>
          </section>
        </>
      ) : (
        <>
          {section !== "folders" && (
            <div className="search-input collection-search">
              <Search size={18} />
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                aria-label="Поиск в коллекции"
                placeholder={`Искать: ${names[section].toLocaleLowerCase()}`}
              />
            </div>
          )}
          {section === "playlists" ? (
            <>
              <div
                className="filter-tabs"
                role="group"
                aria-label="Группа плейлистов"
              >
                <button
                  className={!likedGroup ? "active" : ""}
                  onClick={() => setLikedGroup(false)}
                >
                  Вы собрали · {own.length + 1}
                </button>
                <button
                  className={likedGroup ? "active" : ""}
                  onClick={() => setLikedGroup(true)}
                >
                  Вам понравились · {saved.length}
                </button>
              </div>
              {!likedGroup && (
                <button
                  className="liked-preview-heading"
                  onClick={openFavorites}
                >
                  <span className="liked-cover">
                    <Heart />
                  </span>
                  <span>
                    <strong>Мне нравится</strong>
                    <small>{favoriteTotal} треков</small>
                  </span>
                  <ChevronRight size={20} />
                </button>
              )}
              {!likedGroup && localVisible && (
                <Button
                  icon={ListMusic}
                  variant="secondary"
                  onClick={() =>
                    window.dispatchEvent(
                      new CustomEvent("lad:local-playlist", { detail: [] }),
                    )
                  }
                >
                  Создать плейлист на устройстве
                </Button>
              )}
              {cardGrid(likedGroup ? saved : own)}
            </>
          ) : section === "albums" ? (
            cardGrid(albums)
          ) : section === "artists" ? (
            cardGrid(artists, true)
          ) : section === "tracks" ? (
            tracksView(
              localTracks.filter((t) =>
                (t.title + " " + t.artist)
                  .toLocaleLowerCase()
                  .includes(query.toLocaleLowerCase()),
              ),
              "На устройстве",
            )
          ) : section === "folders" ? (
            <div className="local-folders">
              {summary.folders.length ? (
                summary.folders.map((f) => (
                  <div className="local-folder" key={f.id}>
                    <Folder size={24} />
                    <button
                      className="local-folder-label"
                      onClick={() =>
                        open({
                          id: f.id,
                          type: "album",
                          source: "local",
                          title: f.name,
                        })
                      }
                    >
                      <strong>{f.name}</strong>
                      <small title={f.path}>{f.path}</small>
                      <small>
                        {f.count} треков
                        {f.status === "scanning" ? " · Обновляем" : ""}
                      </small>
                      {f.error && <small role="status">{f.error}</small>}
                    </button>
                    <IconButton
                      icon={RefreshCw}
                      label="Обновить папку"
                      onClick={() => void run("localRescan", { id: f.id })}
                    />
                    <IconButton
                      icon={Folder}
                      label="Найти папку заново"
                      onClick={() => void localAction("restore", f.id)}
                    />
                    <IconButton
                      icon={Trash2}
                      label="Убрать папку из Лада; файлы останутся"
                      onClick={() =>
                        void run("localRemoveFolder", { id: f.id })
                      }
                    />
                  </div>
                ))
              ) : (
                <Empty
                  icon={FolderPlus}
                  title="Добавь папку с музыкой"
                  action={
                    <Button
                      icon={FolderPlus}
                      onClick={() => void localAction("add")}
                    >
                      Выбрать папку
                    </Button>
                  }
                >
                  Вложенные папки тоже попадут в библиотеку. Файлы останутся на
                  своих местах.
                </Empty>
              )}
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}
