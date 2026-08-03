import { api } from "./client";
import { toUiError } from "./errors";
import type { Result } from "./system";

/**
 * Запрос одноразового кода. Сервер отвечает 202 и не сообщает, существует ли
 * аккаунт, поэтому ответ пустой — и на экране одинаковый в любом случае.
 */
export async function requestEmailCode(email: string): Promise<Result<null>> {
  try {
    const { error, response } = await api.POST("/api/v1/auth/email/code", {
      body: { email },
    });
    if (response.ok) return { data: null };
    return { error: toUiError(error, response) };
  } catch (cause) {
    return { error: toUiError(cause) };
  }
}

/** Проверка кода. Успех создаёт сессию в cookie и возвращает id аккаунта. */
export async function verifyEmailCode(
  email: string,
  code: string,
): Promise<Result<string>> {
  try {
    const { data, error, response } = await api.POST(
      "/api/v1/auth/email/verify",
      { body: { email, code } },
    );
    if (data) return { data: data.accountId };
    return { error: toUiError(error, response) };
  } catch (cause) {
    return { error: toUiError(cause) };
  }
}

export async function logout(): Promise<Result<null>> {
  try {
    const { error, response } = await api.POST("/api/v1/auth/logout", {});
    if (response.ok) return { data: null };
    return { error: toUiError(error, response) };
  } catch (cause) {
    return { error: toUiError(cause) };
  }
}
