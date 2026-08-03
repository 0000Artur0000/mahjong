import { useId, useState, type ComponentPropsWithoutRef, type ReactNode } from "react";
import { CircleAlert } from "lucide-react";
import { Icon } from "./Icon";

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
            <Icon icon={CircleAlert} size="sm" />
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

/**
 * One-time-code entry styled as a row of mahjong tiles (RD-07 login uses it).
 * A visually hidden input does the real editing (paste, autofill, mobile
 * keyboard); the cells only render the value, so no caret math is needed.
 * The freshly filled cell pops with a spring animation.
 */
export function CodeInput({
  length = 6,
  value,
  onChange,
  className,
  onFocus,
  onBlur,
  ...rest
}: {
  length?: number;
  value: string;
  onChange: (value: string) => void;
} & Omit<ComponentPropsWithoutRef<"input">, "value" | "onChange" | "size">) {
  const inputId = useId();
  const [isFocused, setFocused] = useState(false);
  const chars = Array.from({ length }, (_, i) => value[i] ?? "");
  const active = Math.min(value.length, length - 1);

  return (
    <div className={cx("code-input", className)}>
      {chars.map((char, i) => (
        <label
          key={`${i}:${char}`}
          htmlFor={inputId}
          aria-hidden="true"
          className={cx(
            "code-input__cell",
            char && "code-input__cell--filled",
            isFocused && i === active && "code-input__cell--active",
          )}
        >
          {char}
        </label>
      ))}
      <input
        id={inputId}
        className="code-input__hidden"
        inputMode="numeric"
        autoComplete="one-time-code"
        value={value}
        onChange={(e) => {
          const next = e.target.value.replace(/\D/g, "").slice(0, length);
          onChange(next);
        }}
        onFocus={(e) => {
          setFocused(true);
          onFocus?.(e);
        }}
        onBlur={(e) => {
          setFocused(false);
          onBlur?.(e);
        }}
        {...rest}
      />
    </div>
  );
}
