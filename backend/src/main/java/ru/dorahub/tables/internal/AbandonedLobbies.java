package ru.dorahub.tables.internal;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.dorahub.system.BackgroundJob;
import ru.dorahub.tables.TableSessions;

/**
 * Уборка лобби, которые так и не начали партию.
 *
 * <p>Отдельный бин, а не метод сервиса: вызов через прокси даёт закрытию транзакцию, а
 * self-invocation внутри {@code TableSessions} её бы потерял — стол сохранился бы без своих
 * событий.
 */
@Component
@Profile("!test")
class AbandonedLobbies {

  private final TableSessions sessions;
  private final Duration lobbyTtl;

  AbandonedLobbies(
      TableSessions sessions, @Value("${dorahub.tables.lobby-ttl:PT24H}") Duration lobbyTtl) {
    this.sessions = sessions;
    this.lobbyTtl = lobbyTtl;
  }

  // ponytail: пачка на 100 столов за проход, чаще чем раз в 15 минут смотреть незачем.
  @Scheduled(
      initialDelayString = "${dorahub.tables.lobby-sweep:PT15M}",
      fixedDelayString = "${dorahub.tables.lobby-sweep:PT15M}")
  void sweep() {
    BackgroundJob.run(
        "tables.abandoned-lobbies", () -> sessions.closeAbandonedLobbies(lobbyTtl, 100));
  }
}
