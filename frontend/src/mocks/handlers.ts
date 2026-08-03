import { http, HttpResponse } from "msw";

// Mock handlers stand in for the backend so screens work independently of it.
// The `*` origin wildcard matches any host, so the same handlers serve the
// browser worker (same origin) and the Node server used in tests. Grow this
// list from shared contract examples.
const ME = "3f7c1a6e-0b2d-4a51-9c8e-1d5b0e2a7c44";

const NICKNAMES: Record<string, string> = {
  [ME]: "Кагами",
  "b1a1c2d3-1111-4a51-9c8e-1d5b0e2a7c01": "Ито",
  "b1a1c2d3-2222-4a51-9c8e-1d5b0e2a7c02": "Сато",
  "b1a1c2d3-3333-4a51-9c8e-1d5b0e2a7c03": "Морита",
  "b1a1c2d3-4444-4a51-9c8e-1d5b0e2a7c04": "Нагаи",
  "b1a1c2d3-5555-4a51-9c8e-1d5b0e2a7c05": "Оониси",
};

const LADDER = [
  { accountId: "b1a1c2d3-1111-4a51-9c8e-1d5b0e2a7c01", rating: 1584, games: 23 },
  { accountId: ME, rating: 1541, games: 17 },
  { accountId: "b1a1c2d3-2222-4a51-9c8e-1d5b0e2a7c02", rating: 1512, games: 31 },
  { accountId: "b1a1c2d3-3333-4a51-9c8e-1d5b0e2a7c03", rating: 1498, games: 9 },
  { accountId: "b1a1c2d3-4444-4a51-9c8e-1d5b0e2a7c04", rating: 1463, games: 12 },
  { accountId: "b1a1c2d3-5555-4a51-9c8e-1d5b0e2a7c05", rating: 1402, games: 6 },
];

export const handlers = [
  http.get("*/api/v1/system/time", () =>
    HttpResponse.json({ serverTime: new Date().toISOString() }),
  ),

  http.get("*/api/v1/account/profile", () =>
    HttpResponse.json({
      accountId: ME,
      nickname: NICKNAMES[ME],
      city: "Иркутск",
      avatarMediaId: null,
      status: "active",
      role: "moderator",
      privacy: { showCity: true, showClubs: true },
    }),
  ),

  http.get("*/api/v1/ratings", ({ request }) => {
    const format = new URL(request.url).searchParams.get("format");
    const rows = format === "hanchan" ? LADDER : LADDER.slice(2);
    return HttpResponse.json(
      rows.map((row) => ({ ...row, nickname: NICKNAMES[row.accountId] ?? null })),
    );
  }),

  http.get("*/api/v1/ratings/me/summary", () =>
    HttpResponse.json([
      {
        format: "hanchan",
        rating: 1541,
        games: 17,
        places: [6, 4, 4, 3],
        averagePlace: 2.24,
      },
      {
        format: "tonpuusen",
        rating: 1489,
        games: 5,
        places: [1, 1, 2, 1],
        averagePlace: 2.6,
      },
    ]),
  ),

  http.get("*/api/v1/tables/hands/mine", () =>
    HttpResponse.json([
      {
        tableId: "aa11bb22-cc33-4d44-8e55-ff6677889900",
        sequence: 42,
        han: 13,
        fu: 20,
        yaku: ["Kokushi Musou"],
        at: "2026-07-28T18:12:00Z",
      },
      {
        tableId: "aa11bb22-cc33-4d44-8e55-ff6677889900",
        sequence: 31,
        han: 4,
        fu: 30,
        yaku: ["Riichi", "Tsumo", "Pinfu", "Dora 1"],
        at: "2026-07-28T17:40:00Z",
      },
      {
        tableId: "aa11bb22-cc33-4d44-8e55-ff6677889901",
        sequence: 12,
        han: 2,
        fu: 40,
        yaku: ["Sanshoku"],
        at: "2026-07-21T19:05:00Z",
      },
    ]),
  ),

  http.get("*/api/v1/tables/stats", () =>
    HttpResponse.json({
      lobbies: 2,
      active: 3,
      stale: 1,
      completed: 47,
      abandonedEarly: 9,
      abandonedLobby: 14,
      completionRate: 67.1,
      handsPerCompletedGame: 8.4,
      medianMinutes: 74,
      p90Minutes: 121,
    }),
  ),
];
