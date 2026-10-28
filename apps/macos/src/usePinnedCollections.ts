// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useState } from "react";
import type { Card } from "./types";
export const collectionKey = (card: Card) =>
  JSON.stringify([
    card.type,
    card.type === "playlist" ? card.owner || "" : "",
    card.id,
  ]);
const validCard = (card: unknown): card is Card => {
  if (!card || typeof card !== "object") return false;
  const c = card as Card;
  return (
    (c.type === "album" || c.type === "playlist") &&
    typeof c.id === "string" &&
    !!c.id &&
    c.id !== "link" &&
    typeof c.title === "string" &&
    (c.owner === undefined || typeof c.owner === "string")
  );
};
export function usePinnedCollections(
  scope: string | null,
  legacy: Card[] | null,
  notice: (message: string) => void,
) {
  const [snapshot, setSnapshot] = useState<{
    scope: string | null;
    cards: Card[];
    ready: boolean;
  }>({ scope: null, cards: [], ready: false });
  const storageKey = scope ? `lad:pinned:v1:${scope}` : null;
  useEffect(() => {
    if (!scope || !storageKey) {
      setSnapshot({ scope: null, cards: [], ready: false });
      return;
    }
    try {
      const saved = localStorage.getItem(storageKey);
      if (saved !== null) {
        const data: unknown = JSON.parse(saved);
        const keys = new Set<string>();
        const cards = (Array.isArray(data) ? data : [])
          .filter(validCard)
          .filter((c) => {
            const key = collectionKey(c);
            if (keys.has(key)) return false;
            keys.add(key);
            return true;
          });
        const refreshed = cards.map((card) => ({
          ...card,
          ...legacy?.find(
            (item) => collectionKey(item) === collectionKey(card),
          ),
        }));
        setSnapshot({ scope, cards: refreshed, ready: true });
      } else if (legacy !== null) {
        // Preserve the shortcuts already visible before pinning became explicit.
        const cards = legacy.filter(validCard).slice(0, 12);
        localStorage.setItem(storageKey, JSON.stringify(cards));
        setSnapshot({ scope, cards, ready: true });
      } else setSnapshot({ scope, cards: [], ready: false });
    } catch {
      setSnapshot({ scope, cards: [], ready: true });
      notice("Не удалось прочитать закрепления «Лада»");
    }
  }, [scope, storageKey, legacy, notice]);
  const cards = snapshot.scope === scope ? snapshot.cards : [];
  const isPinned = (card: Card) =>
    cards.some((p) => collectionKey(p) === collectionKey(card));
  const toggle = (card: Card) => {
    if (
      !scope ||
      !storageKey ||
      snapshot.scope !== scope ||
      !snapshot.ready ||
      !validCard(card)
    )
      return;
    const pinned = isPinned(card);
    const next = pinned
      ? cards.filter((p) => collectionKey(p) !== collectionKey(card))
      : [...cards, card];
    try {
      localStorage.setItem(storageKey, JSON.stringify(next));
      setSnapshot({ scope, cards: next, ready: true });
      notice(
        pinned ? "Откреплено из «Твоей музыки»" : "Закреплено в «Твоей музыке»",
      );
    } catch {
      notice("Не удалось сохранить закрепление");
    }
  };
  return {
    cards,
    isPinned,
    toggle,
    ready: snapshot.scope === scope && snapshot.ready,
  };
}
