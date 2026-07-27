import { useId, useRef, useState, type ReactNode } from "react";
import { Button } from "./Button";
import { useDismiss } from "./hooks";

/**
 * Disclosure popover: a button toggles a labelled surface. Escape and
 * outside-pointer close it (useDismiss); focus returns to the trigger on close.
 */
export function Popover({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const surfaceId = useId();

  const close = () => {
    setOpen(false);
    trigger.current?.focus();
  };
  useDismiss(wrap, open, close);

  return (
    <div className="popover" ref={wrap}>
      <Button
        ref={trigger}
        aria-expanded={open}
        aria-controls={surfaceId}
        onClick={() => setOpen((v) => !v)}
      >
        {label}
      </Button>
      {open && (
        <div className="popover__surface" id={surfaceId} role="group">
          {children}
        </div>
      )}
    </div>
  );
}

type MenuItem = { label: string; onSelect: () => void; danger?: boolean };

/**
 * Command menu built as an accessible disclosure (Tab-navigable buttons in a
 * popover) rather than the heavyweight ARIA menu pattern. First item is focused
 * on open; selecting or Escape closes and returns focus to the trigger.
 */
export function Menu({ label, items }: { label: string; items: MenuItem[] }) {
  const [open, setOpen] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const surfaceId = useId();

  const close = () => {
    setOpen(false);
    trigger.current?.focus();
  };
  useDismiss(wrap, open, close);

  return (
    <div className="popover" ref={wrap}>
      <Button
        ref={trigger}
        aria-expanded={open}
        aria-controls={surfaceId}
        onClick={() => setOpen((v) => !v)}
      >
        {label}
      </Button>
      {open && (
        <div className="popover__surface menu" id={surfaceId}>
          {items.map((item, i) => (
            <button
              key={item.label}
              type="button"
              autoFocus={i === 0}
              className={
                item.danger ? "menu__item menu__item--danger" : "menu__item"
              }
              onClick={() => {
                item.onSelect();
                close();
              }}
            >
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
