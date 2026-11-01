// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useState } from "react";
import { Check, ListMusic } from "lucide-react";
import type { Card, Track } from "../types";
import { bridge } from "../bridge";
import { Modal } from "./Modal";
import { Button } from "./ui";

export function LocalPlaylistDialog({
  tracks,
  close,
  notice,
}: {
  tracks: Track[];
  close: () => void;
  notice: (message: string) => void;
}) {
  const [cards, setCards] = useState<Card[]>([]);
  const [selected, setSelected] = useState("");
  const [title, setTitle] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    bridge
      .rpc<{ cards: Card[] }>("localBrowse", { kind: "playlists" })
      .then((r) => {
        if (active) setCards(r.cards);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, []);
  const save = async () => {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      const card = selected
        ? cards.find((c) => c.id === selected)
        : await bridge.rpc<Card>("localCreatePlaylist", {
            title: title.trim(),
          });
      if (!card) throw Error("Выбери плейлист");
      if (tracks.length)
        await bridge.rpc("localPlaylistAdd", { id: card.id, tracks });
      notice(
        tracks.length
          ? `Добавлено в «${card.title}»`
          : `Плейлист «${card.title}» создан`,
      );
      close();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title={tracks.length ? "Добавить в плейлист" : "Новый плейлист"}
      subtitle="Плейлист хранится на этом устройстве"
      close={close}
      footer={
        <Button
          icon={Check}
          loading={busy}
          disabled={!selected && !title.trim()}
          onClick={() => void save()}
        >
          {tracks.length ? "Добавить" : "Создать"}
        </Button>
      }
    >
      {tracks.length > 0 && cards.length > 0 && (
        <label className="local-field">
          Плейлист
          <select
            aria-label="Выбрать локальный плейлист"
            value={selected}
            onChange={(e) => setSelected(e.target.value)}
          >
            <option value="">Создать новый</option>
            {cards.map((c) => (
              <option key={c.id} value={c.id}>
                {c.title}
              </option>
            ))}
          </select>
        </label>
      )}
      {!selected && (
        <label className="local-field">
          Название
          <input
            autoFocus
            maxLength={160}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Новый плейлист"
            onKeyDown={(e) => {
              if (e.key === "Enter" && title.trim()) void save();
            }}
          />
        </label>
      )}
      {tracks.length > 0 && (
        <p className="muted">
          <ListMusic size={16} /> {tracks.length} треков
        </p>
      )}
      {error && <p role="alert">{error}</p>}
    </Modal>
  );
}
