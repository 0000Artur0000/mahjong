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

type Toast = { id: number; title: string; tone: Tone; leaving?: boolean };

const MARK: Record<Tone, LucideIcon> = {
  info: Info,
  positive: Check,
  danger: TriangleAlert,
};

/* Keep in sync with toast-out duration in ui.css. */
const EXIT_MS = 180;
const LIFE_MS = 5000;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(0);

  const dismiss = useCallback((id: number) => {
    // Exit animation first, actual removal after it finishes.
    setToasts((list) =>
      list.map((x) => (x.id === id ? { ...x, leaving: true } : x)),
    );
    setTimeout(() => {
      setToasts((list) => list.filter((x) => x.id !== id));
    }, EXIT_MS);
  }, []);

  const push = useCallback(
    (t: { title: string; tone?: Tone }) => {
      const id = nextId.current++;
      setToasts((list) => [
        ...list,
        { id, title: t.title, tone: t.tone ?? "info" },
      ]);
      setTimeout(() => dismiss(id), LIFE_MS - EXIT_MS);
    },
    [dismiss],
  );

  return (
    <ToastContext.Provider value={push}>
      {children}
      {/* Polite live region so new toasts are announced without stealing focus. */}
      <div className="toasts" role="region" aria-label="Уведомления">
        <ul className="toasts__list" aria-live="polite">
          {toasts.map((t) => (
            <li
              key={t.id}
              className={`toast toast--${t.tone}${t.leaving ? " toast--leaving" : ""}`}
            >
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
