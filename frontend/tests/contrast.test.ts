import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

// Parse tokens.css into per-theme maps and enforce WCAG AA on every semantic
// text/surface pair, so a palette tweak that quietly breaks contrast fails CI.

const css = readFileSync(
  fileURLToPath(new URL("../src/styles/tokens.css", import.meta.url)),
  "utf8",
);

function declsOf(
  selectorTest: (sel: string) => boolean,
): Record<string, string> {
  const out: Record<string, string> = {};
  const block = /([^{}]+)\{([^{}]+)\}/g;
  for (const [, sel, body] of css.matchAll(block)) {
    if (!selectorTest(sel)) continue;
    for (const [, name, value] of body.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
      out[name] = value.trim();
    }
  }
  return out;
}

const base = declsOf(
  (s) => s.includes(":root") && !s.includes("light") && !s.includes(":not("),
);
const light = {
  ...base,
  ...declsOf((s) => s.includes('[data-theme="light"]')),
};
const themes = { dark: base, light } as const;

function resolve(
  value: string,
  map: Record<string, string>,
  depth = 0,
): string {
  const m = value.match(/^var\(\s*(--[\w-]+)/);
  if (!m) return value;
  if (depth > 8) throw new Error(`var() cycle at ${value}`);
  return resolve(map[m[1]] ?? "", map, depth + 1);
}

function rgb(hex: string): [number, number, number] {
  const h = hex.trim().replace("#", "");
  const n = parseInt(h, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function luminance(hex: string): number {
  const chan = (c: number) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  const [r, g, b] = rgb(hex);
  return 0.2126 * chan(r) + 0.7152 * chan(g) + 0.0722 * chan(b);
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

// [foreground token, background token, min ratio]
const TEXT = 4.5;
const UI = 3; // WCAG 1.4.11 non-text + large text
const PAIRS: Array<[string, string, number]> = [
  ["--color-fg", "--color-bg", TEXT],
  ["--color-fg", "--color-surface", TEXT],
  ["--color-fg", "--color-surface-raised", TEXT],
  ["--color-fg-muted", "--color-bg", TEXT],
  ["--color-fg-muted", "--color-surface", TEXT],
  ["--color-accent-ink", "--color-bg", TEXT],
  ["--color-accent-ink", "--color-surface", TEXT],
  ["--color-positive-ink", "--color-bg", TEXT],
  ["--color-danger-ink", "--color-bg", TEXT],
  ["--color-warning-ink", "--color-bg", TEXT],
  ["--color-on-accent", "--color-accent", TEXT],
  ["--color-on-positive", "--color-positive", TEXT],
  ["--color-on-danger", "--color-danger", TEXT],
  ["--color-on-warning", "--color-warning", TEXT],
  ["--color-fg-subtle", "--color-bg", UI],
  ["--color-border-strong", "--color-bg", UI],
  ["--color-focus", "--color-bg", UI],
  ["--color-accent", "--color-bg", UI],
  // Noir Gold effects (RD-02): text on the felt table, felt as text accent.
  ["--color-on-felt", "--color-felt", TEXT],
  ["--color-on-felt", "--color-felt-raised", TEXT],
  ["--color-felt-ink", "--color-bg", TEXT],
];

describe("WCAG AA contrast", () => {
  for (const [themeName, map] of Object.entries(themes)) {
    describe(themeName, () => {
      for (const [fg, bg, min] of PAIRS) {
        it(`${fg} on ${bg} >= ${min}:1`, () => {
          const ratio = contrast(
            resolve(`var(${fg})`, map),
            resolve(`var(${bg})`, map),
          );
          expect(
            ratio,
            `${fg} on ${bg} = ${ratio.toFixed(2)}:1 (need ${min}:1) in ${themeName}`,
          ).toBeGreaterThanOrEqual(min);
        });
      }
    });
  }
});
