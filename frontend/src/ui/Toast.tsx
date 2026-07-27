import { useCallback, useRef, useState, type ReactNode } from "react";
import { ToastContext, type Tone } from "./toast-context";

type Toast = { id: number; title: string; tone: Tone };

const MARK: Record<Tone, string> = { info: "·", positive: "✓", danger: "!" };

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
                {MARK[t.tone]}
              </span>
              <span className="toast__title">{t.title}</span>
              <button
                type="button"
                className="toast__close"
                aria-label="Закрыть уведомление"
                onClick={() => dismiss(t.id)}
              >
                <span aria-hidden="true">✕</span>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </ToastContext.Provider>
  );
}
