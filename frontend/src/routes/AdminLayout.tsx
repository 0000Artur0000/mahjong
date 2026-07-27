import { Link, NavLink, Outlet, useNavigate } from "react-router";
import { signOut } from "@/auth/session";
import { ThemeToggle } from "@/theme";
import { navClass } from "./nav";

// Desktop-first admin shell with a persistent sidebar (stacks on narrow screens).
export function AdminLayout() {
  const navigate = useNavigate();
  return (
    <div className="admin-shell">
      <aside className="admin-sidebar">
        <Link to="/admin" className="brand">
          <span className="brand__mark" aria-hidden="true">
            東
          </span>
          Admin
        </Link>
        <nav className="admin-nav" aria-label="Администрирование">
          <NavLink to="/admin" end className={navClass}>
            Судейская
          </NavLink>
          <NavLink to="/admin/moderation" className={navClass}>
            Модерация
          </NavLink>
        </nav>
        <div className="admin-sidebar__foot">
          <ThemeToggle />
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => {
              signOut();
              navigate("/");
            }}
          >
            Выйти
          </button>
        </div>
      </aside>
      <main className="admin-main">
        <Outlet />
      </main>
    </div>
  );
}
