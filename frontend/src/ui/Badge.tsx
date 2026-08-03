import type { ComponentPropsWithRef } from "react";

function cx(...parts: Array<string | false | undefined>): string {
  return parts.filter(Boolean).join(" ");
}

/**
 * Compact status pill: leagues, provisional marks, evidence sources.
 * Tones are semantic — never colour alone, always pair with text.
 */
export function Badge({
  tone = "neutral",
  className,
  ...rest
}: ComponentPropsWithRef<"span"> & {
  tone?: "accent" | "positive" | "danger" | "warning" | "neutral";
}) {
  return <span className={cx("badge", `badge--${tone}`, className)} {...rest} />;
}
