import { http, HttpResponse } from "msw";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { createApiClient } from "@/api/client";
import { toUiError } from "@/api/errors";
import { server } from "@/mocks/server";

// Create the client after listen() so it captures MSW's patched global fetch.
// Absolute base gives Node fetch a full URL; relative handlers still match.
let api: ReturnType<typeof createApiClient>;

beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
  api = createApiClient("http://localhost");
});
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe("api client on mocks", () => {
  it("returns typed server time from the mock handler", async () => {
    const { data, error } = await api.GET("/api/v1/system/time");
    expect(error).toBeUndefined();
    expect(typeof data?.serverTime).toBe("string");
  });

  it("normalises an RFC 9457 Problem Details response", async () => {
    server.use(
      http.get("*/api/v1/system/time", () =>
        HttpResponse.json(
          {
            type: "about:blank",
            title: "Сервис недоступен",
            status: 503,
            code: "service_unavailable",
            correlationId: "corr-9",
          },
          { status: 503, headers: { "x-correlation-id": "corr-9" } },
        ),
      ),
    );
    const { error, response } = await api.GET("/api/v1/system/time");
    expect(toUiError(error, response)).toMatchObject({
      kind: "problem",
      status: 503,
      code: "service_unavailable",
      correlationId: "corr-9",
    });
  });

  // Spring Security включает CSRF по умолчанию: без заголовка любая мутация — 403.
  it("attaches the CSRF token to a mutating request", async () => {
    let sent: string | null = null;
    let csrfCalls = 0;
    server.use(
      http.get("*/api/v1/auth/csrf", () => {
        csrfCalls++;
        return HttpResponse.json({ headerName: "X-CSRF-TOKEN", token: "tok-42" });
      }),
      http.post("*/api/v1/tables/:id/start", ({ request }) => {
        sent = request.headers.get("X-CSRF-TOKEN");
        return HttpResponse.json({ id: "t" });
      }),
    );

    await api.POST("/api/v1/tables/{tableId}/start", {
      params: { path: { tableId: "t" } },
    });

    expect(sent).toBe("tok-42");
    expect(csrfCalls).toBe(1);
  });

  it("does not ask for a CSRF token on a read", async () => {
    let csrfCalls = 0;
    server.use(
      http.get("*/api/v1/auth/csrf", () => {
        csrfCalls++;
        return HttpResponse.json({ headerName: "X-CSRF-TOKEN", token: "tok" });
      }),
    );

    await api.GET("/api/v1/system/time");

    expect(csrfCalls).toBe(0);
  });

  it("maps a thrown fetch failure to a network error", () => {
    expect(toUiError(new TypeError("Failed to fetch"))).toMatchObject({
      kind: "network",
      status: 0,
    });
  });
});
