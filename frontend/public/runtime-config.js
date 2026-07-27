// Runtime configuration, loaded before the app bundle. Overwrite this file per
// environment at container start (e.g. envsubst). Local-dev defaults live here.
// Public file — never put secrets in it.
window.__DORAHUB_CONFIG__ = {
  // API origin. Empty = same origin; OpenAPI paths already include /api/v1.
  apiBaseUrl: "",
};
