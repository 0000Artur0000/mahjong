import { Link, NavLink, Outlet } from "react-router";
import { getSession } from "@/auth/session";
import { ThemeToggle } from "@/theme";
import { navClass } from "./nav";

export function PublicLayout() {
  const session = getSession();
  return (
    <div className="layout">
      <header className="site-header">
        <Link to="/" className="brand">
          <span className="brand__mark" aria-hidden="true">
            東
          </span>
          Dorahub
        </Link>
        <nav className="site-nav" aria-label="Основная навигация">
          <NavLink to="/" end className={navClass}>
            Главная
          </NavLink>
          <NavLink to="/rules" className={navClass}>
            Правила
          </NavLink>
          <NavLink to="/clubs" className={navClass}>
            Клубы
          </NavLink>
          <NavLink to="/leaderboard" className={navClass}>
            Рейтинг
          </NavLink>
          <NavLink to="/styleguide" className={navClass}>
            UI
          </NavLink>
        </nav>
        <div className="site-header__actions">
          <ThemeToggle />
          <Link to={session ? "/app" : "/login"} className="btn btn--primary">
            {session ? "Кабинет" : "Войти"}
          </Link>
        </div>
      </header>
      <main className="site-main">
        <Outlet />
      </main>
      <footer className="site-footer">
        Dorahub · демо маршрутного каркаса
      </footer>
    </div>
  );
}
