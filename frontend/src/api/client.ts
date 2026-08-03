import createClient, { type Client, type Middleware } from "openapi-fetch";
import { config } from "@/config";
import type { paths } from "./schema";

const acceptLanguage = () =>
  typeof navigator !== "undefined" ? navigator.language : "ru";

/**
 * Идентификатор запроса для сквозной трассировки.
 *
 * `crypto.randomUUID` живёт только в secure context: HTTPS или `http://localhost`.
 * По адресу в локальной сети (например, WSL2-адрес вида `http://172.20.x.x:5173`)
 * страница открывается как insecure, метода нет, и без этого запаса падал бы каждый
 * запрос ещё до отправки. `getRandomValues` доступен и в insecure context, поэтому
 * запасной путь остаётся случайным, а не предсказуемым.
 */
export function correlationId(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();

  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6]! & 0x0f) | 0x40; // версия 4
  bytes[8] = (bytes[8]! & 0x3f) | 0x80; // вариант RFC 4122
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join("-");
}

/** Методы, для которых Spring Security требует CSRF-токен. */
const MUTATING = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/**
 * Токен берётся перед каждой мутацией, а не кэшируется.
 *
 * ponytail: один лишний GET на мутацию. Кэш пришлось бы инвалидировать при входе,
 * выходе и step-up — там меняется сессия, а с ней и токен, и просроченный кэш дал бы
 * необъяснимый 403. За партию мутаций десятки, так что платить нечем. Если появится
 * пакетный ввод судьи — кэшировать с повтором после первого 403.
 */
async function csrfHeader(baseUrl: string): Promise<[string, string]> {
  const response = await fetch(`${baseUrl}/api/v1/auth/csrf`, {
    credentials: "include",
    headers: { "X-Correlation-Id": correlationId() },
  });
  if (!response.ok) {
    throw new Error(`csrf token unavailable: ${response.status}`);
  }
  const { headerName, token } = (await response.json()) as {
    headerName: string;
    token: string;
  };
  return [headerName, token];
}

// Single request layer: same-site cookies, the UI locale, a per-request
// correlation id and the CSRF token on mutations. Deadlines/cancellation are
// passed per call via `signal` (openapi-fetch forwards it) — never invented
// client-side.
function contextMiddleware(baseUrl: string): Middleware {
  return {
    async onRequest({ request }) {
      request.headers.set("X-Correlation-Id", correlationId());
      if (!request.headers.has("Accept-Language")) {
        request.headers.set("Accept-Language", acceptLanguage());
      }
      if (MUTATING.has(request.method)) {
        const [name, token] = await csrfHeader(baseUrl);
        request.headers.set(name, token);
      }
      return request;
    },
  };
}

export function createApiClient(
  baseUrl: string = config.apiBaseUrl,
): Client<paths> {
  const client = createClient<paths>({ baseUrl, credentials: "include" });
  client.use(contextMiddleware(baseUrl));
  return client;
}

export const api = createApiClient();
