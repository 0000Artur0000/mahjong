import { Link, NavLink, Outlet, useNavigate } from "react-router";
import { logout } from "@/api/auth";
import { forgetSession } from "@/auth/session";
import { ThemeToggle } from "@/theme";
import { navClass } from "./nav";

export function AppLayout() {
  const navigate = useNavigate();
  // Сессия живёт на сервере, поэтому выход — это запрос, а не забывание.
  const leave = async () => {
    await logout();
    forgetSession();
    navigate("/");
  };

  return (
    <div className="layout">
      <header className="site-header">
        <Link to="/app" className="brand">
          <span className="brand__mark" aria-hidden="true">
            東
          </span>
          Dorahub
        </Link>
        <nav className="site-nav" aria-label="Кабинет">
          <NavLink to="/app" end className={navClass}>
            Обзор
          </NavLink>
          <NavLink to="/app/tables" className={navClass}>
            Столы
          </NavLink>
          <NavLink to="/app/profile" className={navClass}>
            Профиль
          </NavLink>
        </nav>
        <div className="site-header__actions">
          <ThemeToggle />
          <button type="button" className="btn btn--ghost" onClick={() => void leave()}>
            Выйти
          </button>
        </div>
      </header>
      <main className="site-main">
        <Outlet />
      </main>
    </div>
  );
}
