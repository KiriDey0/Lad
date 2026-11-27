// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useRef, useState, type ReactNode } from "react";
import {
  ArrowLeft,
  ChevronRight,
  HeartOff,
  Play,
  RefreshCw,
  Undo2,
} from "lucide-react";
import { bridge } from "../bridge";
import type { ArtistDetail, Card, State, Track } from "../types";
import {
  Art,
  Button,
  Empty,
  IconButton,
  LikeButton,
  Skeleton,
  duration,
} from "./ui";
import { TrackMetadata, useTrackActions } from "./TrackActions";
type PlayTracks = (tracks: Track[], index: number, source: string) => void;
type Albums = { albums: Card[]; page: number; hasMore: boolean };
const message = (e: unknown) =>
  e instanceof Error ? e.message : "Не удалось загрузить музыку";
function AlbumCards({
  cards,
  open,
}: {
  cards: Card[];
  open: (card: Card) => void;
}) {
  return (
    <div className="card-grid discovery-albums">
      {cards.map((card) => (
        <button className="music-card" key={card.id} onClick={() => open(card)}>
          <Art item={card} />
          <strong>{card.title}</strong>
          <small>
            {[card.year, card.releaseType === "single" ? "Сингл" : null]
              .filter(Boolean)
              .join(" · ") || card.subtitle}
          </small>
        </button>
      ))}
    </div>
  );
}
export function ArtistPage({
  card,
  state,
  open,
  play,
  tracksView,
  changed,
  notice,
}: {
  card: Card;
  state: State;
  open: (card: Card) => void;
  play: PlayTracks;
  tracksView: (tracks: Track[], title: string) => ReactNode;
  changed: () => void;
  notice: (message: string) => void;
}) {
  const [data, setData] = useState<ArtistDetail | null>(null),
    [error, setError] = useState(""),
    [revision, setRevision] = useState(0),
    [following, setFollowing] = useState(false),
    [discography, setDiscography] = useState(false),
    [albums, setAlbums] = useState<Albums | null>(null),
    [albumsError, setAlbumsError] = useState(""),
    [loadingAlbums, setLoadingAlbums] = useState(false);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  useEffect(() => {
    let cancelled = false;
    setError("");
    setData(null);
    bridge
      .rpc<ArtistDetail>("artist", { id: card.id })
      .then((v) => {
        if (!v || !Array.isArray(v.releases))
          throw new Error(
            "Не удалось прочитать список новых релизов. Попробуй ещё раз.",
          );
        if (!cancelled) setData(v);
      })
      .catch((e) => {
        if (!cancelled) setError(message(e));
      });
    return () => {
      cancelled = true;
    };
  }, [card.id, revision]);
  const loadAlbums = async (page: number) => {
    if (loadingAlbums) return;
    setLoadingAlbums(true);
    setAlbumsError("");
    try {
      const v = await bridge.rpc<Albums>("artistAlbums", { id: card.id, page });
      if (alive.current)
        setAlbums((prev) => ({
          ...v,
          albums:
            page && prev
              ? [
                  ...prev.albums,
                  ...v.albums.filter(
                    (a) => !prev.albums.some((x) => x.id === a.id),
                  ),
                ]
              : v.albums,
        }));
    } catch (e) {
      if (alive.current) setAlbumsError(message(e));
    } finally {
      if (alive.current) setLoadingAlbums(false);
    }
  };
  const follow = async () => {
    if (!data || data.followed === null || following) return;
    setFollowing(true);
    try {
      const next = !data.followed;
      if (await bridge.rpc("artistLike", { id: card.id, liked: next })) {
        if (alive.current) {
          setData({ ...data, followed: next });
          changed();
        }
      }
    } catch (e) {
      notice(message(e));
    } finally {
      if (alive.current) setFollowing(false);
    }
  };
  if (error)
    return (
      <Empty
        title="Исполнитель не загрузился"
        action={
          <Button icon={RefreshCw} onClick={() => setRevision((x) => x + 1)}>
            Повторить
          </Button>
        }
      >
        {error}
      </Empty>
    );
  if (!data) return <Skeleton />;
  return (
    <div className="artist-page">
      <div className="artist-heading">
        <Art item={{ ...card, ...data.artist }} size="lg" />
        <div className="artist-heading-copy">
          <span className="eyebrow">ИСПОЛНИТЕЛЬ</span>
          <h1>{data.title}</h1>
          {typeof data.listeners === "number" && (
            <p>{data.listeners.toLocaleString("ru-RU")} слушателей за месяц</p>
          )}
          <div className="artist-actions">
            <Button
              icon={Play}
              disabled={
                !data.tracks.some(
                  (t) =>
                    t.available !== false &&
                    !(state.blockExplicit && t.explicit),
                )
              }
              onClick={() =>
                play(
                  data.tracks,
                  data.tracks.findIndex(
                    (t) =>
                      t.available !== false &&
                      !(state.blockExplicit && t.explicit),
                  ),
                  data.title,
                )
              }
            >
              Слушать
            </Button>
            <LikeButton
              identity={`artist:${card.id}`}
              active={!!data.followed}
              disabled={following || data.followed === null}
              aria-label={
                data.followed
                  ? "Убрать исполнителя из любимых"
                  : "Добавить исполнителя в любимые"
              }
              onClick={() => void follow()}
            />
            {data.followed === null && (
              <button
                className="text-link"
                onClick={() => setRevision((x) => x + 1)}
              >
                Повторить загрузку лайка
              </button>
            )}
          </div>
        </div>
      </div>
      {discography ? (
        <section className="music-section artist-discography">
          <button
            className="discovery-heading back-to-artist"
            onClick={() => setDiscography(false)}
          >
            <ArrowLeft size={18} />
            <span>К исполнителю</span>
          </button>
          <h2>Альбомы · {data.title}</h2>
          <p className="muted">От новых к старым</p>
          {albums && <AlbumCards cards={albums.albums} open={open} />}{" "}
          {loadingAlbums && <Skeleton />}
          {albumsError && <p role="alert">{albumsError}</p>}
          {!loadingAlbums && (!albums || albums.hasMore) && (
            <Button
              variant="secondary"
              icon={albumsError ? RefreshCw : undefined}
              onClick={() => void loadAlbums(albums ? albums.page + 1 : 0)}
            >
              {albumsError ? "Повторить" : "Показать ещё альбомы"}
            </Button>
          )}
          {albums && !albums.albums.length && (
            <p className="muted">Яндекс не вернул альбомов</p>
          )}
        </section>
      ) : (
        <>
          {tracksView(data.tracks, "Популярные треки")}
          <section className="music-section artist-popular-albums">
            <button
              className="discovery-heading"
              onClick={() => {
                setDiscography(true);
                if (!albums) void loadAlbums(0);
              }}
            >
              <h2>Популярные альбомы</h2>
              <ChevronRight size={20} />
            </button>
            {data.albumsError && <p className="muted">{data.albumsError}</p>}
            {data.albums.length ? (
              <AlbumCards cards={data.albums} open={open} />
            ) : (
              <p className="muted">
                Популярные альбомы пока недоступны. Открой заголовок, чтобы
                посмотреть дискографию.
              </p>
            )}
          </section>
        </>
      )}
    </div>
  );
}
export function NewReleases({
  open,
  play,
  notice,
}: {
  open: (card: Card) => void;
  play: PlayTracks;
  notice: (message: string) => void;
}) {
  const [data, setData] = useState<{
      releases: Card[];
      basis: string;
      partial?: boolean;
    } | null>(null),
    [error, setError] = useState(""),
    [revision, setRevision] = useState(0),
    [busy, setBusy] = useState<string | null>(null);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  useEffect(() => {
    let cancelled = false;
    setError("");
    bridge
      .rpc<{ releases: Card[]; basis: string; partial?: boolean }>("releases", {
        refresh: revision > 0,
      })
      .then((v) => {
        if (!cancelled) setData(v);
      })
      .catch((e) => {
        if (!cancelled) setError(message(e));
      });
    return () => {
      cancelled = true;
    };
  }, [revision]);
  const listen = async (card: Card) => {
    if (busy) return;
    setBusy(card.id);
    try {
      const data = await bridge.rpc<{ tracks: Track[] }>("collection", {
        type: "album",
        id: card.id,
      });
      if (alive.current) {
        if (data.tracks.length) play(data.tracks, 0, card.title);
        else notice("В этом релизе пока нет доступных треков");
      }
    } catch (e) {
      notice(message(e));
    } finally {
      if (alive.current) setBusy(null);
    }
  };
  return (
    <section className="music-section new-releases">
      <h2>Новые релизы</h2>
      {error ? (
        <div className="inline-error">
          <p>{error}</p>
          <Button
            variant="secondary"
            icon={RefreshCw}
            onClick={() => setRevision((x) => x + 1)}
          >
            Повторить
          </Button>
        </div>
      ) : !data ? (
        <Skeleton />
      ) : (
        <>
          <p className="muted release-basis">{data.basis}</p>
          {data.partial && (
            <p className="muted">
              Часть данных сейчас недоступна.{" "}
              <button
                className="text-link"
                onClick={() => setRevision((x) => x + 1)}
              >
                Повторить
              </button>
            </p>
          )}
          {data.releases.length ? (
            <div className="release-carousel">
              {data.releases.map((card) => {
                const artist = card.releaseArtist || card.artists?.[0];
                return (
                  <article className="release-card" key={card.id}>
                    {artist && (
                      <button
                        className="release-artist"
                        onClick={() => open(artist)}
                      >
                        <Art item={artist} size="lg" />
                        <strong>{artist.title}</strong>
                      </button>
                    )}
                    <div className="release-record">
                      <button
                        className="release-album"
                        onClick={() => open(card)}
                      >
                        <Art item={card} size="sm" />
                        <span>
                          <strong>{card.title}</strong>
                          <small>
                            {card.releaseType === "single"
                              ? "Сингл"
                              : card.releaseType === "ep"
                                ? "EP"
                                : "Альбом"}
                            {card.releaseDate &&
                              ` · ${new Date(card.releaseDate).toLocaleDateString("ru-RU", { day: "numeric", month: "long" })}`}
                          </small>
                        </span>
                      </button>
                      <IconButton
                        icon={Play}
                        label={`Слушать ${card.title}`}
                        disabled={!!busy}
                        onClick={() => void listen(card)}
                      />
                    </div>
                  </article>
                );
              })}
            </div>
          ) : (
            <p className="muted">
              За последние 90 дней новых релизов не нашлось. Здесь появятся
              новинки исполнителей, которых ты слушаешь и отмечаешь лайком.
            </p>
          )}
        </>
      )}
    </section>
  );
}
export function DislikesPage({
  state,
  play,
  undo,
}: {
  state: State;
  play: PlayTracks;
  undo: (track: Track) => Promise<boolean>;
}) {
  const [tracks, setTracks] = useState<Track[]>([]);
  const [total, setTotal] = useState(0);
  const [request, setRequest] = useState({ offset: 0, revision: 0 });
  const [nextOffset, setNextOffset] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [pending, setPending] = useState<Set<string>>(new Set());
  const actions = useTrackActions();
  const cursor = useRef(0);
  const listed = useRef(tracks);
  listed.current = tracks;
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    const changed = (event: Event) => {
      const data = (event as CustomEvent<{ id: string; disliked?: boolean }>)
        .detail;
      if (
        data.disliked !== false ||
        !listed.current.some((t) => t.id === data.id)
      )
        return;
      // Removing an earlier item shifts all subsequent server offsets by one.
      cursor.current = Math.max(0, cursor.current - 1);
      setNextOffset(cursor.current);
      setTracks((prev) => prev.filter((t) => t.id !== data.id));
      setTotal((prev) => Math.max(0, prev - 1));
    };
    window.addEventListener("lad:rating-changed", changed);
    return () => {
      alive.current = false;
      window.removeEventListener("lad:rating-changed", changed);
    };
  }, []);
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError("");
    bridge
      .rpc<{ tracks: Track[]; total: number; nextOffset?: number }>(
        "dislikes",
        { offset: request.offset, limit: 100 },
      )
      .then((data) => {
        if (cancelled) return;
        setTracks((prev) =>
          request.offset
            ? [
                ...prev,
                ...data.tracks.filter((t) => !prev.some((x) => x.id === t.id)),
              ]
            : data.tracks,
        );
        setTotal(data.total);
        cursor.current =
          data.nextOffset ?? Math.min(data.total, request.offset + 100);
        setNextOffset(cursor.current);
      })
      .catch((e) => {
        if (!cancelled) setError(message(e));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [request]);
  const remove = async (track: Track) => {
    if (pending.has(track.id)) return;
    setPending((prev) => new Set(prev).add(track.id));
    await undo({ ...track, disliked: true });
    if (alive.current)
      setPending((prev) => {
        const next = new Set(prev);
        next.delete(track.id);
        return next;
      });
  };
  return (
    <section className="music-section dislikes-page">
      <h1>Мои дизлайки</h1>
      <p className="muted">
        Яндекс реже предлагает похожую музыку. Сними случайную отметку, чтобы
        вернуть трек в рекомендации.
      </p>
      <div className="disliked-tracks">
        {tracks.map((track, index) => (
          <div
            className="disliked-track"
            key={track.id}
            onContextMenu={(event) => actions.menu(event, track)}
          >
            <button
              className="disliked-play"
              aria-label={`Включить ${track.title}`}
              disabled={
                track.available === false ||
                (state.blockExplicit && track.explicit)
              }
              onClick={() => play(tracks, index, "Мои дизлайки")}
            >
              <Art item={track} size="xs" />
              <Play size={18} />
            </button>
            <div className="track-name">
              <TrackMetadata track={track} />
            </div>
            <span className="track-duration">{duration(track.duration)}</span>
            <IconButton
              icon={Undo2}
              label={`Снять дизлайк: ${track.title}`}
              disabled={loading || pending.has(track.id)}
              onClick={() => void remove(track)}
            />
          </div>
        ))}
      </div>
      {loading && <Skeleton />}
      {error && (
        <div className="inline-error">
          <p role="alert">{error}</p>
          <Button
            icon={RefreshCw}
            onClick={() =>
              setRequest((prev) => ({ ...prev, revision: prev.revision + 1 }))
            }
          >
            Повторить
          </Button>
        </div>
      )}
      {!loading && !error && !tracks.length && nextOffset >= total && (
        <Empty icon={HeartOff} title="Дизлайков пока нет">
          Здесь можно будет отменить отметки «Не нравится».
        </Empty>
      )}
      {!loading && !error && nextOffset < total && (
        <Button
          variant="secondary"
          onClick={() =>
            setRequest((prev) => ({
              offset: cursor.current,
              revision: prev.revision + 1,
            }))
          }
        >
          Показать ещё
        </Button>
      )}
    </section>
  );
}
