// Bundle report + budget gate. Runs as `postbuild`, so `npm run build` fails
// loudly when the shipped JS grows past budget instead of drifting silently.
import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { gzipSync } from "node:zlib";

const ASSETS_DIR = "dist/assets";
// ponytail: single global JS budget; split per-chunk budgets only once route-level
// code splitting (FE-04) makes a single number misleading.
const JS_GZIP_BUDGET_KB = 200;

const names = await readdir(ASSETS_DIR).catch(() => null);
if (!names) {
  console.error(`No ${ASSETS_DIR} — run \`vite build\` first.`);
  process.exit(1);
}

const assets = names.filter((n) => /\.(js|css)$/.test(n));
let jsGzipTotal = 0;
const rows = [];
for (const name of assets) {
  const buf = await readFile(join(ASSETS_DIR, name));
  const gzip = gzipSync(buf).length;
  if (name.endsWith(".js")) jsGzipTotal += gzip;
  rows.push({ name, raw: buf.length, gzip });
}

const kb = (n) => `${(n / 1024).toFixed(1)} KB`.padStart(9);
rows.sort((a, b) => b.gzip - a.gzip);
console.log("asset".padEnd(34), "raw".padStart(9), "gzip".padStart(9));
for (const r of rows) console.log(r.name.padEnd(34), kb(r.raw), kb(r.gzip));
console.log("-".repeat(54));
console.log(
  `JS gzip total: ${(jsGzipTotal / 1024).toFixed(1)} KB (budget ${JS_GZIP_BUDGET_KB} KB)`,
);

if (jsGzipTotal / 1024 > JS_GZIP_BUDGET_KB) {
  console.error(
    `\nBundle budget exceeded: JS gzip ${(jsGzipTotal / 1024).toFixed(1)} KB > ${JS_GZIP_BUDGET_KB} KB. ` +
      `Trim imports or raise the budget deliberately in scripts/bundle-report.mjs.`,
  );
  process.exit(1);
}
