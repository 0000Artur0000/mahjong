import { Link } from "react-router";

export function Home() {
  return (
    <div className="screen">
      <p className="eyebrow">Риичи-платформа</p>
      <h1 className="screen__title screen__title--hero">
        Риичи без лишней магии
      </h1>
      <p className="lede">
        Клубные столы, честный подсчёт очков по фото и рейтинг — в одном месте.
        Публичная часть, обучение и калькулятор появятся в FE-30.
      </p>
      <div className="cluster">
        <Link className="btn btn--primary" to="/login">
          Войти
        </Link>
        <Link className="btn btn--secondary" to="/styleguide">
          Дизайн-система
        </Link>
      </div>
    </div>
  );
}
