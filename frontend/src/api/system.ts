import { api } from "./client";
import { toUiError, type UiError } from "./errors";

export type Result<T> = { data: T } | { error: UiError };

/**
 * Общий разбор ответа openapi-fetch: данные, доменная ошибка или сеть.
 *
 * Живёт рядом с Result: иначе каждый модуль API пишет свой try/catch и
 * по-своему решает, что считать ошибкой.
 */
export async function call<T>(
  run: () => Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<Result<T>> {
  try {
    const { data, error, response } = await run();
    if (data !== undefined) return { data };
    return { error: toUiError(error, response) };
  } catch (cause) {
    return { error: toUiError(cause) };
  }
}

// Example typed consumer. If the OpenAPI renames this path or drops `serverTime`,
// this stops compiling — the compile-time contract check the plan asks for.
export async function getServerTime(
  signal?: AbortSignal,
): Promise<Result<string>> {
  try {
    const { data, error, response } = await api.GET("/api/v1/system/time", {
      signal,
    });
    if (data) return { data: data.serverTime };
    return { error: toUiError(error, response) };
  } catch (cause) {
    return { error: toUiError(cause) };
  }
}
