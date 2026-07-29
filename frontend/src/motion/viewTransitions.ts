/**
 * Motion layer 2: progressive-enhancement helpers for the View Transitions
 * API. When the API or the user's motion preference is missing, the update
 * simply runs synchronously — no feature detection leaks into callers.
 */

type ViewTransitionDoc = Document & {
  startViewTransition?: (update: () => void) => {
    ready: Promise<void>;
    finished: Promise<void>;
  };
};

export function prefersReducedMotion(): boolean {
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

export function withViewTransition(update: () => void): void {
  const doc = document as ViewTransitionDoc;
  if (!doc.startViewTransition || prefersReducedMotion()) {
    update();
    return;
  }
  doc.startViewTransition(update);
}

/**
 * Theme switch as a circular wipe growing from the toggle click point.
 * Falls back to the plain crossfade (or instant swap) automatically.
 */
export function themeWipe(x: number, y: number, apply: () => void): void {
  const doc = document as ViewTransitionDoc;
  if (!doc.startViewTransition || prefersReducedMotion()) {
    apply();
    return;
  }
  const vt = doc.startViewTransition(apply);
  const radius = Math.hypot(
    Math.max(x, window.innerWidth - x),
    Math.max(y, window.innerHeight - y),
  );
  vt.ready
    .then(() => {
      document.documentElement.animate(
        {
          clipPath: [
            `circle(0px at ${x}px ${y}px)`,
            `circle(${radius}px at ${x}px ${y}px)`,
          ],
        },
        {
          duration: 500,
          easing: "cubic-bezier(0.16, 1, 0.3, 1)",
          pseudoElement: "::view-transition-new(root)",
        },
      );
    })
    .catch(() => {
      /* transition skipped — the theme is already applied */
    });
}
