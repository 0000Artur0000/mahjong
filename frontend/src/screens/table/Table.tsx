import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router";
import type { UiError } from "@/api/errors";
import { currentSession } from "@/auth/session";
import {
  confirmHand,
  declareRiichi,
  finishTable,
  getTable,
  getTableEvents,
  joinTable,
  leaveLobby,
  leaveSeat,
  previewHand,
  recordDraw,
  revertTable,
  startTable,
  takeSeat,
  type HandPayment,
  type HandRequest,
  type TableEvent,
  type TableView,
} from "@/api/tables";
import { getMyRatingChanges, type RatingChange } from "@/api/ratings";
import {
  Badge,
  Button,
  Dialog,
  EmptyState,
  Field,
  ScoreChip,
  Skeleton,
  Tabs,
  Textarea,
  type TileId,
} from "@/ui";
import { Inbox } from "lucide-react";
import { HandEditor } from "./HandEditor";
import { STALE_HOURS, idleHours } from "./stale";

/** Ветер места: восток у дилера, дальше по порядку хода. */
const WIND_TILES: TileId[] = ["1z", "2z", "3z", "4z"];
const WIND_NAMES = ["Восток", "Юг", "Запад", "Север"] as const;
const ROUND_NAMES: Record<string, string> = {
  east: "Восточный",
  south: "Южный",
  west: "Западный",
  north: "Северный",
};

// Соседние телефоны за столом узнают об изменении опросом журнала. SSE
// сознательно не вводим: четыре устройства у одного стола — это ~1 запрос/с.
const POLL_MS = 4000;

const FINISH_REASONS: Record<string, string> = {
  completed: "Партия доиграна",
  early: "Завершён досрочно",
  abandoned_lobby: "Лобби закрыто автоматически",
};

type Mode = "idle" | "win" | "draw";

function seatIndex(seat: number, dealerSeat: number): number {
  return (seat - dealerSeat + WIND_TILES.length) % WIND_TILES.length;
}

