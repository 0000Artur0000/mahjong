import { useState } from "react";
import type { HandRequest, TableView } from "@/api/tables";

/** Имя яку из контракта, а не свободная строка. */
type DeclarableYaku = NonNullable<HandRequest["declaredYaku"]>[number];
import { Badge, Button, Field, Tile, TILE_IDS, type TileId } from "@/ui";

const WINDS = ["Восток", "Юг", "Запад", "Север"] as const;

/** Яку, которых фото не докажет: их объявляет игрок. Имена — из контракта. */
const DECLARABLE: ReadonlyArray<readonly [DeclarableYaku, string]> = [
  ["Richi", "Риичи"],
  ["WRichi", "Двойное риичи"],
  ["Ippatsu", "Иппацу"],
  ["Rinshan", "Риншан"],
  ["Chankan", "Чанкан"],
  ["Haitei", "Хайтей"],
  ["Houtei", "Хоутей"],
];

const HAND_SIZE = 14;

function seatWind(seat: number, dealerSeat: number): string {
  return WINDS[(seat - dealerSeat + WINDS.length) % WINDS.length]!;
}

/**
 * Ручной ввод выигрышной руки: тайлы набираются тапом по спрайту, выигрышный
 * тайл и способ победы указываются отдельно — фото их не доказывает.
 *
 * Экран ничего не считает сам: хан, фу и яку приходят из превью Score Engine.
 */
