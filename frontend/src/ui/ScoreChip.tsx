import { Tile } from "./Tile";
import type { TileId } from "./tiles";

function cx(...parts: Array<string | false | undefined>): string {
  return parts.filter(Boolean).join(" ");
}

/**
 * One seat in a scoreboard: wind tile, name, tabular score, optional delta.
 * The dealer is marked with the thin gold ring (fx-dealer-ring) — never
 * by colour alone; the "дилер" text below keeps it unambiguous.
 */
export function ScoreChip({
  tile,
  label,
  note,
  score,
  delta,
  dealer = false,
  vacant = false,
}: {
  tile?: TileId;
  label: string;
  /** Ветер места словами: сам тайл скрыт от скринридера. */
  note?: string;
  score: number;
  delta?: number;
  dealer?: boolean;
  /** Игрок ушёл: очки места остаются, но за ним никто не сидит. */
  vacant?: boolean;
}) {
  return (
    <div
      className={cx(
        "score-chip",
        dealer && "score-chip--dealer",
        vacant && "score-chip--vacant",
      )}
    >
      {tile && (
        <Tile tile={tile} width={26} className="score-chip__tile" aria-hidden />
      )}
      <span className="score-chip__label">
        {label}
        {note && <span className="score-chip__dealer-note"> · {note}</span>}
        {dealer && <span className="score-chip__dealer-note"> · дилер</span>}
      </span>
      <span className="score-chip__score tabular">
        {score.toLocaleString("ru-RU")}
      </span>
      {delta !== undefined && delta !== 0 && (
        <span
          className={cx(
            "score-chip__delta",
            delta > 0
              ? "score-chip__delta--positive"
              : "score-chip__delta--negative",
          )}
        >
          {delta > 0 ? "+" : "−"}
          {Math.abs(delta).toLocaleString("ru-RU")}
        </span>
      )}
    </div>
  );
}
