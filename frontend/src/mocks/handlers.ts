import { http, HttpResponse } from "msw";

// Mock handlers stand in for the backend so screens work independently of it.
// The `*` origin wildcard matches any host, so the same handlers serve the
// browser worker (same origin) and the Node server used in tests. Grow this
// list from shared contract examples.
export const handlers = [
  http.get("*/api/v1/system/time", () =>
    HttpResponse.json({ serverTime: new Date().toISOString() }),
  ),
];