export function HandEditor({
  table,
  onPreview,
  onConfirm,
  busy,
}: {
  table: TableView;
  onPreview: (hand: HandRequest) => void;
  onConfirm: (hand: HandRequest) => void;
  busy: boolean;
}) {
  const [tiles, setTiles] = useState<TileId[]>([]);
  const [winnerSeat, setWinnerSeat] = useState(table.dealerSeat);
  const [discarderSeat, setDiscarderSeat] = useState<number | "">("");
  const [tsumo, setTsumo] = useState(false);
  const [doraIndicators, setDoraIndicators] = useState<TileId[]>([]);
  const [uraIndicators, setUraIndicators] = useState<TileId[]>([]);
  const [picking, setPicking] = useState<"hand" | "dora" | "ura">("hand");
  const [declared, setDeclared] = useState<DeclarableYaku[]>([]);

  const riichi = declared.includes("Richi") || declared.includes("WRichi");
  const complete = tiles.length === HAND_SIZE;
  const ronReady = tsumo || discarderSeat !== "";
  const ready = complete && ronReady;

  const hand = (): HandRequest => ({
    tiles,
    winningTile: tiles[tiles.length - 1]!,
    tsumo,
    winnerSeat,
    ...(tsumo ? {} : { discarderSeat: Number(discarderSeat) }),
    // Присылаем индикаторы, а не число: дору считает сервер по §11.
    // Ака не передаётся вовсе — красные пятёрки уже в составе руки.
    doraIndicators,
    uraIndicators,
    declaredYaku: declared,
    expectedVersion: table.version,
  });

  return (
    <div className="hand-editor stack">
      <div className="hand-editor__head">
        <p className="demo-label">
          Рука · {tiles.length} из {HAND_SIZE}
        </p>
        {tiles.length > 0 && (
          <Button size="sm" variant="ghost" onClick={() => setTiles([])}>
            Очистить
          </Button>
        )}
      </div>

      {/* Последний добавленный тайл считается выигрышным — набирают его в конце. */}
      <div className="hand-editor__hand" aria-label="Собранная рука">
        {tiles.map((tile, i) => (
          <button
            key={`${tile}-${i}`}
            type="button"
            className={
              i === tiles.length - 1
                ? "hand-editor__slot hand-editor__slot--winning"
                : "hand-editor__slot"
            }
            onClick={() => setTiles(tiles.filter((_, j) => j !== i))}
            aria-label={`Убрать ${tile}`}
          >
            <Tile tile={tile} width={34} />
          </button>
        ))}
        {tiles.length === 0 && (
          <p className="hand-editor__empty">
            Нажимайте тайлы ниже. Выигрышный добавьте последним.
          </p>
        )}
      </div>

      <p className="demo-label">
        {picking === "hand"
          ? "Тайлы идут в руку"
          : picking === "dora"
            ? "Тайлы идут в индикаторы доры"
            : "Тайлы идут в индикаторы ура-доры"}
      </p>
      <div className="hand-editor__picker" role="group" aria-label="Выбор тайлов">
        {TILE_IDS.filter((t) => t !== "back").map((tile) => (
          <button
            key={tile}
            type="button"
            className="hand-editor__pick"
            disabled={picking === "hand" && complete}
            onClick={() => {
              if (picking === "hand") setTiles([...tiles, tile]);
              else if (picking === "dora")
                setDoraIndicators([...doraIndicators, tile]);
              else setUraIndicators([...uraIndicators, tile]);
            }}
          >
            <Tile tile={tile} width={30} />
          </button>
        ))}
      </div>

      <div className="hand-editor__grid">
        <Field label="Победитель">
          {(field) => (
            <select
              {...field}
              className="select"
              value={winnerSeat}
              onChange={(e) => setWinnerSeat(Number(e.target.value))}
            >
            {table.scores.map((_, seat) => (
              <option key={seat} value={seat}>
                {seatWind(seat, table.dealerSeat)}
                {seat === table.dealerSeat ? " · дилер" : ""}
              </option>
              ))}
            </select>
          )}
        </Field>

        <Field label="Способ победы">
          {(field) => (
            <select
              {...field}
              className="select"
              value={tsumo ? "tsumo" : "ron"}
              onChange={(e) => {
                const isTsumo = e.target.value === "tsumo";
                setTsumo(isTsumo);
                if (isTsumo) setDiscarderSeat("");
              }}
            >
              <option value="ron">Рон</option>
              <option value="tsumo">Цумо</option>
            </select>
          )}
        </Field>

        {!tsumo && (
          <Field
            label="Сбросил"
            error={
              discarderSeat === "" ? "Укажите, кто сбросил тайл." : undefined
            }
          >
            {(field) => (
              <select
                {...field}
                className="select"
                value={discarderSeat}
                onChange={(e) =>
                  setDiscarderSeat(
                    e.target.value === "" ? "" : Number(e.target.value),
                  )
                }
              >
                <option value="">—</option>
                {table.scores.map((_, seat) =>
                  seat === winnerSeat ? null : (
                    <option key={seat} value={seat}>
                      {seatWind(seat, table.dealerSeat)}
                    </option>
                  ),
                )}
              </select>
            )}
          </Field>
        )}

      </div>

      <IndicatorRow
        label="Индикаторы доры"
        hint="Первый — базовый, следующие открыты канами. Дору посчитает сервер."
        tiles={doraIndicators}
        active={picking === "dora"}
        onActivate={() => setPicking(picking === "dora" ? "hand" : "dora")}
        onRemove={(i) =>
          setDoraIndicators(doraIndicators.filter((_, j) => j !== i))
        }
      />

      {riichi && (
        <IndicatorRow
          label="Индикаторы ура-доры"
          hint="Нижние индикаторы: по одному на каждый верхний."
          tiles={uraIndicators}
          active={picking === "ura"}
          onActivate={() => setPicking(picking === "ura" ? "hand" : "ura")}
          onRemove={(i) =>
            setUraIndicators(uraIndicators.filter((_, j) => j !== i))
          }
        />
      )}

      <div className="stack">
        <p className="demo-label">Объявленные яку</p>
        <div className="cluster">
          {DECLARABLE.map(([id, label]) => {
            const on = declared.includes(id);
            return (
              <button
                key={id}
                type="button"
                aria-pressed={on}
                className="hand-editor__yaku"
                onClick={() => {
                  const next = on
                    ? declared.filter((x) => x !== id)
                    : [...declared, id];
                  setDeclared(next);
                  // Ура открывается только с риичи — снимаем вместе с ним.
                  if (!next.includes("Richi") && !next.includes("WRichi")) {
                    setUraIndicators([]);
                    if (picking === "ura") setPicking("hand");
                  }
                }}
              >
                <Badge tone={on ? "accent" : "neutral"}>{label}</Badge>
              </button>
            );
          })}
        </div>
      </div>

      <div className="cluster">
        <Button
          variant="secondary"
          disabled={!ready || busy}
          onClick={() => onPreview(hand())}
        >
          Посчитать
        </Button>
        <Button disabled={!ready || busy} loading={busy} onClick={() => onConfirm(hand())}>
          Подтвердить раздачу
        </Button>
      </div>
    </div>
  );
}

/** Строка индикаторов: тап по кнопке переключает, куда идут выбираемые тайлы. */
function IndicatorRow({
  label,
  hint,
  tiles,
  active,
  onActivate,
  onRemove,
}: {
  label: string;
  hint: string;
  tiles: TileId[];
  active: boolean;
  onActivate: () => void;
  onRemove: (index: number) => void;
}) {
  return (
    <div className="stack">
      <div className="hand-editor__head">
        <p className="demo-label">{label}</p>
        <Button
          size="sm"
          variant={active ? "primary" : "secondary"}
          aria-pressed={active}
          onClick={onActivate}
        >
          {active ? "Готово" : "Добавить"}
        </Button>
      </div>
      <div className="hand-editor__hand" aria-label={label}>
        {tiles.map((tile, i) => (
          <button
            key={`${tile}-${i}`}
            type="button"
            className="hand-editor__slot"
            onClick={() => onRemove(i)}
            aria-label={`Убрать индикатор ${tile}`}
          >
            <Tile tile={tile} width={30} />
          </button>
        ))}
        {tiles.length === 0 && <p className="hand-editor__empty">{hint}</p>}
      </div>
    </div>
  );
}
