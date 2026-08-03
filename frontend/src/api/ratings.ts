import { api } from "./client";
import type { components } from "./schema";
import { call, type Result } from "./system";

export type LadderEntry = components["schemas"]["LadderEntryView"];
export type RatingChange = components["schemas"]["RatingChangeView"];
export type FormatSummary = components["schemas"]["FormatSummaryView"];

/** Лестница формата, сильные сверху. */
export function getLadder(
  format: string,
  signal?: AbortSignal,
): Promise<Result<LadderEntry[]>> {
  return call(() =>
    api.GET("/api/v1/ratings", { params: { query: { format } }, signal }),
  );
}

/** Свой итог по форматам: партии, места и рейтинг. */
export function getMyRatingSummary(
  signal?: AbortSignal,
): Promise<Result<FormatSummary[]>> {
  return call(() => api.GET("/api/v1/ratings/me/summary", { signal }));
}

/** Свои изменения рейтинга, свежие сверху. */
export function getMyRatingChanges(
  signal?: AbortSignal,
): Promise<Result<RatingChange[]>> {
  return call(() => api.GET("/api/v1/ratings/me", { signal }));
}
