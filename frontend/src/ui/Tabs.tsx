import { useId, useState, type KeyboardEvent, type ReactNode } from "react";
import { nextTabIndex } from "./tabs-keys";

function cx(...parts: Array<string | false | undefined>): string {
  return parts.filter(Boolean).join(" ");
}

/**
 * Accessible tabs: roving tabindex, arrow/Home/End keys, aria wiring.
 * The active tab is marked with a sliding gold underline (layout.css
 * pattern shared with nav-link).
 */
export function Tabs({
  tabs,
  initial = 0,
  className,
}: {
  tabs: Array<{ id: string; label: ReactNode; panel: ReactNode }>;
  initial?: number;
  className?: string;
}) {
  const baseId = useId();
  const [active, setActive] = useState(initial);

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const next = nextTabIndex(e.key, active, tabs.length);
    if (next === null) return;
    e.preventDefault();
    setActive(next);
    document.getElementById(`${baseId}-tab-${next}`)?.focus();
  };

  return (
    <div className={cx("tabs", className)}>
      <div className="tabs__list" role="tablist" onKeyDown={onKeyDown}>
        {tabs.map((tab, i) => (
          <button
            key={tab.id}
            id={`${baseId}-tab-${i}`}
            type="button"
            role="tab"
            aria-selected={i === active}
            aria-controls={`${baseId}-panel-${i}`}
            tabIndex={i === active ? 0 : -1}
            className={cx("tabs__tab", i === active && "tabs__tab--active")}
            onClick={() => setActive(i)}
          >
            {tab.label}
          </button>
        ))}
      </div>
      {tabs.map((tab, i) => (
        <div
          key={tab.id}
          id={`${baseId}-panel-${i}`}
          role="tabpanel"
          aria-labelledby={`${baseId}-tab-${i}`}
          hidden={i !== active}
          className="tabs__panel"
        >
          {tab.panel}
        </div>
      ))}
    </div>
  );
}
