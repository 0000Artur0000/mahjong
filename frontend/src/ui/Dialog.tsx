import { useEffect, useId, useRef, type ReactNode } from "react";
import { X } from "lucide-react";
import { Button } from "./Button";
import { Icon } from "./Icon";

/**
 * Thin wrapper over the native <dialog>: focus trap, Escape and inert backdrop
 * come from the platform, so we only sync open state and label the dialog.
 */
export function Dialog({
  open,
  onClose,
  title,
  children,
  footer,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  footer?: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    else if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      className="dialog"
      aria-labelledby={titleId}
      onCancel={onClose}
      onClose={onClose}
      // Close when the backdrop (the dialog element itself) is clicked.
      onClick={(e) => {
        if (e.target === ref.current) onClose();
      }}
    >
      <div className="dialog__panel">
        <div className="dialog__head">
          <h2 className="dialog__title" id={titleId}>
            {title}
          </h2>
          <Button variant="ghost" icon aria-label="Закрыть" onClick={onClose}>
            <Icon icon={X} size="sm" />
          </Button>
        </div>
        <div className="dialog__body">{children}</div>
        {footer && <div className="dialog__foot">{footer}</div>}
      </div>
    </dialog>
  );
}
