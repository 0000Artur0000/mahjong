import type { ComponentPropsWithRef } from "react";

function cx(...parts: Array<string | false | undefined>): string {
  return parts.filter(Boolean).join(" ");
}

export function Card({
  elevated = false,
  lift = false,
  className,
  ...rest
}: ComponentPropsWithRef<"div"> & {
  elevated?: boolean;
  /** Hover raise (desktop pointer), from effects.css. */
  lift?: boolean;
}) {
  return (
    <div
      className={cx(
        "card",
        elevated && "card--elevated",
        lift && "fx-lift",
        className,
      )}
      {...rest}
    />
  );
}

/**
 * Card with the gold foil gradient border; a lamp glow fades in on hover.
 * Decorative only — the content carries the meaning.
 */
export function GlowCard({
  className,
  children,
  ...rest
}: ComponentPropsWithRef<"div">) {
  return (
    <div className={cx("glow-card", className)} {...rest}>
      {children}
    </div>
  );
}
