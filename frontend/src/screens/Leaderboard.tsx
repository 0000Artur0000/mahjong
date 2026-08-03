import { useEffect, useState } from "react";
import { getLadder, type LadderEntry } from "@/api/ratings";
import { currentSession } from "@/auth/session";
import type { UiError } from "@/api/errors";
import { Button, EmptyState, Skeleton } from "@/ui";
import { Inbox } from "lucide-react";

const FORMATS = [
  { id: "hanchan", label: "Ханчан" },
  { id: "tonpuusen", label: "Тонпусен" },
] as const;

/**
 * Лестница клуба.
 *
 * Форматы считаются раздельно и переключаются здесь же: смешивать ханчан с
 * тонпусеном нельзя, а держать два экрана ради этого незачем.
 */
export function Leaderboard() {
  const [format, setFormat] = useState<string>(FORMATS[0].id);
  const [rows, setRows] = useState<LadderEntry[] | null>(null);
  const [error, setError] = useState<UiError | null>(null);
  const me = currentSession()?.accountId;

  useEffect(() => {
    const controller = new AbortController();
    // Прошлый формат остаётся на экране, пока грузится новый: сбрасывать его
    // синхронно в эффекте — это лишний каскадный рендер ради полсекунды пустоты.
    void (async () => {
      const result = await getLadder(format, controller.signal);
      if (controller.signal.aborted) return;
      if ("error" in result) {
        setError(result.error);
        return;
      }
      setError(null);
      setRows(result.data);
    })();
    return () => controller.abort();
  }, [format]);

  return (
    <div className="screen stack">
      <h1 className="screen__title">Рейтинг клуба</h1>
      <p className="screen__hint">
        В лестницу идут партии, доигранные до конца формата. Форматы считаются
        раздельно.
      </p>

      <div className="cluster">
        {FORMATS.map((option) => (
          <Button
            key={option.id}
            size="sm"
            variant={option.id === format ? "primary" : "secondary"}
            aria-pressed={option.id === format}
            onClick={() => setFormat(option.id)}
          >
            {option.label}
          </Button>
        ))}
      </div>

      {error && (
        <div className="table-view__error" role="alert">
          <strong>{error.title}</strong>
          {error.detail && <span>{error.detail}</span>}
        </div>
      )}

      {!rows && !error && <Skeleton width="100%" height="12rem" />}

      {rows?.length === 0 && (
        <EmptyState
          icon={Inbox}
          title="В этом формате ещё не играли"
          hint="Первая же партия, доигранная до конца, откроет лестницу."
        />
      )}

      {rows && rows.length > 0 && (
        <ol className="ladder">
          {rows.map((row, index) => (
            <li
              key={row.accountId}
              className={[
                "ladder__row",
                index < 3 && "ladder__row--top",
                row.accountId === me && "ladder__row--me",
              ]
                .filter(Boolean)
                .join(" ")}
              aria-current={row.accountId === me ? "true" : undefined}
            >
              <span className="ladder__rank">{index + 1}</span>
              <span className="ladder__name">
                {row.nickname ?? "неизвестный"}
                <span className="ladder__games">{row.games} парт.</span>
              </span>
              <span className="ladder__rating">{row.rating}</span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
