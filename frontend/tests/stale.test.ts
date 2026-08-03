import { describe, expect, it } from "vitest";
import { STALE_HOURS, idleHours } from "../src/screens/table/stale";

// Баннер «партия остыла» показывается по этому счётчику, поэтому проверяем ту же
// функцию, которую вызывает экран.
describe("idleHours", () => {
  const now = Date.parse("2026-07-31T12:00:00Z");

  it("counts whole hours since the last move", () => {
    expect(idleHours("2026-07-31T11:00:00Z", now)).toBe(1);
    expect(idleHours("2026-07-30T23:30:00Z", now)).toBe(12);
  });

  it("stays below the threshold right up to it", () => {
    expect(idleHours("2026-07-31T00:01:00Z", now)).toBe(STALE_HOURS - 1);
    expect(idleHours("2026-07-31T00:00:00Z", now)).toBe(STALE_HOURS);
  });

  it("shows no idle time for a broken or future timestamp", () => {
    expect(idleHours("", now)).toBe(0);
    expect(idleHours("2026-08-01T00:00:00Z", now)).toBe(0);
  });
});
