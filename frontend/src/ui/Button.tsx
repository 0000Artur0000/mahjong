import type { ComponentPropsWithRef } from "react";

type ButtonProps = ComponentPropsWithRef<"button"> & {
  variant?: "primary" | "secondary" | "ghost" | "danger";
  icon?: boolean;
};

function cx(...parts: Array<string | false | undefined>): string {
  return parts.filter(Boolean).join(" ");
}

export function Button({
  variant = "secondary",
  icon = false,
  type,
  className,
  ...rest
}: ButtonProps) {
  return (
    <button
      // Default to a non-submitting button; forms opt in with type="submit".
      type={type ?? "button"}
      className={cx("btn", `btn--${variant}`, icon && "btn--icon", className)}
      {...rest}
    />
  );
}

export function Link({ className, ...rest }: ComponentPropsWithRef<"a">) {
  return <a className={cx("link", className)} {...rest} />;
}
