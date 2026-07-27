import { api } from "./client";
import { toUiError, type UiError } from "./errors";

export type Result<T> = { data: T } | { error: UiError };

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
