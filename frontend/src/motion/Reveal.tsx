import {
  useEffect,
  useRef,
  type CSSProperties,
  type ReactNode,
} from "react";
import { prefersReducedMotion } from "./viewTransitions";

/**
 * Scroll-triggered entrance (CSS layer). Children start hidden and fade up
 * once intersecting. Content is never trapped hidden: without IO or with
 * reduced-motion the visible class is applied on mount.
 */
export function Reveal({
  children,
  className = "",
  stagger = 0,
  as: Tag = "div",
}: {
  children: ReactNode;
  className?: string;
  /** Stagger index; the delay is stagger × --stagger-step (motion.css). */
  stagger?: number;
  as?: "div" | "section" | "li" | "span" | "figure";
}) {
  const ref = useRef<HTMLElement | null>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (prefersReducedMotion() || !("IntersectionObserver" in window)) {
      el.classList.add("reveal--visible");
      return;
    }
    const io = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            entry.target.classList.add("reveal--visible");
            io.unobserve(entry.target);
          }
        }
      },
      { threshold: 0.15, rootMargin: "0px 0px -5% 0px" },
    );
    io.observe(el);
    return () => io.disconnect();
  }, []);

  const style = {
    "--stagger-i": stagger,
  } as CSSProperties;

  return (
    <Tag
      ref={(node: HTMLElement | null) => {
        ref.current = node;
      }}
      className={`reveal ${className}`}
      style={style}
    >
      {children}
    </Tag>
  );
}
