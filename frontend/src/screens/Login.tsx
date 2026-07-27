import { useNavigate, useSearchParams } from "react-router";
import { signIn } from "@/auth/session";
import { Button } from "@/ui";

export function Login() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const next = params.get("next") || "/app";

  const enter = (role: "user" | "admin") => {
    signIn(role);
    navigate(next, { replace: true });
  };

  return (
    <div className="screen screen--narrow">
      <p className="eyebrow">Вход · демо</p>
      <h1 className="screen__title">Войти в Dorahub</h1>
      <p className="lede">
        Настоящий вход по email и соцсетям появится в FE-07. Пока — демо-сессия,
        чтобы пройти по защищённым маршрутам.
      </p>
      <div className="cluster">
        <Button variant="primary" onClick={() => enter("user")}>
          Войти как игрок
        </Button>
        <Button variant="secondary" onClick={() => enter("admin")}>
          Войти как судья
        </Button>
      </div>
      {next !== "/app" && (
        <p className="screen__hint">
          После входа вернём на <code>{next}</code>.
        </p>
      )}
    </div>
  );
}