export function Table() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const [table, setTable] = useState<TableView | null>(null);
  const [events, setEvents] = useState<TableEvent[]>([]);
  const [error, setError] = useState<UiError | null>(null);
  const [mode, setMode] = useState<Mode>("idle");
  const [preview, setPreview] = useState<HandPayment | null>(null);
  const [busy, setBusy] = useState(false);
  const [tenpai, setTenpai] = useState<number[]>([]);
  const [idle, setIdle] = useState(0);
  const [rating, setRating] = useState<RatingChange | null>(null);
  // Откат и уход из-за стола спрашивают подтверждение диалогом, а не системным
  // prompt: у окна браузера нет ни стиля приложения, ни места для объяснения.
  const [revertAt, setRevertAt] = useState<number | null>(null);
  const [reason, setReason] = useState("");
  const [leavingSeat, setLeavingSeat] = useState<number | null>(null);
  const [finishing, setFinishing] = useState(false);
  const role = currentSession()?.role;

  const refresh = useCallback(
    async (signal?: AbortSignal) => {
      const [state, feed] = await Promise.all([
        getTable(id, signal),
        getTableEvents(id, 0, signal),
      ]);
      if (signal?.aborted) return;
      if ("error" in state) {
        setError(state.error);
        return;
      }
      setError(null);
      setTable(state.data);
      // Часы простоя считаются здесь, а не в рендере: время — не чистая функция,
      // а опрос всё равно идёт каждые несколько секунд.
      setIdle(idleHours(state.data.updatedAt, Date.now()));
      if ("data" in feed) setEvents(feed.data);
    },
    [id],
  );

  useEffect(() => {
    const controller = new AbortController();
    // Загрузка идёт за await, поэтому состояние меняется уже после рендера —
    // синхронного setState в эффекте нет и каскадного рендера тоже.
    void (async () => {
      await refresh(controller.signal);
    })();
    return () => controller.abort();
  }, [refresh]);

  // Пока открыта форма, опрос не трогает состояние: иначе он перезатирал бы
  // версию, на которой построено превью.
  useEffect(() => {
    if (mode !== "idle") return;
    const timer = setInterval(() => void refresh(), POLL_MS);
    return () => clearInterval(timer);
  }, [mode, refresh]);

  // Рейтинг появляется только у доигранной партии и больше не меняется, поэтому
  // читается один раз, а не вместе с опросом стола.
  const finished = table?.state === "finished";
  useEffect(() => {
    if (!finished) return;
    const controller = new AbortController();
    void (async () => {
      const result = await getMyRatingChanges(controller.signal);
      if (controller.signal.aborted || !("data" in result)) return;
      setRating(result.data.find((change) => change.tableId === id) ?? null);
    })();
    return () => controller.abort();
  }, [finished, id]);

  // Общий путь мутации: занять кнопки, показать ошибку домена, перечитать стол.
  const run = async (
    action: () => Promise<{ error: UiError } | { data: unknown }>,
  ) => {
    setBusy(true);
    const result = await action();
    setBusy(false);
    if ("error" in result) {
      setError(result.error);
      return false;
    }
    setError(null);
    await refresh();
    return true;
  };

  if (error && !table) {
    return (
      <EmptyState
        icon={Inbox}
        title="Стол недоступен"
        hint={error.detail ?? error.title}
        action={<Button onClick={() => void refresh()}>Повторить</Button>}
      />
    );
  }

  if (!table) {
    return <Skeleton width="100%" height="12rem" />;
  }

  const active = table.state === "active";
  const stale = active && idle >= STALE_HOURS;
  const myId = currentSession()?.accountId;
  const mine = myId ? table.participants.includes(myId) : false;
  const me = myId ? table.seats.indexOf(myId) : -1;
  const waitingForSubstitute = active && table.vacantSeats.length > 0;
  const handLog = events.filter((e) =>
    ["HAND_WON", "EXHAUSTIVE_DRAW", "ABORTIVE_DRAW"].includes(e.type),
  );

  return (
    <div className="table-view stack stack--wide">
      <section className="table-view__board">
        <div className="table-view__round">
          <span className="table-view__round-name">
            {ROUND_NAMES[table.roundWind] ?? table.roundWind} раунд ·{" "}
            {table.handNumber}
          </span>
          <span className="cluster">
            <Badge tone="neutral">Хонба {table.honba}</Badge>
            <Badge tone={table.riichiSticks > 0 ? "accent" : "neutral"}>
              Ставки {table.riichiSticks}
            </Badge>
            <Badge tone={active ? "positive" : "warning"}>
              {active
                ? "Партия идёт"
                : (table.finishedReason &&
                    FINISH_REASONS[table.finishedReason]) ||
                  "Завершён"}
            </Badge>
          </span>
        </div>

        <div className="table-view__seats">
          {table.scores.map((score, seat) => {
            const wind = seatIndex(seat, table.dealerSeat);
            const player = table.seats[seat];
            const vacant = table.vacantSeats.includes(seat);
            return (
              <div key={seat} className="table-view__seat">
                <ScoreChip
                  tile={WIND_TILES[wind]}
                  label={
                    vacant
                      ? WIND_NAMES[wind]!
                      : (player && table.nicknames[player]) || WIND_NAMES[wind]!
                  }
                  note={vacant ? "место свободно" : WIND_NAMES[wind]}
                  vacant={vacant}
                  score={score}
                  dealer={seat === table.dealerSeat}
                />
                {active && !vacant && (
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={busy || waitingForSubstitute}
                    onClick={() => void run(() => declareRiichi(id, seat))}
                  >
                    Риичи
                  </Button>
                )}
                {active && vacant && myId && me < 0 && (
                  <Button
                    size="sm"
                    disabled={busy}
                    onClick={() => void run(() => takeSeat(id, seat))}
                  >
                    Сесть сюда
                  </Button>
                )}
                {active && !vacant && seat === me && (
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={busy}
                    onClick={() => setLeavingSeat(seat)}
                  >
                    Уйти
                  </Button>
                )}
              </div>
            );
          })}
        </div>
      </section>

      {error && (
        <div className="table-view__error" role="alert">
          <strong>{error.title}</strong>
          {error.detail && <span>{error.detail}</span>}
        </div>
      )}

      {waitingForSubstitute && (
        <div className="table-view__stale" role="status">
          <span>
            Место {WIND_NAMES[seatIndex(table.vacantSeats[0]!, table.dealerSeat)]}{" "}
            освободилось. Раздачи не вводятся, пока за стол не сядет замена: партия с
            заменой останется в счёте, но в рейтинг не пойдёт.
          </span>
        </div>
      )}

      {stale && (
        <div className="table-view__stale" role="status">
          <span>
            Новых раздач нет уже {idle} ч. Если играть не собираетесь —
            завершите партию, иначе она останется открытой.
          </span>
          <Button
            size="sm"
            variant="secondary"
            disabled={busy}
            onClick={() => void run(() => finishTable(id))}
          >
            Завершить партию
          </Button>
        </div>
      )}

      {table.standings && (
        <section className="stack">
          <p className="section-label">Итоги партии</p>
          <table className="standings">
            <thead>
              <tr>
                <th>Место</th>
                <th>Ветер</th>
                <th>Очки</th>
                <th>Ума</th>
                <th>Итог</th>
              </tr>
            </thead>
            <tbody>
              {table.standings.map((row) => (
                <tr key={row.seat}>
                  <td className="tabular">{row.place}</td>
                  <td>{WIND_NAMES[seatIndex(row.seat, table.dealerSeat)]}</td>
                  <td className="tabular">{row.points.toLocaleString("ru-RU")}</td>
                  <td className="tabular">{row.uma.toLocaleString("ru-RU")}</td>
                  <td className="tabular">{row.result.toLocaleString("ru-RU")}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {rating && (
            <div className="stat-row">
              <span className="stat stat--accent">
                <span className="stat__value">{rating.ratingAfter}</span>
                <span className="stat__label">твой рейтинг</span>
              </span>
              <span className="stat">
                <span className="stat__value">
                  {rating.delta >= 0 ? "+" : "−"}
                  {Math.abs(rating.delta)}
                </span>
                <span className="stat__label">за эту партию</span>
              </span>
            </div>
          )}
        </section>
      )}

      {table.state === "lobby" && (
        <section className="stack">
          <p className="section-label">
            За столом {table.participants.length} из 4
          </p>
          <p>
            {table.participants
              .map((player) => table.nicknames[player] ?? "неизвестный")
              .join(" · ")}
          </p>
          <p className="screen__hint">
            Остальные заходят по этой же ссылке.
          </p>
          <div className="cluster">
            <Button
              variant="secondary"
              disabled={busy || table.participants.length >= 4}
              onClick={() => void run(() => joinTable(id))}
            >
              Сесть за стол
            </Button>
            <Button
              disabled={busy || table.participants.length < 4}
              loading={busy}
              onClick={() => void run(() => startTable(id))}
            >
              Начать партию
            </Button>
            {mine && (
              <>
                <Button
                  variant="ghost"
                  disabled={busy}
                  onClick={async () => {
                    // Вышедший больше не участник, и стол ему не отдадут.
                    if (await run(() => leaveLobby(id))) navigate("/app/tables");
                  }}
                >
                  Выйти из лобби
                </Button>
                <Button
                  variant="ghost"
                  disabled={busy}
                  onClick={() => setFinishing(true)}
                >
                  Распустить стол
                </Button>
              </>
            )}
          </div>
        </section>
      )}

      {active && mode === "idle" && (
        <div className="cluster">
          <Button onClick={() => setMode("win")}>Победа</Button>
          <Button variant="secondary" onClick={() => setMode("draw")}>
            Ничья
          </Button>
          <Button
            variant="ghost"
            disabled={busy}
            onClick={() =>
              void run(() => recordDraw(id, { type: "ABORTIVE" }))
            }
          >
            Досрочная ничья
          </Button>
          {mine && (
            <Button
              variant="ghost"
              disabled={busy}
              onClick={() => setFinishing(true)}
            >
              Завершить партию
            </Button>
          )}
        </div>
      )}

      {active && mode === "win" && (
        <section className="stack">
          <HandEditor
            table={table}
            busy={busy}
            onPreview={async (hand: HandRequest) => {
              setBusy(true);
              const result = await previewHand(id, hand);
              setBusy(false);
              if ("error" in result) {
                setError(result.error);
                setPreview(null);
              } else {
                setError(null);
                setPreview(result.data);
              }
            }}
            onConfirm={async (hand: HandRequest) => {
              const ok = await run(() => confirmHand(id, hand));
              if (ok) {
                setPreview(null);
                setMode("idle");
              }
            }}
          />
          {preview && <ScoreBreakdown payment={preview} table={table} />}
          <Button
            variant="ghost"
            onClick={() => {
              setPreview(null);
              setMode("idle");
            }}
          >
            Отмена
          </Button>
        </section>
      )}

      {active && mode === "draw" && (
        <section className="stack">
          <p className="section-label">Кто в темпае</p>
          <div className="cluster">
            {table.scores.map((_, seat) => {
              const on = tenpai.includes(seat);
              const wind = seatIndex(seat, table.dealerSeat);
              return (
                <Button
                  key={seat}
                  size="sm"
                  variant={on ? "primary" : "secondary"}
                  aria-pressed={on}
                  onClick={() =>
                    setTenpai(
                      on ? tenpai.filter((s) => s !== seat) : [...tenpai, seat],
                    )
                  }
                >
                  {WIND_NAMES[wind]}
                </Button>
              );
            })}
          </div>
          <div className="cluster">
            <Button
              disabled={busy}
              loading={busy}
              onClick={async () => {
                const ok = await run(() =>
                  recordDraw(id, { type: "EXHAUSTIVE", tenpaiSeats: tenpai }),
                );
                if (ok) {
                  setTenpai([]);
                  setMode("idle");
                }
              }}
            >
              Подтвердить ничью
            </Button>
            <Button
              variant="ghost"
              onClick={() => {
                setTenpai([]);
                setMode("idle");
              }}
            >
              Отмена
            </Button>
          </div>
        </section>
      )}

      <Dialog
        open={revertAt !== null}
        onClose={() => setRevertAt(null)}
        title="Откатить раздачу"
        footer={
          <>
            <Button variant="ghost" onClick={() => setRevertAt(null)}>
              Отмена
            </Button>
            <Button
              disabled={busy || !reason.trim()}
              loading={busy}
              onClick={async () => {
                const to = revertAt;
                if (to === null) return;
                const ok = await run(() =>
                  revertTable(id, to - 1, reason.trim()),
                );
                if (ok) setRevertAt(null);
              }}
            >
              Откатить
            </Button>
          </>
        }
      >
        <p className="screen__hint">
          Партия вернётся к состоянию до этой раздачи. Всё, что было после,
          придётся ввести заново, а рейтинг за партию снимется. Запись останется
          в журнале.
        </p>
        <Field label="Причина" hint="Её увидят участники стола в журнале.">
          {(props) => (
            <Textarea
              {...props}
              rows={3}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              placeholder="Например: ошиблись победителем"
            />
          )}
        </Field>
      </Dialog>

      <Dialog
        open={finishing}
        onClose={() => setFinishing(false)}
        title={table.state === "lobby" ? "Распустить стол" : "Завершить партию"}
        footer={
          <>
            <Button variant="ghost" onClick={() => setFinishing(false)}>
              {table.state === "lobby" ? "Оставить" : "Продолжить игру"}
            </Button>
            <Button
              disabled={busy}
              loading={busy}
              onClick={async () => {
                const ok = await run(() => finishTable(id));
                if (ok) setFinishing(false);
              }}
            >
              {table.state === "lobby" ? "Распустить" : "Завершить"}
            </Button>
          </>
        }
      >
        <p className="screen__hint">
          {table.state === "lobby"
            ? "Партия не начиналась, терять нечего: стол закроется, и все выйдут из лобби."
            : "Очки останутся как есть, итоговая таблица посчитается с умой. Но в рейтинг такая партия не пойдёт: в зачёт идут только доигранные до конца формата."}
        </p>
      </Dialog>

      <Dialog
        open={leavingSeat !== null}
        onClose={() => setLeavingSeat(null)}
        title="Уйти из-за стола"
        footer={
          <>
            <Button variant="ghost" onClick={() => setLeavingSeat(null)}>
              Остаться
            </Button>
            <Button
              disabled={busy}
              loading={busy}
              onClick={async () => {
                const seat = leavingSeat;
                if (seat === null) return;
                const ok = await run(() => leaveSeat(id, seat));
                if (ok) setLeavingSeat(null);
              }}
            >
              Уйти
            </Button>
          </>
        }
      >
        <p className="screen__hint">
          Очки останутся на месте, и его займёт замена. Пока место пустое,
          раздачи не вводятся, а в рейтинг такая партия уже не пойдёт.
        </p>
      </Dialog>

      <Tabs
        tabs={[
          {
            id: "hands",
            label: `Раздачи (${handLog.length})`,
            panel: (
              <HandLog
                events={handLog}
                dealerSeat={table.dealerSeat}
                onRevert={
                  table.state !== "lobby" &&
                  (role === "moderator" || role === "admin")
                    ? (sequence) => {
                        setReason("");
                        setRevertAt(sequence);
                      }
                    : undefined
                }
              />
            ),
          },
          {
            id: "journal",
            label: `Журнал (${events.length})`,
            panel: (
              <Journal
                events={events}
                nicknames={table.nicknames}
                dealerSeat={table.dealerSeat}
              />
            ),
          },
        ]}
      />
    </div>
  );
}

function ScoreBreakdown({
  payment,
  table,
}: {
  payment: HandPayment;
  table: TableView;
}) {
  return (
    <div className="breakdown">
      <div className="cluster">
        <Badge tone="accent">
          {payment.han} хан · {payment.fu} фу
        </Badge>
        {payment.yakumanCount > 0 && (
          <Badge tone="positive">
            Якуман ×{payment.yakumanCount}
          </Badge>
        )}
        {payment.riichiSticksAwarded > 0 && (
          <Badge tone="neutral">
            Ставки +{payment.riichiSticksAwarded * 1000}
          </Badge>
        )}
      </div>
      {payment.dora && (
        <div className="cluster">
          {(
            [
              ["ordinary", "дора"],
              ["kan", "кан-дора"],
              ["ura", "ура-дора"],
              ["aka", "ака"],
            ] as const
          )
            .filter(([key]) => (payment.dora?.[key] ?? 0) > 0)
            .map(([key, label]) => (
              <Badge key={key} tone="neutral">
                {label} +{payment.dora?.[key]}
              </Badge>
            ))}
        </div>
      )}
      <div className="cluster">
        {payment.yaku.length === 0 ? (
          <Badge tone="danger">Яку нет — рука не выигрывает</Badge>
        ) : (
          payment.yaku.map((y) => (
            <Badge key={y} tone="neutral">
              {y}
            </Badge>
          ))
        )}
      </div>
      <div className="breakdown__deltas">
        {payment.seatDelta.map((delta, seat) => (
          <ScoreChip
            key={seat}
            tile={WIND_TILES[seatIndex(seat, table.dealerSeat)]}
            label={WIND_NAMES[seatIndex(seat, table.dealerSeat)]!}
            score={table.scores[seat]! + delta}
            delta={delta}
            dealer={seat === table.dealerSeat}
          />
        ))}
      </div>
    </div>
  );
}

function HandLog({
  events,
  dealerSeat,
  onRevert,
}: {
  events: TableEvent[];
  dealerSeat: number;
  onRevert?: (sequence: number) => void;
}) {
  if (events.length === 0) {
    return (
      <EmptyState
        icon={Inbox}
        title="Раздач ещё не было"
        hint="Подтверждённые раздачи появятся здесь с разбором очков."
      />
    );
  }
  return (
    <ol className="hand-log">
      {events.map((e) => {
        const p = e.payload as Record<string, unknown>;
        const winner = typeof p.winnerSeat === "number" ? p.winnerSeat : null;
        return (
          <li key={e.sequence} className="hand-log__item">
            <span className="tabular">#{e.sequence}</span>
            {e.type === "HAND_WON" && winner !== null ? (
              <span>
                Победа ·{" "}
                {WIND_NAMES[seatIndex(winner, dealerSeat)]} · {String(p.han)} хан{" "}
                {String(p.fu)} фу
              </span>
            ) : e.type === "EXHAUSTIVE_DRAW" ? (
              <span>Ничья · темпай {JSON.stringify(p.tenpaiSeats ?? [])}</span>
            ) : (
              <span>Досрочная ничья</span>
            )}
            {onRevert && (
              <Button
                size="sm"
                variant="ghost"
                onClick={() => onRevert(e.sequence)}
              >
                Откатить
              </Button>
            )}
          </li>
        );
      })}
    </ol>
  );
}

/**
 * Журнал стола человеческими словами.
 *
 * Типы событий — это имена в коде: игроку они говорят не больше, чем номер строки.
 * Причина отката показывается целиком: ради неё её и требуют.
 */
function describe(
  event: TableEvent,
  nicknames: Record<string, string>,
  dealerSeat: number,
): string {
  const p = event.payload as Record<string, unknown>;
  const who = (key: string) =>
    nicknames[String(p[key])] ?? "неизвестный";
  const wind = (key: string) =>
    typeof p[key] === "number"
      ? WIND_NAMES[seatIndex(p[key] as number, dealerSeat)]
      : "";
  switch (event.type) {
    case "TABLE_CREATED":
      return "Стол создан";
    case "PLAYER_JOINED":
      return `${who("playerId")} сел за стол`;
    case "PLAYER_LEFT":
      return `${who("playerId")} вышел из лобби`;
    case "GAME_STARTED":
      return "Партия началась";
    case "RIICHI_DECLARED":
      return `Риичи · ${wind("seat")}`;
    case "HAND_WON":
      // У раздач, записанных до того, как в событие стали класть игрока, есть только место.
      return `Победа · ${
        p.winnerAccount ? who("winnerAccount") : wind("winnerSeat")
      } · ${p.han} хан ${p.fu} фу`;
    case "EXHAUSTIVE_DRAW":
      return "Ничья по исчерпанию стены";
    case "ABORTIVE_DRAW":
      return "Досрочная ничья";
    case "SEAT_LEFT":
      return `${who("playerId")} ушёл из-за стола · ${wind("seat")}`;
    case "SEAT_TAKEN":
      return `${who("playerId")} сел вместо ${who("replaced")} · ${wind("seat")}`;
    case "TABLE_REVERTED":
      return `Откат к версии ${p.toVersion} · ${p.reason}`;
    case "TABLE_FINISHED":
      return p.reason === "COMPLETED"
        ? `Партия доиграна · ${p.handsPlayed} раздач`
        : p.reason === "ABANDONED_LOBBY"
          ? "Лобби закрыто автоматически"
          : `Партия завершена досрочно · ${p.handsPlayed} раздач`;
    default:
      return event.type;
  }
}

function Journal({
  events,
  nicknames,
  dealerSeat,
}: {
  events: TableEvent[];
  nicknames: Record<string, string>;
  dealerSeat: number;
}) {
  return (
    <ol className="hand-log">
      {events.map((e) => (
        <li key={e.sequence} className="hand-log__item">
          <span className="tabular">#{e.sequence}</span>
          <span>{describe(e, nicknames, dealerSeat)}</span>
        </li>
      ))}
    </ol>
  );
}
