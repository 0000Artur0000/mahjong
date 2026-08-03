function cx(...parts: Array<string | false | undefined>): string {
  return parts.filter(Boolean).join(" ");
}

function initialsOf(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join("");
}

/**
 * Player avatar with an initials fallback (no broken-image flash ever).
 * Status ring is decorative; identity always comes from adjacent text.
 */
export function Avatar({
  name,
  src,
  size = "md",
  className,
}: {
  name: string;
  src?: string;
  size?: "sm" | "md" | "lg";
  className?: string;
}) {
  return (
    <span className={cx("avatar", `avatar--${size}`, className)}>
      {src ? (
        <img className="avatar__img" src={src} alt="" loading="lazy" />
      ) : (
        <span className="avatar__initials" aria-hidden="true">
          {initialsOf(name)}
        </span>
      )}
      <span className="visually-hidden">{name}</span>
    </span>
  );
}
