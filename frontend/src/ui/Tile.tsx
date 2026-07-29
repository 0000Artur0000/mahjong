import type { ComponentPropsWithRef } from "react";
import { TILE_LABELS, type TileId } from "./tiles";

/**
 * A mahjong tile face from the Noir Gold sprite (public/tiles.svg).
 * The sprite is a static cached asset referenced via <use>, so repeated
 * tiles cost almost nothing. Always labelled for screen readers.
 */
export function Tile({
  tile,
  width = 44,
  ...rest
}: {
  tile: TileId;
  width?: number;
} & Omit<ComponentPropsWithRef<"svg">, "width" | "height" | "children">) {
  return (
    <svg
      className="tile-face"
      width={width}
      height={Math.round((width * 4) / 3)}
      viewBox="0 0 300 400"
      role="img"
      aria-label={TILE_LABELS[tile]}
      {...rest}
    >
      <use href={`/tiles.svg#tile-${tile}`} />
    </svg>
  );
}
