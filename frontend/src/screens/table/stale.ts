/**
 * Партия без новых раздач дольше половины суток — брошенная.
 *
 * <p>Сервер её не закрывает: за столом бывает долгий перерыв, а внутри уже введённые
 * раздачи. Решает участник кнопкой, экран только напоминает.
 */
export const STALE_HOURS = 12;

/** Часы простоя стола; часовые пояса берёт на себя ISO-время с сервера. */
export function idleHours(updatedAt: string, now: number): number {
  const hours = Math.floor((now - Date.parse(updatedAt)) / 3_600_000);
  // Неразобранная дата и часы сервера, убежавшие вперёд, не должны показывать баннер.
  return Number.isFinite(hours) ? Math.max(0, hours) : 0;
}
