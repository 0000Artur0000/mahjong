import type { ComponentPropsWithRef } from "react";
import { Magnetic } from "@/motion/Magnetic";

type ButtonProps = ComponentPropsWithRef<"button"> & {
  variant?: "primary" | "secondary" | "ghost" | "danger";
  size?: "sm" | "md" | "lg";
  icon?: boolean;
  /** Shows a spinner and blocks interaction while work is in flight. */
  loading?: boolean;
  /** Desktop-only pointer pull (no-op on touch / reduced-motion). */
  magnetic?: boolean;
};

function cx(...parts: Array<string | false | undefined>): string {
  return parts.filter(Boolean).join(" ");
}

export function Button({
  variant = "secondary",
  size = "md",
  icon = false,
  loading = false,
  magnetic = false,
  type,
  className,
  disabled,
  children,
  ...rest
}: ButtonProps) {
  const button = (
    <button
      // Default to a non-submitting button; forms opt in with type="submit".
      type={type ?? "button"}
      className={cx(
        "btn",
        `btn--${variant}`,
        size !== "md" && `btn--${size}`,
        icon && "btn--icon",
        loading && "btn--loading",
        className,
      )}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading && <span className="btn__spinner" aria-hidden="true" />}
      {children}
    </button>
  );
  return magnetic ? <Magnetic>{button}</Magnetic> : button;
}

export function Link({ className, ...rest }: ComponentPropsWithRef<"a">) {
  return <a className={cx("link", className)} {...rest} />;
}
