/**
 * RD-01: renders PWA/favicon PNG icons from the brand 東 tile (favicon.svg).
 *
 * Output: public/icons/*.png
 *   icon-192.png / icon-512.png  — tile on transparent canvas (any mask)
 *   maskable-512.png             — tile in the safe zone on felt + gold frame
 *   apple-touch-icon.png         — 180px, opaque felt (iOS rounds corners itself)
 * Usage:  npm run assets:icons   (after assets:tiles)
 */
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const out = (p) => join(root, p);

const tileSvg = await readFile(out("public/favicon.svg"));

/** Tile is 300×400; render it to a PNG of the given pixel height. */
async function tilePng(height) {
  const width = Math.round(height * 0.75);
  return sharp(tileSvg, { density: 384 })
    .resize({ width, height, fit: "fill" })
    .png()
    .toBuffer();
}

/** Felt backdrop with a thin gold inner frame (opaque). */
function backdropSvg(size, frame) {
  const inset = Math.round(size * 0.055);
  const stroke = Math.max(2, Math.round(size * 0.012));
  const rx = Math.round(size * 0.14);
  const frameRect = frame
    ? `<rect x="${inset}" y="${inset}" width="${size - 2 * inset}" height="${size - 2 * inset}" rx="${rx}" fill="none" stroke="#c99a3a" stroke-width="${stroke}" stroke-opacity=".8"/>`
    : "";
  return Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}">
      <rect width="${size}" height="${size}" fill="#090d18"/>
      <rect width="${size}" height="${size}" fill="#16203a" fill-opacity=".35"/>
      ${frameRect}
    </svg>`,
  );
}

async function composed({ size, tileHeight, backdrop }) {
  const layers = [];
  if (backdrop) layers.push({ input: backdropSvg(size, backdrop === "frame") });
  layers.push({ input: await tilePng(tileHeight), gravity: "center" });
  const background = backdrop
    ? { r: 9, g: 13, b: 24, alpha: 1 }
    : { r: 0, g: 0, b: 0, alpha: 0 };
  return sharp({
    create: { width: size, height: size, channels: 4, background },
  })
    .composite(layers)
    .png()
    .toBuffer();
}

await mkdir(out("public/icons"), { recursive: true });

const targets = [
  { file: "icon-192.png", size: 192, tile: 176, backdrop: null },
  { file: "icon-512.png", size: 512, tile: 470, backdrop: null },
  { file: "maskable-512.png", size: 512, tile: 320, backdrop: "frame" },
  { file: "apple-touch-icon.png", size: 180, tile: 128, backdrop: "solid" },
];

for (const t of targets) {
  const png = await composed({
    size: t.size,
    tileHeight: t.tile,
    backdrop: t.backdrop,
  });
  await writeFile(out(`public/icons/${t.file}`), png);
  console.log(`${t.file}  ${(png.length / 1024).toFixed(1)} KB`);
}
