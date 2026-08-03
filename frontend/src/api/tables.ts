import { api } from "./client";
import type { components } from "./schema";
import { call, type Result } from "./system";

// Формы приходят из контракта, руками ничего не объявляем: переименование поля
// в OpenAPI ломает компиляцию, а не поведение в рантайме.
export type TableView = components["schemas"]["TableView"];
export type HandPayment = components["schemas"]["HandPaymentView"];
export type TableEvent = components["schemas"]["TableEventView"];
export type HandRequest = components["schemas"]["HandRequest"];
export type DrawRequest = components["schemas"]["DrawRequest"];

export type TableSummary = components["schemas"]["TableSummaryView"];
export type WonHand = components["schemas"]["WonHandView"];
export type TableStats = components["schemas"]["TableStatsView"];

/** Пилотные метрики по столам. Только модератору, без персональных данных. */
export function getTableStats(
  signal?: AbortSignal,
): Promise<Result<TableStats>> {
  return call(() => api.GET("/api/v1/tables/stats", { signal }));
}

/** Свои выигранные раздачи, свежие сверху. Откатанные сюда не попадают. */
export function listMyHands(signal?: AbortSignal): Promise<Result<WonHand[]>> {
  return call(() => api.GET("/api/v1/tables/hands/mine", { signal }));
}

/** Столы игрока, свежие сверху. */
export function listMyTables(
  signal?: AbortSignal,
): Promise<Result<TableSummary[]>> {
  return call(() => api.GET("/api/v1/tables", { signal }));
}

export function createTable(
  rulesetKey: string,
  format: "HANCHAN" | "TONPUUSEN",
): Promise<Result<TableView>> {
  return call(() => api.POST("/api/v1/tables", { body: { rulesetKey, format } }));
}

export function joinTable(tableId: string): Promise<Result<TableView>> {
  return call(() =>
    api.POST("/api/v1/tables/{tableId}/players", {
      params: { path: { tableId } },
    }),
  );
}

export function getTable(
  tableId: string,
  signal?: AbortSignal,
): Promise<Result<TableView>> {
  return call(() =>
    api.GET("/api/v1/tables/{tableId}", {
      params: { path: { tableId } },
      signal,
    }),
  );
}

/** Лента журнала после известного клиенту номера. */
export function getTableEvents(
  tableId: string,
  since: number,
  signal?: AbortSignal,
): Promise<Result<TableEvent[]>> {
  return call(() =>
    api.GET("/api/v1/tables/{tableId}/events", {
      params: { path: { tableId }, query: { since } },
      signal,
    }),
  );
}

export function startTable(tableId: string): Promise<Result<TableView>> {
  return call(() =>
    api.POST("/api/v1/tables/{tableId}/start", {
      params: { path: { tableId } },
    }),
  );
}

export function declareRiichi(
  tableId: string,
  seat: number,
): Promise<Result<TableView>> {
  return call(() =>
    api.POST("/api/v1/tables/{tableId}/riichi", {
      params: { path: { tableId } },
      body: { seat },
    }),
  );
}

/** Разбор руки без изменения стола: можно жать сколько угодно раз. */
export function previewHand(
  tableId: string,
  hand: HandRequest,
): Promise<Result<HandPayment>> {
  return call(() =>
    api.POST("/api/v1/tables/{tableId}/hands/preview", {
      params: { path: { tableId } },
      body: hand,
    }),
  );
}

/**
 * Подтверждение раздачи. `expectedVersion` — версия стола, на которой строилось
 * превью; если стол ушёл вперёд, сервер ответит 409 и очки не применятся дважды.
 */
export function confirmHand(
  tableId: string,
  hand: HandRequest,
): Promise<Result<HandPayment>> {
  return call(() =>
    api.POST("/api/v1/tables/{tableId}/hands", {
      params: { path: { tableId } },
      body: hand,
    }),
  );
}

export function recordDraw(
  tableId: string,
  draw: DrawRequest,
): Promise<Result<TableView>> {
  return call(() =>
    api.POST("/api/v1/tables/{tableId}/draws", {
      params: { path: { tableId } },
      body: draw,
    }),
  );
}

/** Выйти из лобби, пока партия не началась. */
export function leaveLobby(tableId: string): Promise<Result<TableView>> {
  return call(() =>
    api.DELETE("/api/v1/tables/{tableId}/players/me", {
      params: { path: { tableId } },
    }),
  );
}

/** Уйти из-за стола посреди партии: место освобождается, очки остаются. */
export function leaveSeat(
  tableId: string,
  seat: number,
): Promise<Result<TableView>> {
  return call(() =>
    api.DELETE("/api/v1/tables/{tableId}/seats/{seat}/player", {
      params: { path: { tableId, seat } },
    }),
  );
}

/** Сесть на освободившееся место. Партия с заменой перестаёт идти в рейтинг. */
export function takeSeat(
  tableId: string,
  seat: number,
): Promise<Result<TableView>> {
  return call(() =>
    api.POST("/api/v1/tables/{tableId}/seats/{seat}/player", {
      params: { path: { tableId, seat } },
    }),
  );
}

/** Откат стола к прошлой версии. Право модератора, сервер это проверяет. */
export function revertTable(
  tableId: string,
  toVersion: number,
  reason: string,
): Promise<Result<TableView>> {
  return call(() =>
    api.POST("/api/v1/tables/{tableId}/revert", {
      params: { path: { tableId } },
      body: { toVersion, reason },
    }),
  );
}

export function finishTable(tableId: string): Promise<Result<TableView>> {
  return call(() =>
    api.POST("/api/v1/tables/{tableId}/finish", {
      params: { path: { tableId } },
    }),
  );
}
