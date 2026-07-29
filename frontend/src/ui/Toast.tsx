import { useCallback, useRef, useState, type ReactNode } from "react";
import {
  Check,
  Info,
  TriangleAlert,
  X,
  type LucideIcon,
} from "lucide-react";
import { ToastContext, type Tone } from "./toast-context";
import { Icon } from "./Icon";

type Toast = { id: number; title: string; tone: Tone };

const MARK: Record<Tone, LucideIcon> = {
  info: Info,
  positive: Check,
  danger: TriangleAlert,
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(0);

  const push = useCallback((t: { title: string; tone?: Tone }) => {
    const id = nextId.current++;
    setToasts((list) => [
      ...list,
      { id, title: t.title, tone: t.tone ?? "info" },
    ]);
    setTimeout(() => {
      setToasts((list) => list.filter((x) => x.id !== id));
    }, 5000);
  }, []);

  const dismiss = (id: number) =>
    setToasts((list) => list.filter((x) => x.id !== id));

  return (
    <ToastContext.Provider value={push}>
      {children}
      {/* Polite live region so new toasts are announced without stealing focus. */}
      <div className="toasts" role="region" aria-label="Уведомления">
        <ul className="toasts__list" aria-live="polite">
          {toasts.map((t) => (
            <li key={t.id} className={`toast toast--${t.tone}`}>
              <span className="toast__mark" aria-hidden="true">
                <Icon icon={MARK[t.tone]} size="sm" />
              </span>
              <span className="toast__title">{t.title}</span>
              <button
                type="button"
                className="toast__close"
                aria-label="Закрыть уведомление"
                onClick={() => dismiss(t.id)}
              >
                <Icon icon={X} size="sm" />
              </button>
            </li>
          ))}
        </ul>
      </div>
    </ToastContext.Provider>
  );
}
