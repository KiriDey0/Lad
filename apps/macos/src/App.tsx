import { WaveScene } from "./wave/WaveScene";
// SPDX-License-Identifier: AGPL-3.0-only
import {
  useState,
  useEffect,
  useCallback,
  useRef,
  type ReactNode,
  type CSSProperties,
} from "react";
import {
  AudioLines,
  Search,
  Heart,
  Library,
  Settings,
  ChevronRight,
  ChevronLeft,
  Play,
  Pause,
  SkipBack,
  SkipForward,
  Shuffle,
  Repeat,
  Repeat1,
  Volume2,
  VolumeX,
  ListMusic,
  Mic2,
  Monitor,
  Speaker,
  Plus,
  ArrowUpRight,
  LogOut,
  X,
  GripVertical,
  Trash2,
  ArrowUp,
  ArrowDown,
  Link,
  Check,
  Radio,
  RefreshCw,
  Wifi,
  ExternalLink,
  Copy,
  Keyboard,
  SlidersHorizontal,
  ArrowRight,
  LoaderCircle,
  Disc3,
  Music2,
  Headphones,
  Info,
  Pin,
  AlertCircle,
} from "lucide-react";
import clsx from "clsx";
import { UpdateSection } from "./components/UpdateSection";
import { bridge } from "./bridge";
import { useGlassPointer } from "./useGlassPointer";
import {
  initialState,
  type State,
  type Track,
  type Card,
  type Lyrics,
  type SearchResult,
  type Collection,
} from "./types";
import {
  Button,
  IconButton,
  Art,
  Empty,
  Skeleton,
  duration,
} from "./components/ui";
import { AudioEngine } from "./components/AudioEngine";
import { ExplicitBadge } from "./components/ExplicitBadge";
import {
  TrackActionsProvider,
  TrackMetadata,
  TrackAlbumLink,
  ArtistLinks,
  useTrackActions,
} from "./components/TrackActions";
import {
  CollectionActionsProvider,
  CollectionControls,
  useCollectionActions,
} from "./components/CollectionActions";
import { usePinnedCollections, collectionKey } from "./usePinnedCollections";
import { TrackProgress } from "./components/TrackProgress";
import { loadTheme, applyTheme, defaultTheme, type Theme } from "./theme";

type Page =
  "home" | "search" | "collection" | "liked" | "now" | "settings" | "detail";
