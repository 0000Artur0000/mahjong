import type { components } from "./schema";

// The server's Problem shape comes from the generated schema, never hand-written.
type ServerProblem = components["schemas"]["Problem"];

export type UiError = {
  kind: "problem" | "network" | "unknown";
  status: number;
  title: string;
  detail?: string;
  code?: string;
  correlationId?: string;
};

function isProblem(value: unknown): value is ServerProblem {
  return (
    typeof value === "object" &&
    value !== null &&
    "title" in value &&
    "status" in value &&
    "code" in value
  );
}

// Normalise an RFC 9457 Problem Details body, an unexpected non-2xx, or a thrown
// fetch failure into one UI error model. The correlation id stays visible so it
// can be surfaced in support details (never hidden).
export function toUiError(error: unknown, response?: Response): UiError {
  const correlationId = response?.headers.get("x-correlation-id") ?? undefined;
  if (isProblem(error)) {
    return {
      kind: "problem",
      status: error.status,
      title: error.title,
      detail: error.detail,
      code: error.code,
      correlationId: error.correlationId ?? correlationId,
    };
  }
  if (response) {
    return {
      kind: "unknown",
      status: response.status,
      title: `Ошибка ${response.status}`,
      correlationId,
    };
  }
  return { kind: "network", status: 0, title: "Нет соединения с сервером" };
}
