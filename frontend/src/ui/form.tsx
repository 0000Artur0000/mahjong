import { useId, type ComponentPropsWithoutRef, type ReactNode } from "react";

function cx(...parts: Array<string | false | undefined>): string {
  return parts.filter(Boolean).join(" ");
}

type FieldChildProps = {
  id: string;
  "aria-describedby": string | undefined;
  "aria-invalid": boolean | undefined;
};

/**
 * Wires a label, optional hint and error to a control via generated ids, so
 * every field gets aria-describedby / aria-invalid without per-screen wiring.
 * Errors carry an icon and text, never colour alone.
 */
export function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: string;
  error?: string;
  children: (props: FieldChildProps) => ReactNode;
}) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy =
    [hint && hintId, error && errorId].filter(Boolean).join(" ") || undefined;

  return (
    <div className={cx("field", error && "field--invalid")}>
      <label className="field__label" htmlFor={id}>
        {label}
      </label>
      {children({
        id,
        "aria-describedby": describedBy,
        "aria-invalid": error ? true : undefined,
      })}
      {hint && !error && (
        <p className="field__hint" id={hintId}>
          {hint}
        </p>
      )}
      {error && (
        <p className="field__error" id={errorId} role="alert">
          <span className="field__error-mark" aria-hidden="true">
            !
          </span>
          {error}
        </p>
      )}
    </div>
  );
}

export function Input({
  className,
  ...rest
}: ComponentPropsWithoutRef<"input">) {
  return <input className={cx("input", className)} {...rest} />;
}

export function Textarea({
  className,
  ...rest
}: ComponentPropsWithoutRef<"textarea">) {
  return <textarea className={cx("input", "textarea", className)} {...rest} />;
}

export function Select({
  className,
  ...rest
}: ComponentPropsWithoutRef<"select">) {
  return <select className={cx("input", "select", className)} {...rest} />;
}

type ChoiceProps = ComponentPropsWithoutRef<"input"> & { label: string };

export function Checkbox({ label, className, ...rest }: ChoiceProps) {
  return (
    <label className={cx("choice", className)}>
      <input type="checkbox" className="choice__control" {...rest} />
      <span>{label}</span>
    </label>
  );
}

export function Radio({ label, className, ...rest }: ChoiceProps) {
  return (
    <label className={cx("choice", className)}>
      <input type="radio" className="choice__control" {...rest} />
      <span>{label}</span>
    </label>
  );
}
