import { useEffect, useRef, useState } from "react";
import { prefersReducedMotion } from "./viewTransitions";

/**
 * Score count-up with tabular numerals (scores must never reflow).
 * Animates from the previously rendered value to `value` over `duration`
 * with an ease-out-expo curve; reduced-motion renders the value instantly.
 */
export function CountUp({
  value,
  duration = 700,
  format = (n: number) => n.toLocaleString("ru-RU"),
  className = "tabular",
}: {
  value: number;
  duration?: number;
  format?: (n: number) => string;
  className?: string;
}) {
  const [shown, setShown] = useState(value);
  const previous = useRef(value);
  const frame = useRef(0);

  useEffect(() => {
    const from = previous.current;
    const to = value;
    previous.current = value;
    if (from === to || prefersReducedMotion()) {
      setShown(to);
      return;
    }
    const start = performance.now();
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / duration);
      const eased = t === 1 ? 1 : 1 - 2 ** (-10 * t);
      setShown(Math.round(from + (to - from) * eased));
      if (t < 1) frame.current = requestAnimationFrame(tick);
    };
    frame.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame.current);
  }, [value, duration]);

  return <span className={className}>{format(shown)}</span>;
}
