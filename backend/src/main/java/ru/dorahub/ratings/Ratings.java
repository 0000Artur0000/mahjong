package ru.dorahub.ratings;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.dorahub.ratings.internal.Elo;
import ru.dorahub.ratings.internal.RatingRepository;

/**
 * Лестница игроков.
 *
 * <p>Модуль ничего не знает про столы и раздачи: на вход приходят места, на выход — дельты.
 * Решение, какая партия идёт в зачёт, принимает тот, кто её ведёт, — рейтингу остаётся посчитать.
 *
 * <p>Форматы считаются раздельно: ханчан и тонпуусен — разные игры, общий рейтинг смешивал бы
 * несравнимое.
 */
@Service
@Profile("!test")
public class Ratings {

  private final RatingRepository ratings;
  private final Clock clock;

  Ratings(RatingRepository ratings, Clock clock) {
    this.ratings = ratings;
    this.clock = clock;
  }

  /**
   * Записать итог партии в лестницу.
   *
   * <p>Повторный вызов с тем же столом ничего не меняет: запись идемпотентна по столу и игроку.
   *
   * @param format ключ лестницы, например {@code hanchan}
   * @param places места игроков; сумма дельт по партии равна нулю
   */
  @Transactional
  public List<RatingChange> record(UUID tableId, String format, List<RatedPlace> places) {
    Map<UUID, Integer> current =
        ratings.currentRatings(format, places.stream().map(RatedPlace::accountId).toList());
    List<Integer> before =
        places.stream().map(place -> current.getOrDefault(place.accountId(), Elo.START)).toList();
    List<Integer> deltas = Elo.deltas(before, places.stream().map(RatedPlace::place).toList());

    List<RatingChange> changes = new ArrayList<>(places.size());
    for (int player = 0; player < places.size(); player++) {
      changes.add(
          new RatingChange(
              tableId,
              places.get(player).accountId(),
              format,
              places.get(player).place(),
              deltas.get(player),
              before.get(player) + deltas.get(player),
              clock.instant()));
    }
    ratings.insert(changes);
    return List.copyOf(changes);
  }

  /**
   * Отменить начисление за партию: результат оказался неверным.
   *
   * <p>Не удаление, а встречное начисление — история лестницы остаётся целой, а сумма по партии как
   * была нулевой, так и останется. После отмены партию можно доиграть заново, и это будет законный
   * второй результат.
   *
   * @return сколько записей отменено; ноль, если партия и не считалась
   */
  @Transactional
  public int revoke(UUID tableId) {
    return ratings.revoke(tableId);
  }

  /** Лестница формата, сильные сверху. */
  @Transactional(readOnly = true)
  public List<LadderEntry> ladder(String format, int limit) {
    return ratings.ladder(format, Math.clamp(limit, 1, 100));
  }

  /** Сколько партий, какие места и какой рейтинг — по форматам. */
  @Transactional(readOnly = true)
  public List<RatingRepository.FormatSummary> summary(UUID accountId) {
    return ratings.summary(accountId);
  }

  /** Последние изменения рейтинга игрока, свежее сверху. */
  @Transactional(readOnly = true)
  public List<RatingChange> history(UUID accountId, int limit) {
    return ratings.history(accountId, Math.clamp(limit, 1, 100));
  }
}
