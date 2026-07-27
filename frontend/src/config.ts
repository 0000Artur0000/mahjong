// Two config sources, kept separate on purpose:
//
//  - Compile-time: baked into the bundle by Vite from `import.meta.env` at build.
//  - Runtime: read from `window.__DORAHUB_CONFIG__`, set by /runtime-config.js which
//    the container can template per environment at start — so one built image serves
//    dev, staging and prod without a rebuild.
//
// Never put secrets in either: both end up in the browser. Runtime values win when set.

export type RuntimeConfig = {
  apiBaseUrl: string;
};

export type AppConfig = RuntimeConfig & {
  appName: string;
  version: string;
  mode: string;
};

declare global {
  interface Window {
    __DORAHUB_CONFIG__?: Partial<RuntimeConfig>;
  }
}

const compileTime: AppConfig = {
  appName: "Dorahub",
  version: import.meta.env.VITE_APP_VERSION ?? "0.1.0",
  mode: import.meta.env.MODE,
  // API origin. Empty = same origin; OpenAPI paths already include /api/v1.
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? "",
};

export function resolveConfig(
  runtime: Partial<RuntimeConfig> | undefined = typeof window === "undefined"
    ? undefined
    : window.__DORAHUB_CONFIG__,
): AppConfig {
  const provided = Object.fromEntries(
    Object.entries(runtime ?? {}).filter(([, v]) => v != null && v !== ""),
  );
  return { ...compileTime, ...provided };
}

export const config = resolveConfig();
