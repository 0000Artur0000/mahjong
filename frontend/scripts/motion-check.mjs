/**
 * RD-03 guard: the motion library (framer-motion / motion) must stay out of
 * the main chunk. Only lazy routes and the motion/ directory may import it.
 * Fails CI if a shared module pulls the library into the entry bundle.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const src = join(dirname(fileURLToPath(import.meta.url)), "..", "src");

/** Paths where importing motion/react is allowed (lazy-loaded code only). */
const ALLOWED = ["src/demo/", "src/showcase/", "src/motion/"];

const IMPORT_RE = /from\s+["'](?:motion(?:\/react)?|framer-motion)["']/;

function* walk(dir) {
  for (const entry of readdirSync(dir)) {
    const p = join(dir, entry);
    if (statSync(p).isDirectory()) yield* walk(p);
    else if (/\.(ts|tsx)$/.test(entry)) yield p;
  }
}

const violations = [];
for (const file of walk(src)) {
  const rel = relative(process.cwd(), file).replaceAll("\\", "/");
  if (ALLOWED.some((a) => rel.startsWith(a))) continue;
  const text = readFileSync(file, "utf8");
  // Importing through @/motion/motion is also forbidden outside allowed
  // paths: it would drag the library into the importer's chunk.
  if (IMPORT_RE.test(text) || /from\s+["']@\/motion\/motion["']/.test(text)) {
    violations.push(rel);
  }
}

if (violations.length > 0) {
  console.error(
    "motion library imported outside lazy paths (allowed: " +
      ALLOWED.join(", ") +
      "):",
  );
  for (const v of violations) console.error("  -", v);
  process.exit(1);
}
console.log(
  `Motion check passed: library confined to ${ALLOWED.join(", ")} (main chunk stays free).`,
);
