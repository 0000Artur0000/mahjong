import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { requestEmailCode, verifyEmailCode } from "@/api/auth";
import type { UiError } from "@/api/errors";
import { refreshSession } from "@/auth/session";
import { Button, CodeInput, Field, Input } from "@/ui";

const CODE_LENGTH = 6;

/**
 * Вход по одноразовому коду на email.
 *
 * Сервер отвечает на запрос кода одинаково независимо от того, есть ли такой
 * аккаунт, поэтому экран тоже не подсказывает — просто переходит к вводу кода.
 * Сессия живёт в cookie, поэтому после проверки кода остаётся перечитать профиль:
 * из него маршруты узнают роль, а экран стола — за каким местом ты сидишь.
 */
export function Login() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const next = params.get("next") || "/app";

  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [stage, setStage] = useState<"email" | "code">("email");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<UiError | null>(null);

  const emailValid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());

  const sendCode = async () => {
    setBusy(true);
    const result = await requestEmailCode(email.trim());
    setBusy(false);
    if ("error" in result) {
      setError(result.error);
      return;
    }
    setError(null);
    setCode("");
    setStage("code");
  };

  const verify = async (value: string) => {
    setBusy(true);
    const result = await verifyEmailCode(email.trim(), value);
    setBusy(false);
    if ("error" in result) {
      setError(result.error);
      return;
    }
    // Сессия уже в cookie; перечитываем профиль, чтобы маршруты и экраны знали
    // роль и идентификатор без второго входа.
    await refreshSession();
    navigate(next, { replace: true });
  };

  return (
    <div className="screen screen--narrow">
      <p className="eyebrow">Вход</p>
      <h1 className="screen__title">Войти в Dorahub</h1>

      {stage === "email" ? (
        <>
          <p className="lede">
            Пришлём одноразовый код на почту. Пароля нет — и не будет.
          </p>
          <Field label="Email">
            {(field) => (
              <Input
                {...field}
                type="email"
                inputMode="email"
                autoComplete="email"
                placeholder="you@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && emailValid && !busy) void sendCode();
                }}
              />
            )}
          </Field>
          <Button
            disabled={!emailValid || busy}
            loading={busy}
            onClick={() => void sendCode()}
          >
            Получить код
          </Button>
        </>
      ) : (
        <>
          <p className="lede">
            Код из шести цифр отправлен на <strong>{email.trim()}</strong>.
          </p>
          <CodeInput
            value={code}
            onChange={(value) => {
              setCode(value);
              setError(null);
              if (value.length === CODE_LENGTH && !busy) void verify(value);
            }}
          />
          <div className="cluster">
            <Button
              disabled={code.length !== CODE_LENGTH || busy}
              loading={busy}
              onClick={() => void verify(code)}
            >
              Войти
            </Button>
            <Button
              variant="ghost"
              disabled={busy}
              onClick={() => {
                setStage("email");
                setError(null);
              }}
            >
              Другой email
            </Button>
          </div>
        </>
      )}

      {error && (
        <p className="screen__hint" role="alert">
          {error.detail ?? error.title}
        </p>
      )}

      {next !== "/app" && (
        <p className="screen__hint">
          После входа вернём на <code>{next}</code>.
        </p>
      )}
    </div>
  );
}
