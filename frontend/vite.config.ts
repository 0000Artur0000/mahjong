import { fileURLToPath, URL } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Compile-time config lives in `import.meta.env` (baked into the bundle at build).
// Runtime config lives in /public/runtime-config.js (see src/config.ts) so one built
// image can be repointed per environment without a rebuild.
export default defineConfig({
  plugins: [react()],
  resolve: {
    // Alias only the stable top-level source root, not individual feature folders.
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: { host: "0.0.0.0", port: 5173 },
  preview: { host: "0.0.0.0", port: 4173 },
  build: {
    target: "es2022",
    // No source maps or secrets in the production build unless explicitly enabled.
    sourcemap: process.env.SOURCEMAP === "true",
    chunkSizeWarningLimit: 600,
  },
});
