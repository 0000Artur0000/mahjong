import { getMyProfile } from "@/api/profile";

/**
 * Кто вошёл.
 *
 * Источник истины — cookie сессии на сервере, а не отметка в браузере: браузер
 * может её потерять, устареть по формату или просто не знать, что сессию закрыли
 * с другого устройства. Поэтому состояние спрашивается у сервера один раз за
 * загрузку страницы и дальше берётся из памяти — загрузчики маршрутов ждут
 * ответа до рендера, поэтому экраны читают его уже готовым.
 */
export type Role = "player" | "moderator" | "admin";
export type Session = { role: Role; accountId: string } | null;

let pending: Promise<Session> | null = null;
let current: Session = null;

/** Спросить сервер, кто вошёл. Повторные вызовы за одну загрузку не ходят по сети. */
export function loadSession(): Promise<Session> {
  pending ??= getMyProfile().then((result) => {
    current = "data" in result ? { role: result.data.role, accountId: result.data.accountId } : null;
    return current;
  });
  return pending;
}

/** Кто вошёл, без запроса. Внутри защищённых маршрутов уже заполнено загрузчиком. */
export function currentSession(): Session {
  return current;
}

/** Перечитать после входа: роль и идентификатор берутся из свежего профиля. */
export function refreshSession(): Promise<Session> {
  pending = null;
  current = null;
  return loadSession();
}

/** Забыть вход на этом устройстве. Саму сессию закрывает сервер. */
export function forgetSession() {
  pending = null;
  current = null;
}
