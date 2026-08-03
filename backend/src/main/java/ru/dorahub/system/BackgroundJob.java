package ru.dorahub.system;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Контекст фоновой задачи в логах.
 *
 * <p>У запроса correlation id ставит фильтр, у задачи по расписанию ставить некому: строки «стол
 * закрыт» появлялись сами по себе, без признака, кто и в каком прогоне их написал. Идентификатор
 * здесь генерируется, потому что снаружи задачу никто не звал.
 */
public final class BackgroundJob {

  private static final Logger LOG = LoggerFactory.getLogger(BackgroundJob.class);

  private BackgroundJob() {}

  /** Выполнить задачу, пометив её логи именем и идентификатором прогона. */
  public static void run(String job, Runnable task) {
    var previous = MDC.getCopyOfContextMap();
    MDC.put("correlationId", UUID.randomUUID().toString());
    MDC.put("actor", "system");
    MDC.put("source", "scheduler");
    MDC.put("job", job);
    long startNanos = System.nanoTime();
    try {
      task.run();
      LOG.atInfo()
          .addKeyValue("event.name", "job.run")
          .addKeyValue("duration.ms", (System.nanoTime() - startNanos) / 1_000_000)
          .log("Background job finished");
    } catch (RuntimeException failure) {
      // Имя класса, а не сообщение: в сообщение попадают данные, а логи читают все.
      LOG.atError()
          .addKeyValue("event.name", "job.failed")
          .addKeyValue("error.type", failure.getClass().getName())
          .log("Background job failed");
      throw failure;
    } finally {
      MDC.clear();
      if (previous != null) {
        MDC.setContextMap(previous);
      }
    }
  }
}
