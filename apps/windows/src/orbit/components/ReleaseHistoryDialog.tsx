// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useState } from "react";
import { RefreshCw } from "lucide-react";
import { bridge } from "../bridge";
import type { ReleaseHistoryEntry } from "../types";
import { Modal } from "./Modal";
import { Button } from "./ui";

// Release descriptions are rendered as text, never as trusted HTML.
function plainText(text: string) {
  return text.replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/\*\*([^*]+)\*\*/g, "$1").replace(/`([^`]+)`/g, "$1");
}

export function ReleaseNotes({ notes }: { notes: string }) {
  return <div className="release-notes">{notes.split(/\n\s*\n/).filter(Boolean).map((block, i) => {
    const lines = block.trim().split("\n");
    if (lines.every(line => /^\s*[-*]\s+/.test(line))) {
      return <ul key={i}>{lines.map((line, j) => <li key={j}>{plainText(line.replace(/^\s*[-*]\s+/, ""))}</li>)}</ul>;
    }
    return <p key={i}>{plainText(block.replace(/^#{1,6}\s+/gm, ""))}</p>;
  })}</div>;
}

export function ReleaseHistoryDialog({ close }: { close: () => void }) {
  const [releases, setReleases] = useState<ReleaseHistoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [attempt, retry] = useState(0);
  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    const load = async () => {
      try {
        if (!bridge.releaseHistory) throw Error("История недоступна. Обнови приложение или открой GitHub.");
        const entries = await bridge.releaseHistory();
        if (active) setReleases(entries);
      } catch (e) {
        if (active) setError(e instanceof Error && !/fetch|timeout|network/i.test(e.message)
          ? e.message : "Не удалось загрузить историю версий. Проверь подключение и попробуй ещё раз.");
      } finally {
        if (active) setLoading(false);
      }
    };
    void load();
    return () => { active = false; };
  }, [attempt]);

  return <Modal title="История версий" close={close} className="release-history-modal"
    footer={<Button variant="ghost" onClick={() => void bridge.open("https://github.com/KiriDey0/Lad/releases")}>Открыть на GitHub</Button>}>
    {loading ? <p className="history-status" role="status">Загружаем историю…</p>
      : error ? <div className="history-status"><p role="alert">{error}</p><Button icon={RefreshCw} variant="tonal" onClick={() => retry(n => n + 1)}>Попробовать снова</Button></div>
      : releases.length === 0 ? <p className="history-status">Публичных выпусков пока нет.</p>
      : <div className="history-list">{releases.map(release => <article className="history-item" key={release.version}>
        <h3>Лад {release.version}</h3>
        <time dateTime={release.date}>{new Date(release.date).toLocaleDateString("ru-RU", { day: "numeric", month: "long", year: "numeric" })}</time>
        {release.notes ? <ReleaseNotes notes={release.notes} /> : <p className="history-status">Описание выпуска не добавлено.</p>}
      </article>)}</div>}
  </Modal>;
}
