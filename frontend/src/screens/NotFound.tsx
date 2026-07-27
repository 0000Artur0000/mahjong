import { Link } from "react-router";

export function NotFound() {
  return (
    <div className="screen screen--center">
      <p className="eyebrow">404</p>
      <h1 className="screen__title">Страница не найдена</h1>
      <p className="lede">Возможно, стол уже собран, а ссылка устарела.</p>
      <Link className="btn btn--primary" to="/">
        На главную
      </Link>
    </div>
  );
}