type Content = {
  cards: Card[];
  tracks: Track[];
  title?: string;
  total?: number;
  albums?: Card[];
  tracksTitle?: string;
  likesError?: string;
};
export default function App() {
  useGlassPointer();
  const [state, setState] = useState<State>(initialState);
  const [page, setPage] = useState<Page>("home");
  const [queueOpen, setQueueOpen] = useState(true);
  const [modal, setModal] = useState<
    "login" | "devices" | "link" | "wave" | null
  >(null);
  const [toast, setToast] = useState("");
  const [busy, setBusy] = useState(false);
  const [content, setContent] = useState<Content>({ cards: [], tracks: [] });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [searchType, setSearchType] = useState("all");
  const [searchResult, setSearchResult] = useState<SearchResult | null>(null);
  const [searchPage, setSearchPage] = useState(0);
  const [detail, setDetail] = useState<Card | null>(null);
  const scope = state.profile
    ? `${state.demo ? "demo" : "account"}:${state.profile.uid}`
    : null;
  const [library, setLibrary] = useState<{
    scope: string;
    playlists: Card[];
    albums: Card[];
  } | null>(null);
  const [collectionTab, setCollectionTab] = useState<"playlists" | "albums">(
    "playlists",
  );
  const [refreshKey, setRefreshKey] = useState(0);
  const [theme, setTheme] = useState<Theme>(loadTheme);
  const searchRef = useRef<HTMLInputElement>(null);
  const requestId = useRef(0);
  const stateRef = useRef(state);
  stateRef.current = state;
  const notice = useCallback((message: string) => setToast(message), []);
  const pins = usePinnedCollections(
    scope,
    library?.scope === scope ? (library?.playlists ?? null) : null,
    notice,
  );
  const albumSaved = (card: Card) =>
    library?.scope === scope && !!library?.albums.some((a) => a.id === card.id);
  const act = useCallback(
    async <T,>(
      method: string,
      params: Record<string, unknown> = {},
    ): Promise<T | undefined> => {
      try {
        return await bridge.rpc<T>(method, params);
      } catch (e) {
        notice((e as Error).message);
        return undefined;
      }
    },
    [notice],
  );
  useEffect(() => {
    const off = bridge.onEvent(({ event, data }) => {
      if (event === "state") setState(data);
      if (event === "notice") notice(data.message);
    });
    void bridge
      .rpc<State>("snapshot")
      .then(setState)
      .catch(() => {});
    return off;
  }, [notice]);
  useEffect(() => {
    if (toast) {
      const t = setTimeout(() => setToast(""), 6500);
      return () => clearTimeout(t);
    }
  }, [toast]);
  useEffect(() => applyTheme(theme), [theme]);
  useEffect(() => {
    if (!state.profile) {
      setLibrary(null);
      return;
    }
    let cancelled = false;
    void bridge
      .rpc<{ playlists: Card[]; albums: Card[] }>("library")
      .then((x) => {
        if (!cancelled && scope) setLibrary({ ...x, scope });
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [state.profile?.uid, state.demo, refreshKey]);
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      const typing =
        ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName) ||
        target.isContentEditable;
      if ((e.metaKey || e.ctrlKey) && e.key === "k") {
        e.preventDefault();
        setPage("search");
        requestAnimationFrame(() => searchRef.current?.focus());
        return;
      }
      if (e.key === "Escape") {
        setModal(null);
        return;
      }
      if (typing || modal) return;
      if (e.code === "Space" && stateRef.current.profile) {
        e.preventDefault();
        void act("control", { action: "toggle" });
      }
      if ((e.metaKey || e.ctrlKey) && e.key === "ArrowRight") {
        e.preventDefault();
        void act("control", { action: "next" });
      }
      if ((e.metaKey || e.ctrlKey) && e.key === "ArrowLeft") {
        e.preventDefault();
        void act("control", { action: "previous" });
      }
      if ((e.metaKey || e.ctrlKey) && e.key === "l") {
        e.preventDefault();
        setPage("now");
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [act, modal]);
  const authenticated = !!state.profile;
  useEffect(() => {
    if (!authenticated || page === "now" || page === "settings") return;
    if (page === "search" && !query.trim()) {
      setSearchResult(null);
      setLoading(false);
      setError("");
      return;
    }
    const id = ++requestId.current;
    setError("");
    setLoading(true);
    const timeout = setTimeout(
      async () => {
        try {
          if (page === "search") {
            const data = await bridge.rpc<SearchResult>("search", {
              query: query.trim(),
              page: searchPage,
              type: searchType,
            });
            if (id === requestId.current) {
              setSearchResult((prev) =>
                searchPage > 0 && prev
                  ? {
                      ...data,
                      tracks: [...prev.tracks, ...data.tracks],
                      albums: [...prev.albums, ...data.albums],
                      artists: [...prev.artists, ...data.artists],
                      playlists: [...prev.playlists, ...data.playlists],
                    }
                  : data,
              );
            }
          } else if (page === "liked") {
            const data = await bridge.rpc<{ tracks: Track[]; total: number }>(
              "liked",
            );
            if (id === requestId.current)
              setContent({
                cards: [],
                tracks: data.tracks,
                total: data.total,
                title: "Мне нравится",
              });
          } else if (page === "collection") {
            const [data, likes] = await Promise.all([
              bridge.rpc<{ playlists: Card[]; albums: Card[] }>("library"),
              bridge
                .rpc<{ tracks: Track[]; total: number }>("liked", { limit: 8 })
                .then((value) => ({ ...value, error: "" }))
                .catch(() => ({
                  tracks: [],
                  total: undefined,
                  error: "Не удалось загрузить любимые треки",
                })),
            ]);
            if (id === requestId.current)
              setContent({
                cards: data.playlists,
                albums: data.albums,
                tracks: likes.tracks,
                total: likes.total,
                likesError: likes.error,
              });
          } else if (page === "detail" && detail && detail.id === "link") {
            setLoading(false);
            return;
          } else if (page === "detail" && detail) {
            const data = await bridge.rpc<Collection>("collection", {
              type: detail.type,
              id: detail.id,
              owner: detail.owner,
            });
            if (id === requestId.current)
              setContent({ cards: [], tracks: data.tracks, title: data.title });
          } else {
            const data = await bridge.rpc<Content>("home");
            if (id === requestId.current) setContent(data);
          }
        } catch (e) {
          if (id === requestId.current) setError((e as Error).message);
        } finally {
          if (id === requestId.current) setLoading(false);
        }
      },
      page === "search" ? 320 : 0,
    );
    return () => {
      clearTimeout(timeout);
      requestId.current++;
    };
  }, [
    authenticated,
    state.profile?.uid,
    state.demo,
    page,
    query,
    searchType,
    searchPage,
    detail,
    refreshKey,
  ]);
  const openCard = (card: Card) => {
    setDetail(card);
    setPage("detail");
  };
  const play = async (tracks: Track[], index: number, source: string) => {
    if (
      state.output === "station" &&
      state.connection !== "connected" &&
      !state.demo
    ) {
      setModal("devices");
      return;
    }
    setBusy(true);
    await act("play", {
      tracks,
      index,
      source,
      collection:
        page === "liked" || source === "Мне нравится" ? "liked" : undefined,
    });
    setBusy(false);
  };
  const wave = async () => {
    setBusy(true);
    const result = await act("wave");
    setBusy(false);
    if (result) setPage("home");
  };
  const current = state.playback.track;
  const liked = (track: Track) => {
    const value =
      track.liked ??
      state.queue.tracks.find((t) => t.id === track.id)?.liked ??
      false;
    return value;
  };
  const toggleLike = async (track: Track) => {
    const value = !liked(track);
    const ok = await act("like", { id: track.id, liked: value });
    if (ok) {
      setContent((prev) => ({
        ...prev,
        tracks:
          page === "collection" && !value
            ? prev.tracks.filter((t) => t.id !== track.id)
            : prev.tracks.map((t) =>
                t.id === track.id ? { ...t, liked: value } : t,
              ),
        total:
          page === "collection" && prev.total !== undefined
            ? Math.max(0, prev.total + (value ? 1 : -1))
            : prev.total,
      }));
      setSearchResult((prev) =>
        prev
          ? {
              ...prev,
              tracks: prev.tracks.map((t) =>
                t.id === track.id ? { ...t, liked: value } : t,
              ),
            }
          : null,
      );
      if (current?.id === track.id)
        setState((prev) => ({
          ...prev,
          playback: { ...prev.playback, track: { ...track, liked: value } },
        }));
    }
  };
  const add = async (track: Track, next = false) => {
    const ok = await act("queue", { action: "add", tracks: [track], next });
    if (ok) notice(next ? "Трек будет следующим" : "Добавлено в очередь");
  };
  const nav = (target: Page) => {
    setPage(target);
    setError("");
  };
  return (
    <TrackActionsProvider
      state={state}
      open={openCard}
      play={play}
      add={add}
      like={toggleLike}
      act={act}
      notice={notice}
    >
      <CollectionActionsProvider
        state={state}
        open={openCard}
        play={play}
        act={act}
        notice={notice}
        pinned={pins.isPinned}
        toggle={pins.toggle}
        ready={pins.ready}
        albumSaved={albumSaved}
        albumReady={library?.scope === scope && !!library}
        likeAlbum={async (card) => {
          const liked = !albumSaved(card);
          const ok = await act("albumLike", { id: card.id, liked, card });
          if (
            ok &&
            stateRef.current.profile?.uid === state.profile?.uid &&
            stateRef.current.demo === state.demo
          ) {
            setLibrary((prev) =>
              prev?.scope === scope
                ? {
                    ...prev,
                    albums: liked
                      ? [...prev.albums.filter((a) => a.id !== card.id), card]
                      : prev.albums.filter((a) => a.id !== card.id),
                  }
                : prev,
            );
            setContent((prev) => ({
              ...prev,
              albums: liked
                ? [...(prev.albums || []).filter((a) => a.id !== card.id), card]
                : (prev.albums || []).filter((a) => a.id !== card.id),
            }));
            notice(
              liked
                ? "Альбом добавлен в коллекцию"
                : "Альбом убран из коллекции",
            );
          }
        }}
      >
        <div className="app-shell">
          <AudioEngine state={state} notice={notice} />
          <div className="titlebar" data-platform={bridge.platform}>
            <div className="titlebar-label">
              Лад <span>·</span> Музыка там, где ты
            </div>
            {state.demo && <span className="demo-badge">Демо · без звука</span>}
          </div>
          <div className="workspace">
            <aside className="sidebar">
              <button
                className="brand"
                onClick={() => nav("home")}
                aria-label="Лад — главная"
              >
                <AudioLines size={30} strokeWidth={1.5} />
                <span>лад</span>
                <span className="brand-dot" />
              </button>
              <nav
                aria-label="Основная навигация"
                className="flex flex-col gap-2"
              >
                <NavItem
                  icon={Radio}
                  label="Моя волна"
                  onClick={() =>
                    authenticated ? void wave() : setModal("login")
                  }
                />
                <NavItem
                  icon={Disc3}
                  label="Для тебя"
                  active={page === "home"}
                  onClick={() => nav("home")}
                />
                <NavItem
                  icon={Search}
                  label="Поиск"
                  active={page === "search"}
                  onClick={() => {
                    nav("search");
                    requestAnimationFrame(() => searchRef.current?.focus());
                  }}
                />
                <NavItem
                  icon={Library}
                  label="Коллекция"
                  active={page === "collection"}
                  onClick={() => nav("collection")}
                />
              </nav>
              <div className="sidebar-heading">
                <span>ТВОЯ МУЗЫКА</span>
                <IconButton
                  icon={Link}
                  label="Открыть ссылку Яндекс Музыки"
                  onClick={() => setModal("link")}
                />
              </div>
              <div className="sidebar-playlists">
                <NavItem
                  icon={Heart}
                  label="Мне нравится"
                  active={page === "liked"}
                  onClick={() => nav("liked")}
                />
                {pins.cards.map((card) => (
                  <PinnedShortcut
                    key={collectionKey(card)}
                    card={card}
                    active={
                      page === "detail" &&
                      !!detail &&
                      collectionKey(detail) === collectionKey(card)
                    }
                    open={openCard}
                  />
                ))}
                {authenticated && pins.ready && !pins.cards.length && (
                  <p className="sidebar-hint">
                    Закрепляй альбомы и плейлисты через их меню.
                  </p>
                )}
                {!authenticated && (
                  <p className="sidebar-hint">
                    Закрепления и любимые треки появятся после входа.
                  </p>
                )}
              </div>
              <div className="sidebar-actions">
                <NavItem
                  icon={Mic2}
                  label="Сейчас играет"
                  active={page === "now"}
                  onClick={() => nav("now")}
                />
                <NavItem
                  icon={Settings}
                  label="Настройки"
                  active={page === "settings"}
                  onClick={() => nav("settings")}
                />
                <button
                  className="account-button"
                  onClick={() =>
                    !authenticated ? setModal("login") : nav("settings")
                  }
                >
                  <span className="account-avatar">
                    {authenticated ? (
                      state.profile!.name.slice(0, 1)
                    ) : (
                      <Headphones size={20} />
                    )}
                  </span>
                  <span className="min-w-0">
                    <strong>{state.profile?.name || "Войти в Яндекс"}</strong>
                    <small>
                      {state.demo
                        ? "Знакомство с «Ладом»"
                        : state.profile?.plus
                          ? "Яндекс Плюс"
                          : "Твоя музыка и устройства"}
                    </small>
                  </span>
                  <ChevronRight size={16} />
                </button>
              </div>
            </aside>
            <main className={clsx("main", page === "now" && "main-now")}>
              <header className="main-header">
                <div className="flex items-center gap-2">
                  <IconButton
                    icon={ChevronLeft}
                    label="Вернуться на главную"
                    onClick={() => nav("home")}
                  />
                  <span className="header-location">
                    {
                      {
                        home: "Моя волна",
                        search: "Поиск",
                        collection: "Коллекция",
                        liked: "Мне нравится",
                        now: "Сейчас играет",
                        settings: "Настройки",
                        detail:
                          detail?.type === "artist"
                            ? "Исполнитель"
                            : detail?.type === "album"
                              ? "Альбом"
                              : "Плейлист",
                      }[page]
                    }
                  </span>
                </div>
                <button
                  className="device-selector"
                  onClick={() => setModal("devices")}
                >
                  {state.output === "local" ? (
                    <Monitor size={18} />
                  ) : (
                    <Speaker size={18} />
                  )}
                  <span>
                    {state.output === "local"
                      ? bridge.platform === "darwin"
                        ? "Этот Mac"
                        : "Этот компьютер"
                      : state.devices.find((d) => d.id === state.deviceId)
                          ?.name || "Станция Миди"}
                  </span>
                  <span
                    className={clsx(
                      "status-dot",
                      (state.output === "local" ||
                        state.connection === "connected" ||
                        state.demo) &&
                        "status-connected",
                    )}
                  />
                  <ChevronRight size={14} />
                </button>
              </header>
              <div
                className={clsx(
                  "page-content",
                  page === "now" && "page-lyrics",
                )}
              >
                {!authenticated && page !== "settings" ? (
                  <Welcome
                    onLogin={() => setModal("login")}
                    onDemo={() => {
                      void act("demo").then(() => {
                        setPage("now");
                      });
                    }}
                  />
                ) : page === "settings" ? (
                  <SettingsPage
                    state={state}
                    act={act}
                    theme={theme}
                    onTheme={setTheme}
                    onLogin={() => setModal("login")}
                    onDevices={() => setModal("devices")}
                    onLogout={() => {
                      void act("logout").then(() => setPage("home"));
                    }}
                  />
                ) : page === "now" ? (
                  <LyricsPage
                    track={current}
                    state={state}
                    theme={theme}
                    notice={notice}
                    seek={(value) =>
                      void act("control", { action: "seek", value })
                    }
                    onBrowse={() => nav("home")}
                    toggleLike={() => current && void toggleLike(current)}
                  />
                ) : (
                  <>
                    {page === "search" ? (
                      <>
                        <div className="page-heading">
                          <span className="eyebrow">
                            НАЙДЁТСЯ ТО, ЧТО ХОЧЕТСЯ
                          </span>
                          <h1>Поиск музыки</h1>
                        </div>
                        <div className="search-input">
                          <Search size={22} />
                          <input
                            ref={searchRef}
                            value={query}
                            placeholder="Трек, альбом, исполнитель или плейлист"
                            onChange={(e) => {
                              setQuery(e.target.value);
                              setSearchPage(0);
                            }}
                            aria-label="Поиск музыки"
                          />
                          {query ? (
                            <IconButton
                              icon={X}
                              label="Очистить поиск"
                              onClick={() => {
                                setQuery("");
                                setSearchPage(0);
                              }}
                            />
                          ) : (
                            <kbd>⌘ K</kbd>
                          )}
                        </div>
                        <div
                          className="filter-tabs"
                          role="tablist"
                          aria-label="Тип поиска"
                        >
                          {[
                            ["all", "Всё"],
                            ["track", "Треки"],
                            ["album", "Альбомы"],
                            ["artist", "Исполнители"],
                            ["playlist", "Плейлисты"],
                          ].map(([id, label]) => (
                            <button
                              key={id}
                              role="tab"
                              aria-selected={searchType === id}
                              className={clsx(searchType === id && "active")}
                              onClick={() => {
                                setSearchType(id);
                                setSearchPage(0);
                              }}
                            >
                              {label}
                            </button>
                          ))}
                        </div>
                      </>
                    ) : page === "home" ? (
                      <div className="home-hero wave-home">
                        <WaveScene
                          cover={current?.cover}
                          playing={state.playback.playing}
                          local={state.output === "local"}
                          intensity={theme.waveIntensity}
                        />
                        <div className="wave-heading">
                          <span className="eyebrow">ТВОЙ РИТМ</span>
                          <h1>Моя волна</h1>
                        </div>
                        {current ? (
                          <WaveNowPlaying
                            state={state}
                            theme={theme}
                            onExpand={() => nav("now")}
                          />
                        ) : (
                          <div className="wave-empty">
                            <Art
                              item={{ id: "orbit-hero", art: 0 }}
                              size="lg"
                            />
                            <p>
                              Знакомое и новое.
                              <br />В твоём настроении.
                            </p>
                          </div>
                        )}
                        <div className="wave-home-controls">
                          <Button
                            icon={
                              state.queue.source.startsWith("Моя волна") &&
                              state.queue.owned &&
                              state.playback.playing
                                ? Pause
                                : Play
                            }
                            onClick={() =>
                              state.queue.source.startsWith("Моя волна") &&
                              state.queue.owned
                                ? void act("control", { action: "toggle" })
                                : void wave()
                            }
                            loading={busy}
                          >
                            {state.queue.source.startsWith("Моя волна") &&
                            state.queue.owned
                              ? state.playback.playing
                                ? "Пауза"
                                : "Продолжить волну"
                              : "Включить Мою волну"}
                          </Button>
                          <Button
                            variant="secondary"
                            icon={SlidersHorizontal}
                            onClick={() => setModal("wave")}
                          >
                            Настроить
                          </Button>
                        </div>
                      </div>
                    ) : page === "liked" ? (
                      <div className="collection-heading">
                        <span className="liked-cover">
                          <Heart size={54} strokeWidth={1.2} />
                        </span>
                        <div>
                          <span className="eyebrow">ТВОЯ КОЛЛЕКЦИЯ</span>
                          <h1>Мне нравится</h1>
                          <p>
                            {content.total ?? content.tracks.length} треков · из
                            Яндекс Музыки
                          </p>
                        </div>
                      </div>
                    ) : page === "collection" ? (
                      <>
                        <div className="page-heading">
                          <span className="eyebrow">ВСЁ, ЧТО ТЫ СОХРАНИЛ</span>
                          <h1>Коллекция</h1>
                        </div>
                        <div
                          className="filter-tabs"
                          role="tablist"
                          aria-label="Раздел коллекции"
                        >
                          {(["playlists", "albums"] as const).map((type) => (
                            <button
                              key={type}
                              role="tab"
                              aria-selected={collectionTab === type}
                              className={clsx(
                                collectionTab === type && "active",
                              )}
                              onClick={() => setCollectionTab(type)}
                            >
                              {type === "playlists" ? "Плейлисты" : "Альбомы"}
                            </button>
                          ))}
                        </div>
                      </>
                    ) : (
                      <CollectionHeading card={detail}>
                        {detail && <Art item={detail} size="md" />}
                        <div className="collection-details">
                          <span className="eyebrow">
                            {detail?.type === "artist"
                              ? "ИСПОЛНИТЕЛЬ"
                              : detail?.type === "album"
                                ? "АЛЬБОМ"
                                : "ПЛЕЙЛИСТ"}
                          </span>
                          <h1>{content.title || detail?.title}</h1>
                          <p>
                            {detail?.subtitle ||
                              `${content.tracks.length} треков`}
                          </p>
                        </div>
                        {detail && <CollectionControls card={detail} />}
                      </CollectionHeading>
                    )}
                    {loading ? (
                      <Skeleton />
                    ) : error ? (
                      <Empty
                        icon={AlertCircle}
                        title="Не удалось загрузить музыку"
                        action={
                          <Button
                            icon={RefreshCw}
                            variant="secondary"
                            onClick={() => setRefreshKey((x) => x + 1)}
                          >
                            Повторить
                          </Button>
                        }
                      >
                        {error}
                      </Empty>
                    ) : page === "search" ? (
                      <>
                        {!query.trim() ? (
                          <Empty icon={Search} title="С чего начнём?">
                            Введи название. Можно найти трек, целый альбом или
                            чужой плейлист.
                          </Empty>
                        ) : (
                          searchResult && (
                            <>
                              {searchResult.tracks.length > 0 && (
                                <TrackList
                                  tracks={searchResult.tracks}
                                  title="Треки"
                                  state={state}
                                  source={`Поиск: ${query}`}
                                  play={play}
                                  add={add}
                                  like={toggleLike}
                                />
                              )}
                              {[
                                ["Исполнители", searchResult.artists],
                                ["Альбомы", searchResult.albums],
                                ["Плейлисты", searchResult.playlists],
                              ].map(
                                ([title, list]) =>
                                  (list as Card[]).length > 0 && (
                                    <CardGrid
                                      key={title as string}
                                      title={title as string}
                                      cards={list as Card[]}
                                      open={openCard}
                                    />
                                  ),
                              )}
                              {!searchResult.tracks.length &&
                                !searchResult.albums.length &&
                                !searchResult.playlists.length &&
                                !searchResult.artists.length && (
                                  <Empty
                                    icon={Search}
                                    title="Ничего не нашлось"
                                  >
                                    Попробуй другое название или имя
                                    исполнителя.
                                  </Empty>
                                )}
                              {((searchType === "track" &&
                                searchResult.tracks.length > 0 &&
                                searchResult.tracks.length <
                                  searchResult.total) ||
                                (searchType !== "all" &&
                                  searchType !== "track" &&
                                  [
                                    searchResult.artists,
                                    searchResult.albums,
                                    searchResult.playlists,
                                  ].some((x) => x.length > 0))) && (
                                <Button
                                  variant="secondary"
                                  onClick={() => setSearchPage((x) => x + 1)}
                                >
                                  Показать ещё
                                </Button>
                              )}
                            </>
                          )
                        )}
                      </>
                    ) : page === "collection" ? (
                      <>
                        {collectionTab === "playlists" && (
                          <section
                            className="liked-preview"
                            aria-label="Любимые треки в коллекции"
                          >
                            <button
                              className="liked-preview-heading"
                              onClick={() => nav("liked")}
                            >
                              <span className="liked-cover">
                                <Heart size={26} />
                              </span>
                              <span>
                                <strong>Мне нравится</strong>
                                <small>
                                  {content.total === undefined
                                    ? "Открыть любимые треки"
                                    : `${content.total} треков`}
                                </small>
                              </span>
                              <ChevronRight size={20} />
                            </button>
                            {content.likesError ? (
                              <p className="muted">{content.likesError}</p>
                            ) : (
                              <TrackList
                                compact
                                tracks={content.tracks}
                                title="Любимые треки"
                                state={state}
                                source="Мне нравится"
                                play={play}
                                add={add}
                                like={toggleLike}
                              />
                            )}
                          </section>
                        )}
                        <CardGrid
                          title={
                            collectionTab === "playlists"
                              ? "Твои плейлисты"
                              : "Любимые альбомы"
                          }
                          cards={
                            collectionTab === "playlists"
                              ? content.cards
                              : content.albums || []
                          }
                          open={openCard}
                        />
                      </>
                    ) : (
                      <>
                        {page === "home" && content.cards.length > 0 && (
                          <CardGrid
                            title="Собрано для тебя"
                            cards={content.cards.slice(0, 8)}
                            open={openCard}
                          />
                        )}
                        <TrackList
                          tracks={content.tracks}
                          title={
                            page === "home"
                              ? content.tracksTitle || "Треки для тебя"
                              : page === "liked"
                                ? "Любимые треки"
                                : "Треки"
                          }
                          state={state}
                          source={
                            page === "home"
                              ? content.tracksTitle || "Треки для тебя"
                              : content.title || "Треки"
                          }
                          play={play}
                          add={add}
                          like={toggleLike}
                        />
                        {page === "liked" &&
                        content.total &&
                        content.tracks.length < content.total ? (
                          <Button
                            variant="secondary"
                            onClick={async () => {
                              setBusy(true);
                              const data = await act<{ tracks: Track[] }>(
                                "liked",
                                {
                                  offset: content.tracks.length,
                                },
                              );
                              if (data)
                                setContent((prev) => ({
                                  ...prev,
                                  tracks: [...prev.tracks, ...data.tracks],
                                }));
                              setBusy(false);
                            }}
                            loading={busy}
                          >
                            Показать ещё
                          </Button>
                        ) : null}
                      </>
                    )}
                  </>
                )}
              </div>
            </main>
            {queueOpen && authenticated && (
              <QueuePanel
                state={state}
                act={act}
                close={() => setQueueOpen(false)}
              />
            )}
          </div>
          <Player
            state={state}
            act={act}
            onLyrics={() => nav("now")}
            onQueue={() => setQueueOpen((x) => !x)}
            queueOpen={queueOpen}
            onDevice={() => setModal("devices")}
            like={() => current && void toggleLike(current)}
          />
          {modal === "wave" && (
            <WaveSettingsModal close={() => setModal(null)} demo={state.demo} />
          )}
          {modal === "login" && (
            <LoginModal
              close={() => setModal(null)}
              notice={notice}
              act={act}
              state={state}
            />
          )}
          {modal === "devices" && (
            <DeviceModal
              state={state}
              act={act}
              close={() => setModal(null)}
              login={() => setModal("login")}
            />
          )}
          {modal === "link" && (
            <LinkModal
              close={() => setModal(null)}
              act={act}
              onResult={(data) => {
                setLoading(false);
                setError("");
                setDetail({ id: "link", type: "playlist", title: data.title });
                setContent({
                  cards: [],
                  tracks: data.tracks,
                  title: data.title,
                });
                setPage("detail");
                setModal(null);
              }}
            />
          )}
          {toast && (
            <div className="toast" role="status">
              <Info size={18} />
              <span>{toast}</span>
              <IconButton
                icon={X}
                label="Закрыть уведомление"
                onClick={() => setToast("")}
              />
            </div>
          )}
        </div>
      </CollectionActionsProvider>
    </TrackActionsProvider>
  );
}
function NavItem({
  icon: Icon,
  label,
  active,
  onClick,
}: {
  icon: typeof Search;
  label: string;
  active?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      className={clsx("nav-item", active && "selected")}
      aria-current={active ? "page" : undefined}
      onClick={onClick}
    >
      <Icon size={22} />
      <span>{label}</span>
    </button>
  );
}
function Welcome({
  onLogin,
  onDemo,
}: {
  onLogin: () => void;
  onDemo: () => void;
}) {
  return (
    <div className="welcome">
      <div className="welcome-copy">
        <span className="eyebrow">НЕОФИЦИАЛЬНЫЙ КЛИЕНТ ЯНДЕКС МУЗЫКИ</span>
        <h1>
          Музыка здесь.
          <br />
          Звук — где хочешь.
        </h1>
        <p>
          Твоя коллекция, очередь и слова любимых песен.
          <br />
          Слушай на компьютере. Включай на Станции.
        </p>
        <div className="welcome-actions">
          <Button icon={ArrowUpRight} onClick={onLogin}>
            Войти в Яндекс
          </Button>
          <Button icon={Play} variant="secondary" onClick={onDemo}>
            Посмотреть демо
          </Button>
        </div>
        <span className="welcome-note">
          Твой аккаунт и подписка Яндекс Плюс · без Bluetooth
        </span>
      </div>
      <div className="welcome-visual">
        <Art item={{ id: "welcome", art: 0 }} size="lg" />
        <div className="welcome-device">
          <span className="device-icon">
            <Speaker size={24} />
          </span>
          <div>
            <strong>Станция Миди</strong>
            <small>Музыка по локальной сети</small>
          </div>
          <AudioLines size={22} />
        </div>
      </div>
      <div className="welcome-features">
        {[
          [
            ListMusic,
            "Очередь под рукой",
            "Собирай, меняй порядок, добавляй следом.",
          ],
          [Mic2, "Слова в такт", "Синхронный текст, когда он есть у Яндекса."],
          [Monitor, "Одно приложение", "Компьютер и Станция в одном плеере."],
        ].map(([Icon, title, text]) => {
          const I = Icon as typeof ListMusic;
          return (
            <div key={title as string}>
              <I size={22} />
              <h3>{title as string}</h3>
              <p>{text as string}</p>
            </div>
          );
        })}
      </div>
    </div>
  );
}
function PinnedShortcut({
  card,
  active,
  open,
}: {
  card: Card;
  active: boolean;
  open: (card: Card) => void;
}) {
  const actions = useCollectionActions();
  return (
    <button
      className={clsx("playlist-shortcut", active && "selected")}
      onClick={() => open(card)}
      onContextMenu={(event) => actions.menu(event, card)}
    >
      <Art item={card} size="xs" />
      <span className="min-w-0">
        <strong>{card.title}</strong>
        <small>{card.type === "album" ? "Альбом" : "Плейлист"}</small>
      </span>
      <Pin className="shortcut-pin" size={12} aria-hidden="true" />
    </button>
  );
}
function CollectionHeading({
  card,
  children,
}: {
  card: Card | null;
  children: ReactNode;
}) {
  const actions = useCollectionActions();
  return (
    <div
      className="collection-heading"
      onContextMenu={(event) => card && actions.menu(event, card)}
    >
      {children}
    </div>
  );
}
function CardGrid({
  title,
  cards,
  open,
}: {
  title: string;
  cards: Card[];
  open: (card: Card) => void;
}) {
  const actions = useCollectionActions();
  return (
    <section className="music-section">
      <h2>{title}</h2>
      {cards.length ? (
        <div className="card-grid">
          {cards.map((card, i) => (
            <button
              className="music-card"
              key={`${card.type}:${card.owner}:${card.id}:${i}`}
              onClick={() => open(card)}
              onContextMenu={(event) => actions.menu(event, card)}
            >
              <div className="relative">
                <Art item={card} />
                <span className="card-play">
                  <ArrowUpRight size={20} />
                </span>
              </div>
              <strong>{card.title}</strong>
              <small>{card.subtitle}</small>
            </button>
          ))}
        </div>
      ) : (
        <Empty icon={Library} title="Здесь пока пусто">
          Сохранённые альбомы и плейлисты появятся после обновления коллекции.
        </Empty>
      )}
    </section>
  );
}
function TrackList({
  tracks,
  title,
  state,
  source,
  play,
  add,
  like,
  compact = false,
}: {
  compact?: boolean;
  tracks: Track[];
  title: string;
  state: State;
  source: string;
  play: (tracks: Track[], index: number, source: string) => void;
  add: (track: Track, next?: boolean) => void;
  like: (track: Track) => void;
}) {
  const [filter, setFilter] = useState("");
  const actions = useTrackActions();
  const filtered = tracks
    .map((track, index) => ({ track, index }))
    .filter(({ track }) =>
      (track.title + " " + track.artist)
        .toLowerCase()
        .includes(filter.toLowerCase()),
    );
  return (
    <section className={clsx("music-section", compact && "compact-tracks")}>
      {!compact && (
        <div className="section-title">
          <h2>
            {title} <span>{tracks.length || ""}</span>
          </h2>
          <div className="flex items-center gap-2">
            {tracks.length > 8 && (
              <label className="inline-search">
                <Search size={16} />
                <input
                  value={filter}
                  onChange={(e) => setFilter(e.target.value)}
                  placeholder="В списке"
                  aria-label="Поиск в списке"
                />
              </label>
            )}
            {tracks.length > 0 && (
              <Button
                variant="tonal"
                icon={Play}
                onClick={() => play(tracks, 0, source)}
              >
                Слушать
              </Button>
            )}
          </div>
        </div>
      )}
      {!tracks.length ? (
        <Empty title="Пока нет треков">
          Они появятся здесь, когда ты добавишь музыку в коллекцию.
        </Empty>
      ) : (
        <div className="track-list">
          {filtered.map(({ track: t, index }) => (
            <div
              key={`${t.id}:${index}`}
              onContextMenu={(e) =>
                actions.menu(e, t, () => play(tracks, index, source))
              }
              className={clsx(
                "track-row",
                state.playback.track?.id === t.id && "track-current",
                (t.available === false ||
                  (state.blockExplicit && t.explicit)) &&
                  "track-unavailable",
              )}
            >
              <button
                className="track-play"
                onClick={() => play(tracks, index, source)}
                aria-label={`Включить ${t.title}`}
                disabled={
                  t.available === false || (state.blockExplicit && t.explicit)
                }
              >
                {state.playback.track?.id === t.id && state.playback.playing ? (
                  <AudioLines size={16} />
                ) : (
                  <>
                    <span className="track-number">{index + 1}</span>
                    <Play size={16} className="track-play-icon" />
                  </>
                )}
              </button>
              <Art item={t} size="xs" />
              <div className="track-name">
                <TrackMetadata track={t} />
              </div>
              <span className="track-album">
                <TrackAlbumLink track={t}>{t.album}</TrackAlbumLink>
              </span>
              <span className="track-duration">{duration(t.duration)}</span>
              <IconButton
                icon={Heart}
                active={
                  t.liked ||
                  state.queue.tracks.find((x) => x.id === t.id)?.liked
                }
                label={t.liked ? "Убрать из любимых" : "Добавить в любимые"}
                onClick={() => like(t)}
                className="track-like"
              />
              <IconButton
                icon={Plus}
                label={`Добавить ${t.title} в очередь`}
                onClick={(e) =>
                  actions.menu(e, t, () => play(tracks, index, source))
                }
              />
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
type Act = <T = any>(
  method: string,
  params?: Record<string, unknown>,
) => Promise<T | undefined>;
function usePosition(playback: State["playback"], output: State["output"]) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 100);
    return () => clearInterval(t);
  }, []);
  const elapsed = playback.playing
    ? Math.max(0, (now - playback.positionAt) / 1000)
    : 0;
  return Math.min(
    playback.duration || 0,
    Math.max(
      0,
      playback.position +
        (output === "station" ? Math.min(elapsed, 4) : elapsed),
    ),
  );
}
function WaveNowPlaying({
  state,
  theme,
  onExpand,
}: {
  state: State;
  theme: Theme;
  onExpand: () => void;
}) {
  const t = state.playback.track!;
  const actions = useTrackActions();
  const [lyrics, setLyrics] = useState<Lyrics | null>(null);
  const [loading, setLoading] = useState(true);
  const position = usePosition(state.playback, state.output);
  useEffect(() => {
    let cancelled = false;
    setLyrics(null);
    setLoading(true);
    void bridge
      .rpc<Lyrics>("lyrics", { id: t.id })
      .then((value) => {
        if (!cancelled) setLyrics(value);
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [t.id, state.demo]);
  const index = lyrics?.synced
    ? lyrics.lines.reduce(
        (last, line, i) =>
          line.time !== null && line.time <= position + theme.lyricOffset
            ? i
            : last,
        -1,
      )
    : -1;
  const lines =
    lyrics?.lines.slice(Math.max(0, index), Math.max(0, index) + 2) || [];
  return (
    <section
      className="wave-now-playing"
      aria-label="Сейчас в Моей волне"
      onContextMenu={(e) => actions.menu(e, t)}
    >
      <Art item={t} size="lg" />
      <div className="wave-track-meta">
        <TrackMetadata track={t} />
      </div>
      <div
        className="wave-lyrics"
        aria-label={
          lyrics?.synced ? "Синхронный текст в карточке" : "Текст в карточке"
        }
      >
        {lines.length ? (
          lines.map((line, i) => (
            <div
              key={`${t.id}:${index}:${i}`}
              className={clsx(i === 0 && "wave-lyric-current")}
            >
              {line.text}
            </div>
          ))
        ) : (
          <span>
            {loading ? "Загружаю текст…" : "У этого трека пока нет текста"}
          </span>
        )}
      </div>
      <button className="wave-expand" onClick={onExpand}>
        Открыть полный текст <ArrowUpRight size={14} />
      </button>
    </section>
  );
}
function Player({
  state,
  act,
  onLyrics,
  onQueue,
  queueOpen,
  onDevice,
  like,
}: {
  state: State;
  act: Act;
  onLyrics: () => void;
  onQueue: () => void;
  queueOpen: boolean;
  onDevice: () => void;
  like: () => void;
}) {
  const actions = useTrackActions();
  const p = state.playback,
    position = usePosition(p, state.output);
  const t = p.track;
  const [volume, setVolume] = useState(p.volume);
  const volumeEditing = useRef(false);
  const audibleVolume = useRef(p.volume || 0.4);
  useEffect(() => {
    if (!volumeEditing.current) setVolume(p.volume);
    if (p.volume > 0) audibleVolume.current = p.volume;
  }, [p.volume]);
  const commitVolume = (value: number) => {
    volumeEditing.current = false;
    setVolume(value);
    if (value > 0) audibleVolume.current = value;
    void act("control", { action: "volume", value });
  };
  return (
    <div className="player-dock">
      {t && (
        <div className="player-wash" aria-hidden="true">
          <Art item={t} size="lg" />
        </div>
      )}
      <footer className="player">
        <div
          className="player-track"
          onContextMenu={(e) => t && actions.menu(e, t)}
        >
          {t ? (
            <>
              <button
                className="shrink-0"
                onClick={onLyrics}
                aria-label="Открыть текст текущего трека"
              >
                <Art item={t} size="sm" />
              </button>
              <div className="player-track-name">
                <TrackMetadata track={t} />
              </div>
              <IconButton
                icon={Heart}
                label={t.liked ? "Убрать из любимых" : "Добавить в любимые"}
                active={!!t.liked}
                onClick={like}
              />
            </>
          ) : (
            <>
              <span className="idle-cover">
                <Music2 size={23} />
              </span>
              <div>
                <strong>Твоя музыка ждёт</strong>
                <small>Выбери, с чего начать</small>
              </div>
            </>
          )}
        </div>
        <div className="player-center">
          <div className="player-controls">
            <IconButton
              icon={Shuffle}
              label="Перемешать очередь"
              active={state.queue.shuffled}
              disabled={!state.queue.tracks.length}
              onClick={() =>
                void act("control", {
                  action: "shuffle",
                  value: !state.queue.shuffled,
                })
              }
            />
            <IconButton
              icon={SkipBack}
              label="Предыдущий трек"
              disabled={!t}
              onClick={() => void act("control", { action: "previous" })}
            />
            <button
              className="main-play"
              aria-label={p.playing ? "Пауза" : "Продолжить"}
              disabled={!t && !state.queue.tracks.length}
              onClick={() => void act("control", { action: "toggle" })}
            >
              {p.transitioning ? (
                <LoaderCircle size={22} className="animate-spin" />
              ) : p.playing ? (
                <Pause size={22} fill="currentColor" />
              ) : (
                <Play size={22} fill="currentColor" />
              )}
            </button>
            <IconButton
              icon={SkipForward}
              label="Следующий трек"
              disabled={!t}
              onClick={() => void act("control", { action: "next" })}
            />
            <IconButton
              icon={state.queue.repeat === "one" ? Repeat1 : Repeat}
              label={`Повтор: ${{ off: "выключен", one: "трек", all: "вся очередь" }[state.queue.repeat]}`}
              active={state.queue.repeat !== "off"}
              disabled={!state.queue.tracks.length}
              onClick={() =>
                void act("control", {
                  action: "repeat",
                  value:
                    state.queue.repeat === "off"
                      ? "all"
                      : state.queue.repeat === "all"
                        ? "one"
                        : "off",
                })
              }
            />
          </div>
          <TrackProgress
            position={position}
            length={p.duration}
            resetKey={`${t?.id || ""}:${state.output}`}
            seek={(value) => void act("control", { action: "seek", value })}
          />
        </div>
        <div className="player-right">
          <div className="volume-control">
            <IconButton
              icon={volume === 0 ? VolumeX : Volume2}
              label={volume === 0 ? "Включить звук" : "Выключить звук"}
              onClick={() =>
                commitVolume(volume === 0 ? audibleVolume.current : 0)
              }
            />
            <output className="volume-value" aria-hidden="true">
              {Math.round(volume * 100)}%
            </output>
            <input
              aria-label="Громкость"
              aria-valuetext={`${Math.round(volume * 100)}%`}
              style={{ "--volume-level": `${volume * 100}%` } as CSSProperties}
              type="range"
              min="0"
              max="1"
              step="0.01"
              value={volume}
              onPointerDown={() => {
                volumeEditing.current = true;
              }}
              onChange={(e) => setVolume(Number(e.target.value))}
              onPointerUp={(e) => commitVolume(Number(e.currentTarget.value))}
              onPointerCancel={() => {
                volumeEditing.current = false;
                setVolume(p.volume);
              }}
              onKeyUp={(e) => commitVolume(Number(e.currentTarget.value))}
            />
          </div>
          <span className="player-divider" />
          <IconButton icon={Mic2} label="Текст песни" onClick={onLyrics} />
          <IconButton
            icon={ListMusic}
            label="Показать очередь"
            active={queueOpen}
            onClick={onQueue}
          />
          <IconButton
            icon={state.output === "local" ? Monitor : Speaker}
            label="Устройство воспроизведения"
            onClick={onDevice}
          />
        </div>
      </footer>
    </div>
  );
}
function QueuePanel({
  state,
  act,
  close,
}: {
  state: State;
  act: Act;
  close: () => void;
}) {
  const actions = useTrackActions();
  const [drag, setDrag] = useState<number | null>(null);
  const [dropSlot, setDropSlot] = useState<number | null>(null);
  const q = state.queue;
  const insertion =
    drag !== null && dropSlot !== drag && dropSlot !== drag + 1
      ? dropSlot
      : null;
  return (
    <aside className="queue-panel">
      <div className="queue-header">
        <h2>
          Очередь <span>{q.tracks.length || ""}</span>
        </h2>
        <IconButton icon={X} label="Скрыть очередь" onClick={close} />
      </div>
      <div className="queue-source">
        <span>
          {q.owned
            ? q.source
            : q.tracks.length
              ? "Сохранённая очередь Лад"
              : "Пока ничего не выбрано"}
        </span>
        {q.tracks.length > 0 && (
          <IconButton
            icon={Trash2}
            label="Очистить следующие треки"
            onClick={() => void act("queue", { action: "clear" })}
          />
        )}
      </div>
      {state.output === "station" && !q.owned && state.playback.track && (
        <div className="inline-note">
          <Info size={16} />
          <span>
            Музыку включили вне «Лада». Станция не передаёт полный список своей
            очереди. Сохранённую очередь можно запустить снова.
          </span>
        </div>
      )}
      <div
        className="queue-scroll"
        onDragOver={(e) => {
          if (drag === null) return;
          e.preventDefault();
          e.dataTransfer.dropEffect = "move";
          const rows =
            e.currentTarget.querySelectorAll<HTMLElement>(".queue-row");
          const index = [...rows].findIndex((row) => {
            const rect = row.getBoundingClientRect();
            return e.clientY < rect.top + rect.height / 2;
          });
          setDropSlot(index === -1 ? q.tracks.length : index);
        }}
        onDragLeave={(e) => {
          if (!e.currentTarget.contains(e.relatedTarget as Node | null))
            setDropSlot(null);
        }}
        onDrop={(e) => {
          e.preventDefault();
          if (drag !== null && dropSlot !== null) {
            const to = dropSlot > drag ? dropSlot - 1 : dropSlot;
            if (drag !== to)
              void act("queue", { action: "move", from: drag, to });
          }
          setDrag(null);
          setDropSlot(null);
        }}
      >
        {!q.tracks.length ? (
          <Empty icon={ListMusic} title="Поставь что-нибудь">
            Найди трек или открой плейлист — очередь будет здесь.
          </Empty>
        ) : (
          q.tracks.map((t, i) => (
            <div
              key={`${t.id}:${i}`}
              onContextMenu={(e) =>
                actions.menu(
                  e,
                  t,
                  () => void act("queue", { action: "jump", index: i }),
                  i,
                )
              }
              draggable
              onDragStart={(e) => {
                setDrag(i);
                setDropSlot(null);
                e.dataTransfer.effectAllowed = "move";
                e.dataTransfer.setData("text/plain", String(i));
              }}
              onDragEnd={() => {
                setDrag(null);
                setDropSlot(null);
              }}
              className={clsx(
                "queue-row",
                q.index === i && q.owned && "queue-current",
                i < q.index && q.owned && "queue-played",
                drag === i && "opacity-40",
                insertion === i && "queue-drop-before",
                insertion === q.tracks.length &&
                  i === q.tracks.length - 1 &&
                  "queue-drop-after",
              )}
            >
              <span className="queue-grip" aria-hidden="true">
                <GripVertical size={14} />
              </span>
              <button
                className="queue-art-play"
                disabled={
                  t.available === false || (state.blockExplicit && t.explicit)
                }
                onClick={() => void act("queue", { action: "jump", index: i })}
                aria-label={`Включить ${t.title} из очереди`}
              >
                <Art item={t} size="xs" />
                <Play size={16} />
              </button>
              <div className="queue-track">
                <TrackMetadata track={t} />
              </div>
              {q.index === i && q.owned ? (
                <AudioLines className="queue-playing" size={17} />
              ) : (
                <span className="queue-track-time">{duration(t.duration)}</span>
              )}
              <div className="queue-actions">
                <IconButton
                  icon={ArrowUp}
                  label={`Поднять ${t.title}`}
                  disabled={i === 0}
                  onClick={() =>
                    void act("queue", { action: "move", from: i, to: i - 1 })
                  }
                />
                <IconButton
                  icon={ArrowDown}
                  label={`Опустить ${t.title}`}
                  disabled={i === q.tracks.length - 1}
                  onClick={() =>
                    void act("queue", { action: "move", from: i, to: i + 1 })
                  }
                />
                <IconButton
                  icon={X}
                  label={`Удалить ${t.title} из очереди`}
                  disabled={q.index === i && q.owned}
                  onClick={() =>
                    void act("queue", { action: "remove", index: i })
                  }
                />
              </div>
            </div>
          ))
        )}
      </div>
      <div className="queue-bottom">
        <GripVertical size={15} />
        <span>Перетаскивай треки, меняй порядок</span>
      </div>
    </aside>
  );
}
function LyricsPage({
  track,
  state,
  theme,
  notice,
  seek,
  onBrowse,
  toggleLike,
}: {
  track: Track | null;
  state: State;
  theme: Theme;
  notice: (message: string) => void;
  seek: (position: number) => void;
  onBrowse: () => void;
  toggleLike: () => void;
}) {
  const actions = useTrackActions();
  const [lyrics, setLyrics] = useState<Lyrics | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const position = usePosition(state.playback, state.output);
  const active = useRef<HTMLButtonElement | null>(null);
  const manualUntil = useRef(0);
  const [following, setFollowing] = useState(true);
  useEffect(() => {
    setLyrics(null);
    setError("");
    if (!track) return;
    let cancelled = false;
    setLoading(true);
    void bridge
      .rpc<Lyrics>("lyrics", { id: track.id })
      .then((data) => {
        if (!cancelled) setLyrics(data);
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
  }, [track?.id, state.demo, retry]);
  const currentLine = lyrics?.synced
    ? lyrics.lines.reduce(
        (last, line, i) =>
          line.time !== null && line.time <= position + theme.lyricOffset
            ? i
            : last,
        -1,
      )
    : -1;
  useEffect(() => {
    if (Date.now() > manualUntil.current) {
      active.current?.scrollIntoView({ block: "center", behavior: "smooth" });
      setFollowing(true);
    }
  }, [currentLine]);
  if (!track)
    return (
      <Empty
        icon={Mic2}
        title="У каждой песни свои слова"
        action={
          <Button icon={Search} onClick={onBrowse}>
            Выбрать музыку
          </Button>
        }
      >
        Включи трек — обложка и текст появятся здесь.
      </Empty>
    );
  return (
    <div className="now-playing">
      <div
        className="now-heading"
        onContextMenu={(e) => actions.menu(e, track)}
      >
        <div>
          <span className="eyebrow">СЕЙЧАС ИГРАЕТ</span>
          <h1 aria-label={track.title}>
            <TrackAlbumLink track={track}>{track.title}</TrackAlbumLink>{" "}
            <ExplicitBadge track={track} />
          </h1>
          <p>
            <ArtistLinks track={track} />
            {track.album && (
              <>
                {" "}
                · <TrackAlbumLink track={track}>{track.album}</TrackAlbumLink>
              </>
            )}
          </p>
        </div>
        <IconButton
          icon={Heart}
          label="Отметить текущий трек"
          active={!!track.liked}
          onClick={toggleLike}
        />
      </div>
      <div className="now-meta">
        <Art item={track} size="sm" />
        <span>
          {state.demo
            ? "Демонстрация · без звука"
            : state.output === "station"
              ? state.devices.find((d) => d.id === state.deviceId)?.name ||
                "Станция"
              : "Звучит на компьютере"}
        </span>
        <span className="now-meta-line" />
        {lyrics?.synced ? (
          <span>
            <Mic2 size={14} /> Синхронный текст
          </span>
        ) : lyrics?.lines.length ? (
          <span>Текст без временных меток</span>
        ) : null}
      </div>
      <div
        className="lyrics-scroll"
        onWheel={() => {
          manualUntil.current = Date.now() + 6000;
          setFollowing(false);
        }}
      >
        {loading ? (
          <div className="lyrics-skeleton">
            <Skeleton />
          </div>
        ) : error ? (
          <Empty
            icon={AlertCircle}
            title="Не удалось загрузить текст"
            action={
              <Button
                icon={RefreshCw}
                variant="secondary"
                onClick={() => setRetry((x) => x + 1)}
              >
                Повторить
              </Button>
            }
          >
            {error}
          </Empty>
        ) : !lyrics?.lines.length ? (
          <div className="no-lyrics">
            <Art item={track} size="lg" />
            <h2>У этого трека пока нет текста</h2>
            <p>Когда Яндекс добавит слова, они появятся здесь.</p>
          </div>
        ) : (
          <div className="lyric-lines">
            {lyrics.lines.map((line, i) => (
              <button
                key={`${line.time}:${i}`}
                ref={i === currentLine ? active : undefined}
                className={clsx(
                  "lyric-line",
                  i === currentLine && "lyric-active",
                  i < currentLine && "lyric-past",
                  !lyrics.synced && "lyric-plain",
                )}
                disabled={!lyrics.synced || line.time === null}
                title={lyrics.synced ? "Перейти к этой строке" : undefined}
                onClick={() => {
                  if (line.time !== null) {
                    manualUntil.current = 0;
                    seek(Math.max(0, line.time - theme.lyricOffset));
                  }
                }}
              >
                {line.text || "···"}
              </button>
            ))}
          </div>
        )}
      </div>
      <div className="lyrics-footer">
        <span>
          {state.demo
            ? "Авторский демо-текст Лад"
            : lyrics?.writers?.length
              ? `Авторы: ${lyrics.writers.join(", ")}`
              : "Текст предоставляется Яндекс Музыкой"}
          {lyrics?.provider ? ` · ${lyrics.provider}` : ""}
        </span>
        {!following && lyrics?.synced && (
          <button
            onClick={() => {
              manualUntil.current = 0;
              active.current?.scrollIntoView({
                block: "center",
                behavior: "smooth",
              });
              setFollowing(true);
            }}
          >
            К текущей строке <ArrowDown size={14} />
          </button>
        )}
      </div>
    </div>
  );
}
function Modal({
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
    const first = target?.querySelector<HTMLButtonElement>("button");
    first?.focus();
    const handle = (e: KeyboardEvent) => {
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
type WavePreference = {
  key: string;
  name: string;
  selected: string;
  values: { value: string; name: string }[];
};
function WaveSettingsModal({
  close,
  demo,
}: {
  close: () => void;
  demo: boolean;
}) {
  const [groups, setGroups] = useState<WavePreference[]>([]),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(true),
    [saving, setSaving] = useState(false);
  useEffect(() => {
    let cancelled = false;
    if (demo) {
      setError("Настройки из Яндекс Музыки появятся после входа.");
      setLoading(false);
      return;
    }
    void bridge
      .rpc<{ groups: WavePreference[] }>("waveSettings", {})
      .then((r) => {
        if (!cancelled) setGroups(r.groups);
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
  }, [demo]);
  async function apply() {
    setSaving(true);
    setError("");
    try {
      await bridge.rpc("waveSettings", {
        settings: Object.fromEntries(groups.map((g) => [g.key, g.selected])),
      });
      close();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Не удалось настроить волну");
    } finally {
      setSaving(false);
    }
  }
  return (
    <Modal
      title="Настроить волну"
      subtitle="Твои настройки Яндекс Музыки"
      close={close}
      footer={
        <Button
          icon={Play}
          loading={saving}
          disabled={
            loading ||
            !groups.length ||
            groups.some((g) => !g.values.some((v) => v.value === g.selected))
          }
          onClick={() => void apply()}
        >
          Со следующего трека
        </Button>
      }
    >
      {loading && <p className="text-(--muted)">Загружаю настройки…</p>}
      {error && (
        <p role="alert" className="text-(--muted)">
          {error}
        </p>
      )}
      {groups.map((g) => (
        <div className="wave-preference" key={g.key}>
          <h3>{g.name}</h3>
          <div className="wave-preference-values">
            {g.values.map((v) => (
              <button
                key={v.value}
                aria-pressed={g.selected === v.value}
                onClick={() =>
                  setGroups((old) =>
                    old.map((x) =>
                      x.key === g.key ? { ...x, selected: v.value } : x,
                    ),
                  )
                }
              >
                {v.name}
              </button>
            ))}
          </div>
        </div>
      ))}
    </Modal>
  );
}
function LoginModal({
  close,
  notice,
  act,
  state,
}: {
  close: () => void;
  notice: (message: string) => void;
  act: Act;
  state: State;
}) {
  const [busy, setBusy] = useState(false);
  const [auth, setAuth] = useState<{
    url: string;
    code: string;
    interval: number;
    expiresIn: number;
  } | null>(null);
  const [error, setError] = useState("");
  const [token, setToken] = useState("");
  const [advanced, setAdvanced] = useState(false);
  useEffect(() => {
    if (!auth) return;
    let cancelled = false;
    const deadline = Date.now() + auth.expiresIn * 1000;
    const poll = async () => {
      if (cancelled) return;
      if (Date.now() > deadline) {
        setError("Код истёк. Закрой окно и запроси новый.");
        return;
      }
      try {
        const response = await bridge.rpc<{ done: boolean }>("authPoll");
        if (response.done) {
          close();
          return;
        }
        if (!cancelled)
          timer = setTimeout(poll, Math.max(3, auth.interval) * 1000);
      } catch (e) {
        if (!cancelled) setError((e as Error).message);
      }
    };
    let timer = setTimeout(poll, Math.max(3, auth.interval) * 1000);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [auth]);
  return (
    <Modal
      title="Твоя музыка начинается здесь"
      subtitle="Войди в аккаунт Яндекса с подпиской Плюс."
      close={close}
    >
      <div className="login-intro">
        <div className="login-icon">
          <AudioLines size={32} />
        </div>
        <p>
          Библиотека и Станции твоего аккаунта.
          <br />
          Пароль вводится только на странице Яндекса.
        </p>
      </div>
      <Button
        icon={ArrowUpRight}
        className="w-full"
        loading={busy}
        onClick={async () => {
          setBusy(true);
          setError("");
          try {
            const result = await bridge.login();
            if (result) close();
          } catch (e) {
            setError((e as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      >
        {state.hasStationSession
          ? "Обновить вход в Яндекс"
          : "Войти через Яндекс"}
      </Button>
      <div className="login-divider">
        <span>или подтвердить вход в браузере</span>
      </div>
      {!auth ? (
        <Button
          icon={Keyboard}
          variant="secondary"
          className="w-full"
          onClick={async () => {
            setBusy(true);
            try {
              setAuth(await bridge.rpc("authStart"));
            } catch (e) {
              setError((e as Error).message);
            } finally {
              setBusy(false);
            }
          }}
          disabled={busy}
        >
          Войти по коду устройства
        </Button>
      ) : (
        <div className="auth-code">
          <p>Открой страницу Яндекса и введи код</p>
          <div className="code-box">
            <strong>{auth.code}</strong>
            <IconButton
              icon={Copy}
              label="Скопировать код"
              onClick={() => {
                void bridge.copy(auth.code);
                notice("Код скопирован");
              }}
            />
          </div>
          <Button
            icon={ExternalLink}
            variant="tonal"
            onClick={() => void bridge.open(auth.url)}
          >
            Открыть Яндекс
          </Button>
          <small>
            <LoaderCircle size={14} className="animate-spin" /> Жду
            подтверждения входа
          </small>
        </div>
      )}
      {error && (
        <div className="error-note" role="alert">
          <AlertCircle size={18} />
          {error}
        </div>
      )}
      <button
        className="advanced-toggle"
        onClick={() => setAdvanced((x) => !x)}
      >
        {advanced ? "Скрыть" : "Другой способ входа"} <ChevronRight size={14} />
      </button>
      {advanced && (
        <form
          className="flex flex-col gap-3"
          onSubmit={async (e) => {
            e.preventDefault();
            const ok = await act("loginToken", { token });
            if (ok) close();
          }}
        >
          <label className="field-label">
            Токен Яндекс Музыки
            <input
              type="password"
              autoComplete="off"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder="OAuth-токен"
            />
          </label>
          <Button
            icon={Check}
            variant="secondary"
            type="submit"
            disabled={!token.trim()}
          >
            Подключить библиотеку
          </Button>
        </form>
      )}
      <p className="privacy-note">
        Сессия хранится на этом компьютере в защищённом хранилище ОС. Лад
        обращается к Яндексу напрямую.
      </p>
    </Modal>
  );
}
function DeviceModal({
  state,
  act,
  close,
  login,
}: {
  state: State;
  act: Act;
  close: () => void;
  login: () => void;
}) {
  const [devices, setDevices] = useState(state.devices);
  const [busy, setBusy] = useState(false);
  const [host, setHost] = useState("");
  const [selected, setSelected] = useState(state.deviceId || "");
  const [selectedOutput, setSelectedOutput] = useState<State["output"]>(
    state.output,
  );
  const [error, setError] = useState("");
  useEffect(() => {
    if (state.devices.length || !state.demo) setDevices(state.devices);
  }, [state.devices, state.demo]);
  const refresh = async () => {
    setBusy(true);
    try {
      const items = await bridge.rpc<State["devices"]>("devices");
      setDevices(items);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  useEffect(() => {
    if (state.hasStationSession || state.demo) void refresh();
  }, []);
  const choose = async (id: string) => {
    setBusy(true);
    setError("");
    try {
      await bridge.rpc("connect", { id, host: host || undefined });
      await bridge.rpc("output", { output: "station", transfer: true });
      close();
    } catch (e) {
      setError((e as Error).message);
      setSelected(id);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title="Где будем слушать?"
      subtitle="Одна очередь — компьютер или Яндекс Станция."
      close={close}
      footer={
        (devices.length > 0 || selectedOutput === "local") && (
          <div className="transfer-action">
            {state.playback.track && (
              <p>
                «{state.playback.track.title}» продолжится с{" "}
                {duration(state.playback.position)}. Очередь сохранится.
              </p>
            )}
            <Button
              icon={selectedOutput === "station" ? Speaker : Monitor}
              className="w-full"
              loading={busy}
              disabled={selectedOutput === "station" && !selected}
              onClick={async () => {
                if (selectedOutput === "station") await choose(selected);
                else {
                  const result = await act("output", {
                    output: "local",
                    transfer: true,
                  });
                  if (result) close();
                }
              }}
            >
              {selectedOutput === "station"
                ? "Продолжить на Станции"
                : bridge.platform === "darwin"
                  ? "Слушать на Mac"
                  : "Слушать на компьютере"}
            </Button>
          </div>
        )
      }
    >
      {state.blockExplicit && (
        <div className="content-filter-note">
          <Check size={16} /> Фильтр 18+ включён в Ладе
        </div>
      )}
      <button
        className={clsx(
          "device-option",
          selectedOutput === "local" && "selected",
        )}
        onClick={() => setSelectedOutput("local")}
      >
        <span className="device-icon">
          <Monitor size={25} />
        </span>
        <div>
          <strong>
            {bridge.platform === "darwin" ? "Этот Mac" : "Этот компьютер"}
          </strong>
          <small>Системный аудиовыход</small>
        </div>
        {selectedOutput === "local" && <Check size={20} />}
      </button>
      <div className="device-group-title">
        <span>ЯНДЕКС СТАНЦИИ</span>
        <IconButton
          icon={RefreshCw}
          label="Обновить устройства"
          disabled={busy || (!state.hasStationSession && !state.demo)}
          onClick={() => void refresh()}
        />
      </div>
      {devices.map((d) => (
        <button
          className={clsx(
            "device-option",
            selectedOutput === "station" && selected === d.id && "selected",
          )}
          key={d.id}
          onClick={() => {
            setSelected(d.id);
            setSelectedOutput("station");
          }}
          disabled={busy}
        >
          <span className="device-icon">
            <Speaker size={25} />
          </span>
          <div>
            <strong>{d.name || "Яндекс Станция"}</strong>
            <small>
              {state.demo
                ? "Пример устройства"
                : d.host
                  ? `${d.host} · локальная сеть`
                  : "Не найдена в локальной сети"}
            </small>
          </div>
          {selectedOutput === "station" && selected === d.id ? (
            <Check size={20} />
          ) : (
            <ChevronRight size={20} />
          )}
        </button>
      ))}
      {!devices.length && !busy && (
        <Empty
          icon={Speaker}
          title={
            state.hasStationSession
              ? "Пока не вижу Станцию"
              : "Подключи свой аккаунт"
          }
          action={
            !state.hasStationSession ? (
              <Button icon={ArrowUpRight} variant="secondary" onClick={login}>
                Войти для Станции
              </Button>
            ) : undefined
          }
        >
          {state.hasStationSession
            ? "Убедись, что колонка привязана к этому аккаунту и подключена к той же локальной сети."
            : "Для поиска колонки нужен вход в аккаунт её владельца."}
        </Empty>
      )}
      {busy && (
        <p className="loading-note">
          <LoaderCircle size={17} className="animate-spin" /> Подключаюсь к
          устройству…
        </p>
      )}
      {error && (
        <div className="error-note">
          <AlertCircle size={17} />
          {error}
        </div>
      )}
      {(selected || state.hasStationSession) && !state.demo && (
        <details className="manual-host">
          <summary>Указать IP Станции вручную</summary>
          <p>
            Если автоматический поиск не сработал. Адрес можно посмотреть в
            роутере.
          </p>
          <div className="flex gap-2">
            <input
              value={host}
              onChange={(e) => setHost(e.target.value)}
              placeholder="192.168.1.25"
              aria-label="IP Станции"
            />
            <Button
              variant="secondary"
              disabled={!host || !selected || busy}
              onClick={() => void choose(selected)}
            >
              Подключить
            </Button>
          </div>
        </details>
      )}
      <div className="inline-note mt-4">
        <Wifi size={17} />
        <span>
          Станция и компьютер должны находиться в одной локальной сети.
          Bluetooth не используется.
        </span>
      </div>
    </Modal>
  );
}
function LinkModal({
  close,
  act,
  onResult,
}: {
  close: () => void;
  act: Act;
  onResult: (data: Collection) => void;
}) {
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  return (
    <Modal
      title="Открыть музыку по ссылке"
      subtitle="Трек, альбом или плейлист Яндекс Музыки."
      close={close}
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          const data = await act<Collection>("link", { url: value });
          setBusy(false);
          if (data) onResult(data);
        }}
      >
        <input
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder="https://music.yandex.ru/…"
          aria-label="Ссылка Яндекс Музыки"
        />
        <Button
          icon={ArrowRight}
          type="submit"
          disabled={!value.trim()}
          loading={busy}
        >
          Открыть
        </Button>
      </form>
    </Modal>
  );
}
function SettingsPage({
  state,
  act,
  theme,
  onTheme,
  onLogin,
  onDevices,
  onLogout,
}: {
  state: State;
  act: Act;
  theme: Theme;
  onTheme: (theme: Theme) => void;
  onLogin: () => void;
  onDevices: () => void;
  onLogout: () => void;
}) {
  const update = (value: Partial<Theme>) => onTheme({ ...theme, ...value });
  const [filterBusy, setFilterBusy] = useState(false);
  return (
    <div className="settings-page">
      <div className="page-heading">
        <span className="eyebrow">ТВОЁ ПРОСТРАНСТВО</span>
        <h1>Настройки</h1>
      </div>
      <section className="settings-section">
        <h2>Аккаунт и устройства</h2>
        <div className="setting-row">
          <div>
            <strong>{state.profile?.name || "Яндекс Музыка"}</strong>
            <p>
              {state.demo
                ? "Режим знакомства · музыка не воспроизводится"
                : state.hasMusicSession
                  ? "Музыкальная библиотека подключена"
                  : "Войди, чтобы открыть свою музыку"}
            </p>
          </div>
          <Button icon={ArrowUpRight} variant="secondary" onClick={onLogin}>
            {state.hasMusicSession ? "Обновить вход" : "Войти"}
          </Button>
        </div>
        <div className="setting-row">
          <div>
            <strong>Устройство воспроизведения</strong>
            <p>
              {state.output === "local"
                ? "Системный аудиовыход компьютера"
                : state.devices.find((d) => d.id === state.deviceId)?.name ||
                  "Яндекс Станция"}
            </p>
          </div>
          <Button icon={Speaker} variant="secondary" onClick={onDevices}>
            Выбрать
          </Button>
        </div>
        <div className="setting-row">
          <div>
            <strong>Синхронизировать громкость с устройством</strong>
            <p>
              Системный регулятор управляет Ладом, в том числе на Станции.
              Выключено по умолчанию.
            </p>
          </div>
          <button
            role="switch"
            aria-label="Синхронизировать громкость с устройством"
            aria-checked={!!state.syncVolume}
            className={clsx("switch", state.syncVolume && "switch-on")}
            onClick={() =>
              void act("setVolumeSync", { enabled: !state.syncVolume })
            }
          >
            <span aria-hidden="true" />
          </button>
        </div>
      </section>
      <section className="settings-section">
        <div className="section-title">
          <h2>Контент</h2>
        </div>
        <div className="setting-row">
          <div>
            <strong>Не воспроизводить 18+</strong>
            <p>
              Пропускать треки с маркировкой Яндекса. Настройка действует только
              в Ладе.
            </p>
          </div>
          <button
            role="switch"
            aria-label="Не воспроизводить 18+"
            aria-checked={state.blockExplicit}
            disabled={filterBusy}
            className={clsx("switch", state.blockExplicit && "switch-on")}
            onClick={async () => {
              setFilterBusy(true);
              await act("setContentFilter", { enabled: !state.blockExplicit });
              setFilterBusy(false);
            }}
          >
            <span aria-hidden="true" />
          </button>
        </div>
        <p className="settings-content-note">
          Фильтр действует на музыку, запускаемую из Лада. Голосовые команды
          Станции и другие приложения он не меняет.
        </p>
      </section>
      <section className="settings-section">
        <div className="section-title">
          <h2>Оформление</h2>
          <span className="subtle-label">C4 · Orbit</span>
        </div>
        <div className="setting-row">
          <div>
            <strong>Размер интерфейса</strong>
            <p>Текст, иконки, кнопки и отступы меняются вместе.</p>
          </div>
          <div className="interface-scale-control">
            <button
              className="scale-step"
              aria-label="Уменьшить интерфейс"
              disabled={theme.scale <= 75}
              onClick={() => update({ scale: theme.scale - 5 })}
            >
              −
            </button>
            <select
              aria-label="Размер интерфейса"
              value={theme.scale}
              onChange={(e) => update({ scale: Number(e.target.value) })}
            >
              {[75, 80, 85, 90, 95, 100, 105, 110, 115].map((scale) => (
                <option key={scale} value={scale}>
                  {scale}%{scale === 85 ? " · по умолчанию" : ""}
                </option>
              ))}
            </select>
            <button
              className="scale-step"
              aria-label="Увеличить интерфейс"
              disabled={theme.scale >= 115}
              onClick={() => update({ scale: theme.scale + 5 })}
            >
              +
            </button>
          </div>
        </div>
        <div className="setting-row">
          <div>
            <strong>Основа</strong>
            <p>Геометрия и шрифт остаются в стиле Лад.</p>
          </div>
          <div className="segmented">
            {(["dark", "oled"] as const).map((base) => (
              <button
                key={base}
                className={clsx(theme.base === base && "active")}
                onClick={() => update({ base })}
              >
                {base === "dark" ? "Dark" : "OLED"}
              </button>
            ))}
          </div>
        </div>
        <div className="setting-row">
          <div>
            <strong>Окраска</strong>
            <p>
              Исходная палитра, цветной акцент или лёгкий оттенок поверхностей.
            </p>
          </div>
          <select
            aria-label="Окраска интерфейса"
            value={theme.mode}
            onChange={(e) => update({ mode: e.target.value as Theme["mode"] })}
          >
            <option value="default">Исходная</option>
            <option value="accent">Акцентная</option>
            <option value="tinted">Тонированная</option>
          </select>
        </div>
        {theme.mode !== "default" && (
          <div className="setting-row">
            <div>
              <strong>Цвет акцента</strong>
              <p>Смысловые цвета ошибок и статусов остаются независимыми.</p>
            </div>
            <input
              type="color"
              aria-label="Цвет акцента"
              value={theme.seed}
              onChange={(e) => update({ seed: e.target.value })}
            />
          </div>
        )}
        <div className="setting-row">
          <div>
            <strong>Прозрачность</strong>
            <p>Материал стекла из C4 Orbit.</p>
          </div>
          <button
            role="switch"
            aria-checked={theme.transparency}
            aria-label="Прозрачность"
            className={clsx("switch", theme.transparency && "switch-on")}
            onClick={() => update({ transparency: !theme.transparency })}
          >
            <span />
          </button>
        </div>
        <Button
          variant="ghost"
          icon={RefreshCw}
          onClick={() => onTheme(defaultTheme)}
        >
          Сбросить оформление
        </Button>
      </section>
      <section className="settings-section">
        <h2>Текст и воспроизведение</h2>
        <div className="setting-row">
          <div>
            <strong>Выразительность волны</strong>
            <p>Движение и яркость паутины вокруг обложки.</p>
          </div>
          <div className="flex items-center gap-3">
            <input
              type="range"
              min="0"
              max="100"
              step="5"
              value={theme.waveIntensity}
              aria-label="Выразительность волны"
              onChange={(e) =>
                update({ waveIntensity: Number(e.target.value) })
              }
            />
            <span>{theme.waveIntensity}%</span>
          </div>
        </div>
        <div className="setting-row">
          <div>
            <strong>Поправка синхронизации текста</strong>
            <p>
              Для задержки конкретной колонки. Только для текста с временными
              метками.
            </p>
          </div>
          <div className="flex items-center gap-3">
            <input
              className="w-28"
              type="range"
              min="-5"
              max="5"
              step="0.1"
              value={theme.lyricOffset}
              aria-label="Поправка синхронизации текста"
              onChange={(e) => update({ lyricOffset: Number(e.target.value) })}
            />
            <span className="w-16 text-right tabular-nums">
              {theme.lyricOffset > 0 ? "+" : ""}
              {theme.lyricOffset.toFixed(1)} с
            </span>
          </div>
        </div>
        <div className="setting-row">
          <div>
            <strong>Качество на компьютере</strong>
            <p>
              Лучший доступный MP3-поток, до 320 кбит/с. Доступность зависит от
              записи и подписки.
            </p>
          </div>
          <span className="subtle-label">Автоматически</span>
        </div>
        <div className="inline-note">
          <Info size={18} />
          <span>
            Очередью, собранной в Лад, управляет приложение. Для автоматического
            перехода между треками на Станции Лад должен работать. Закрытие окна
            во время музыки скрывает приложение; полный выход — ⌘Q.
          </span>
        </div>
      </section>
      <section className="settings-section">
        <h2>Клавиши</h2>
        <div className="shortcut-grid">
          {[
            ["Поиск", "⌘ / Ctrl K"],
            ["Пауза / продолжить", "Пробел"],
            ["Следующий трек", "⌘ / Ctrl →"],
            ["Предыдущий трек", "⌘ / Ctrl ←"],
            ["Текст песни", "⌘ / Ctrl L"],
          ].map(([name, key]) => (
            <div key={name}>
              <span>{name}</span>
              <kbd>{key}</kbd>
            </div>
          ))}
        </div>
      </section>
      <UpdateSection />
      <section className="settings-section">
        <h2>О Ладе</h2>
        <p className="settings-about">
          Независимый клиент. Не связан с Яндексом. Каталог, подписка, музыка и
          тексты предоставляются сервисом Яндекс Музыка. Подключение к Станции
          использует неофициальный локальный протокол Glagol.
        </p>
        <p className="settings-about">
          Внутри: React, Electron, yandex-music (LGPL-3.0), aiohttp, zeroconf,
          Golos Text (OFL). Исходники клиента и лицензии поставляются со
          сборкой.
        </p>
        {state.profile && (
          <Button icon={LogOut} variant="danger" onClick={onLogout}>
            {state.demo ? "Выйти из демо" : "Выйти из аккаунта"}
          </Button>
        )}
      </section>
    </div>
  );
}
