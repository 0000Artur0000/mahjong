import { isRouteErrorResponse, Link, useRouteError } from "react-router";
import { Forbidden } from "@/screens/Forbidden";
import { NotFound } from "@/screens/NotFound";

// errorElement for every layout: turns thrown 403/404 Responses into their
// screens and shows a safe fallback for unexpected render/loader errors.
export function RouteError() {
  const error = useRouteError();
  if (isRouteErrorResponse(error)) {
    if (error.status === 403) return <Forbidden />;
    if (error.status === 404) return <NotFound />;
  }
  return (
    <div className="screen screen--center">
      <p className="eyebrow">Ошибка</p>
      <h1 className="screen__title">Что-то пошло не так</h1>
      <p className="lede">
        Экран не загрузился. Обновите страницу или вернитесь позже.
      </p>
      <Link className="btn btn--secondary" to="/">
        На главную
      </Link>
    </div>
  );
}
