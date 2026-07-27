import { Link } from "react-router";

export function Forbidden() {
  return (
    <div className="screen screen--center">
      <p className="eyebrow">403</p>
      <h1 className="screen__title">Доступ закрыт</h1>
      <p className="lede">
        Этот раздел доступен только судьям и администраторам.
      </p>
      <Link className="btn btn--secondary" to="/app">
        В кабинет
      </Link>
    </div>
  );
}
