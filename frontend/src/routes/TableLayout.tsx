import { Link, Outlet, useParams } from "react-router";

// Fullscreen table: global navigation is intentionally hidden so nothing
// competes with the board. It is a normal route, so browser back/forward and
// history are preserved; "Выйти со стола" is an ordinary link.
export function TableLayout() {
  const { id } = useParams();
  return (
    <div className="table-shell">
      <header className="table-bar">
        <span className="table-bar__id tabular">Стол {id}</span>
        <Link to="/app" className="btn btn--ghost">
          Выйти со стола
        </Link>
      </header>
      <main className="table-main">
        <Outlet />
      </main>
    </div>
  );
}
