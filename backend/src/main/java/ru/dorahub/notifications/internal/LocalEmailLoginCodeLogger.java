package ru.dorahub.notifications.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.dorahub.accounts.EmailLoginCodeIssued;

/**
 * Приёмник кода входа для локальной разработки: печатает код в лог вместо письма.
 *
 * <p>Без него локальный запуск непроходим: письма отправляет только {@link
 * EmailLoginCodeMailListener} под профилем {@code prod}, а в базе от кода остаётся лишь хеш — войти
 * в приложение невозможно.
 *
 * <p>Это печать секрета в лог, поэтому бин ограничен профилем {@code local} и в production
 * загрузиться не может: профили {@code local} и {@code prod} взаимоисключающие, а вне обоих
 * приёмника нет вовсе. SMTP локально не нужен.
 */
@Component
@Profile("local")
class LocalEmailLoginCodeLogger {

  private static final Logger LOG = LoggerFactory.getLogger(LocalEmailLoginCodeLogger.class);

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void log(EmailLoginCodeIssued event) {
    LOG.warn(
        "LOCAL DEV ONLY — код входа для {}: {} (действует до {})",
        event.email(),
        event.code(),
        event.expiresAt());
  }
}
