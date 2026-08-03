import type { ReactNode } from "react";
import type { LucideIcon } from "lucide-react";
import { Icon } from "./Icon";

/**
 * Friendly empty/zero state. Mascot slot is allowed here (Plan.md: mascot
 * lives in onboarding, learning, empty states and friendly errors).
 */
export function EmptyState({
  icon,
  title,
  hint,
  action,
}: {
  icon?: LucideIcon;
  title: string;
  hint?: string;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      {icon && (
        <span className="empty__icon" aria-hidden="true">
          <Icon icon={icon} size="lg" />
        </span>
      )}
      <h3 className="empty__title">{title}</h3>
      {hint && <p className="empty__hint">{hint}</p>}
      {action}
    </div>
  );
}
