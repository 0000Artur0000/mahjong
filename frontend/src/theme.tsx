import { useEffect, useState } from "react";
import { Moon, Sun } from "lucide-react";
import { Icon } from "@/ui";
import { themeWipe } from "@/motion/viewTransitions";

type Theme = "dark" | "light";

// Global theme toggle. The stored value is applied pre-paint by the inline
// script in index.html, so this only keeps it in sync after the app mounts.
export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(() => {
    const stored = localStorage.getItem("dorahub-theme");
    if (stored === "dark" || stored === "light") return stored;
    return window.matchMedia("(prefers-color-scheme: light)").matches
      ? "light"
      : "dark";
  });
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("dorahub-theme", theme);
  }, [theme]);

  return (
    <button
      type="button"
      className="theme-toggle"
      onClick={(e) => {
        // The whole page repaints as a circular wipe from the click point
        // (View Transitions API; unsupported/reduced-motion = instant swap).
        themeWipe(e.clientX, e.clientY, () =>
          setTheme((t) => (t === "dark" ? "light" : "dark")),
        );
      }}
      aria-label={`Тема: ${theme === "dark" ? "тёмная" : "светлая"}. Переключить`}
    >
      <Icon icon={theme === "dark" ? Moon : Sun} size="sm" />
      {theme === "dark" ? "Тёмная" : "Светлая"}
    </button>
  );
}
