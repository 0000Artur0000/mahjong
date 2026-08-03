import { api } from "./client";
import type { components } from "./schema";
import { call, type Result } from "./system";

export type PrivateProfile = components["schemas"]["PrivateProfile"];

/** Свой профиль. Экрану нужен из него только `role`: показывать ли откат раздачи. */
export function getMyProfile(
  signal?: AbortSignal,
): Promise<Result<PrivateProfile>> {
  return call(() => api.GET("/api/v1/account/profile", { signal }));
}
