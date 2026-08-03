import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate } from "react-router";
import { Inbox, Plus } from "lucide-react";
import type { UiError } from "@/api/errors";
import { createTable, listMyTables, type TableSummary } from "@/api/tables";
import { Badge, Button, Card, EmptyState, Field, Skeleton } from "@/ui";

const STATE_NAMES: Record<string, string> = {
  lobby: "Лобби",
  active: "Идёт",
  finished: "Завершён",
};

const FORMAT_NAMES: Record<string, string> = {
  hanchan: "Ханчан",
  tonpuusen: "Тонпусен",
};

/**
 * Список столов игрока и создание нового.
 *
 * Смысл экрана — найти свой стол с любого устройства: сессия привязана к аккаунту,
 * поэтому партию продолжает любой участник со своего телефона. Отдельной передачи
 * ведения нет: две попытки применить одну раздачу разводит проверка версии.
 */
export function Tables() {
  const navigate = useNavigate();
  const [tables, setTables] = useState<TableSummary[] | null>(null);
  const [error, setError] = useState<UiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [format, setFormat] = useState<"HANCHAN" | "TONPUUSEN">("HANCHAN");

  const refresh = useCallback(async (signal?: AbortSignal) => {
    const result = await listMyTables(signal);
    if (signal?.aborted) return;
    if ("error" in result) setError(result.error);
    else {
      setError(null);
      setTables(result.data);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      await refresh(controller.signal);
    })();
    return () => controller.abort();
  }, [refresh]);

  const create = async () => {
    setBusy(true);
    const result = await createTable("rrc-ru", format);
    setBusy(false);
    if ("error" in result) {
      setError(result.error);
      return;
    }
    navigate(`/table/${result.data.id}`);
  };

  return (
    <div className="screen stack stack--wide">
      <h1 className="screen__title">Столы</h1>

      <Card>
        <div className="hand-editor__grid">
          <Field label="Формат" hint="Правила — RRC-RU.">
            {(field) => (
              <select
                {...field}
                className="select"
                value={format}
                onChange={(e) =>
                  setFormat(e.target.value as "HANCHAN" | "TONPUUSEN")
                }
              >
                <option value="HANCHAN">Ханчан</option>
                <option value="TONPUUSEN">Тонпусен</option>
              </select>
            )}
          </Field>
        </div>
        <Button disabled={busy} loading={busy} onClick={() => void create()}>
          <Plus size={18} aria-hidden />
          Создать стол
        </Button>
      </Card>

      {error && (
        <div className="table-view__error" role="alert">
          <strong>{error.title}</strong>
          {error.detail && <span>{error.detail}</span>}
        </div>
      )}

      {tables === null && <Skeleton width="100%" height="6rem" />}

      {tables !== null && tables.length === 0 && (
        <EmptyState
          icon={Inbox}
          title="Столов пока нет"
          hint="Создайте стол и позовите тиммейтов ссылкой — партия начнётся, когда сядут четверо."
        />
      )}

      {tables !== null && tables.length > 0 && (
        <ul className="hand-log">
          {tables.map((table) => (
            <li key={table.id} className="hand-log__item">
              <Badge tone={table.state === "active" ? "positive" : "neutral"}>
                {STATE_NAMES[table.state] ?? table.state}
              </Badge>
              <span>{FORMAT_NAMES[table.format] ?? table.format}</span>
              <span className="tabular">раздач: {table.handsPlayed}</span>
              <Link to={`/table/${table.id}`}>Открыть</Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
