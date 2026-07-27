import createClient, { type Client, type Middleware } from "openapi-fetch";
import { config } from "@/config";
import type { paths } from "./schema";

const acceptLanguage = () =>
  typeof navigator !== "undefined" ? navigator.language : "ru";

// Single request layer: same-site cookies, the UI locale and a per-request
// correlation id on every call. Deadlines/cancellation are passed per call via
// `signal` (openapi-fetch forwards it to fetch) — never invented client-side.
const context: Middleware = {
  async onRequest({ request }) {
    request.headers.set("X-Correlation-Id", crypto.randomUUID());
    if (!request.headers.has("Accept-Language")) {
      request.headers.set("Accept-Language", acceptLanguage());
    }
    return request;
  },
};

export function createApiClient(
  baseUrl: string = config.apiBaseUrl,
): Client<paths> {
  const client = createClient<paths>({ baseUrl, credentials: "include" });
  client.use(context);
  return client;
}

export const api = createApiClient();
