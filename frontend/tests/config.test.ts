import { describe, expect, it } from "vitest";
import { resolveConfig } from "@/config";

describe("resolveConfig", () => {
  it("falls back to compile-time defaults when no runtime config is present", () => {
    const config = resolveConfig(undefined);
    expect(config.apiBaseUrl).toBe("");
    expect(config.appName).toBe("Dorahub");
  });

  it("lets runtime config override compile-time values", () => {
    const config = resolveConfig({ apiBaseUrl: "https://api.staging.dorahub" });
    expect(config.apiBaseUrl).toBe("https://api.staging.dorahub");
  });

  it("ignores empty runtime values instead of blanking defaults", () => {
    const config = resolveConfig({ apiBaseUrl: "" });
    expect(config.apiBaseUrl).toBe("");
    expect(config.appName).toBe("Dorahub");
  });
});
