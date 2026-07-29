import type { LucideIcon } from "lucide-react";

const SIZES = { sm: 16, md: 20, lg: 24 } as const;

/**
 * Lucide icon with token sizes. Decorative by default (aria-hidden);
 * pass `label` for the rare standalone meaningful icon.
 */
export function Icon({
  icon: Glyph,
  size = "md",
  label,
}: {
  icon: LucideIcon;
  size?: keyof typeof SIZES;
  label?: string;
}) {
  return (
    <Glyph
      className="icon"
      size={SIZES[size]}
      strokeWidth={2}
      aria-hidden={label ? undefined : true}
      aria-label={label}
      role={label ? "img" : undefined}
    />
  );
}
