import { afterEach, describe, expect, it } from "vitest";
import { correlationId } from "../src/api/client";

// В insecure context (страница по IP, не по localhost и не по https) метода
// crypto.randomUUID нет. Раньше на этом падал каждый запрос ещё до отправки.
const UUID_V4 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const real = crypto.randomUUID;

afterEach(() => {
  Object.defineProperty(crypto, "randomUUID", { value: real, configurable: true });
});

describe("correlationId", () => {
  it("uses the native generator in a secure context", () => {
    expect(correlationId()).toMatch(UUID_V4);
  });

  it("falls back without crypto.randomUUID", () => {
    Object.defineProperty(crypto, "randomUUID", {
      value: undefined,
      configurable: true,
    });

    const first = correlationId();
    const second = correlationId();

    expect(first).toMatch(UUID_V4);
    expect(second).toMatch(UUID_V4);
    expect(first).not.toEqual(second);
  });

  it("matches the header pattern the backend accepts", () => {
    Object.defineProperty(crypto, "randomUUID", {
      value: undefined,
      configurable: true,
    });

    // OpenAPI: ^[A-Za-z0-9._-]{1,64}$
    expect(correlationId()).toMatch(/^[A-Za-z0-9._-]{1,64}$/);
  });
});
