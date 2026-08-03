package ru.dorahub.ratings.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.dorahub.ratings.LadderEntry;
import ru.dorahub.ratings.RatingChange;

/** Хранение лестницы: append-only, текущий рейтинг — последняя строка игрока. */
@Repository
@Profile("!test")
public class RatingRepository {

  private final JdbcTemplate jdbc;

  RatingRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Текущие рейтинги игроков в формате; отсутствующий в ответе игрок ещё не играл. */
  public Map<UUID, Integer> currentRatings(String format, List<UUID> accounts) {
    if (accounts.isEmpty()) {
      return Map.of();
    }
    String placeholders = accounts.stream().map(id -> "?").collect(Collectors.joining(", "));
    Object[] arguments =
        java.util.stream.Stream.concat(java.util.stream.Stream.of(format), accounts.stream())
            .toArray();
    return jdbc
        .query(
            """
            SELECT DISTINCT ON (account_id) account_id, rating_after
            FROM rating_change
            WHERE format = ? AND account_id IN (%s)
            ORDER BY account_id, seq DESC
            """
                .formatted(placeholders),
            (row, number) ->
                Map.entry(row.getObject("account_id", UUID.class), row.getInt("rating_after")),
            arguments)
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /**
   * Записать изменения партии.
   *
   * <p>Повтор той же партии молча пропускается: {@code UNIQUE (table_id, account_id)} делает запись
   * идемпотентной, а начисление рейтинга дважды за одну игру — нет.
   */
  public void insert(List<RatingChange> changes) {
    jdbc.batchUpdate(
        """
        INSERT INTO rating_change (table_id, account_id, format, place, delta, rating_after)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (table_id, account_id) WHERE NOT compensated DO NOTHING
        """,
        changes.stream()
            .map(
                change ->
                    new Object[] {
                      change.tableId(),
                      change.accountId(),
                      change.format(),
                      change.place(),
                      change.delta(),
                      change.ratingAfter()
                    })
            .toList());
  }

  /**
   * Снять рейтинг за партию встречным начислением.
   *
   * <p>Старые строки не правятся: тот, кто сыграл после, считался от рейтинга, который тогда был, и
   * переписывать его задним числом нельзя. Поэтому компенсация — это дельта на текущий рейтинг, а
   * не откат к прошлому значению.
   *
   * @return сколько записей отменено
   */
  public int revoke(UUID tableId) {
    int compensations =
        jdbc.update(
            """
            INSERT INTO rating_change (
                table_id, account_id, format, place, delta, rating_after, compensated)
            SELECT played.table_id,
                   played.account_id,
                   played.format,
                   played.place,
                   -played.delta,
                   latest.rating_after - played.delta,
                   true
            FROM rating_change played
            JOIN LATERAL (
                SELECT rating_after
                FROM rating_change current
                WHERE current.account_id = played.account_id
                  AND current.format = played.format
                ORDER BY current.seq DESC
                LIMIT 1
            ) latest ON true
            WHERE played.table_id = ? AND NOT played.compensated
            """,
            tableId);
    jdbc.update(
        "UPDATE rating_change SET compensated = true WHERE table_id = ? AND NOT compensated",
        tableId);
    return compensations;
  }

  /**
   * Лестница формата, сильные сверху.
   *
   * <p>Последняя строка игрока берётся через {@code array_agg}, а не {@code DISTINCT ON}: вместе с
   * оконной функцией для счётчика партий он выбирал произвольную строку игрока, и у сыгравшего две
   * партии в лестнице оказывался рейтинг после первой.
   */
  public List<LadderEntry> ladder(String format, int limit) {
    return jdbc.query(
        """
        SELECT account_id, rating, games
        FROM (
            SELECT account_id,
                   (array_agg(rating_after ORDER BY seq DESC))[1] AS rating,
                   count(*) FILTER (WHERE NOT compensated) AS games
            FROM rating_change
            WHERE format = ?
            GROUP BY account_id
            HAVING count(*) FILTER (WHERE NOT compensated) > 0
        ) ladder
        ORDER BY rating DESC, account_id
        LIMIT ?
        """,
        (row, number) ->
            new LadderEntry(
                row.getObject("account_id", UUID.class), row.getInt("rating"), row.getInt("games")),
        format,
        limit);
  }

  /**
   * Итог игрока по форматам.
   *
   * <p>Партии считаются только действующие, а рейтинг берётся по последней строке вообще:
   * отменённая партия из статистики исчезает, но её компенсация — это и есть текущее значение.
   */
  public List<FormatSummary> summary(UUID accountId) {
    return jdbc.query(
        """
        SELECT format,
               count(*) FILTER (WHERE NOT compensated) AS games,
               count(*) FILTER (WHERE NOT compensated AND place = 1) AS first_places,
               count(*) FILTER (WHERE NOT compensated AND place = 2) AS second_places,
               count(*) FILTER (WHERE NOT compensated AND place = 3) AS third_places,
               count(*) FILTER (WHERE NOT compensated AND place = 4) AS fourth_places,
               avg(place) FILTER (WHERE NOT compensated) AS average_place,
               (array_agg(rating_after ORDER BY seq DESC))[1] AS rating
        FROM rating_change
        WHERE account_id = ?
        GROUP BY format
        HAVING count(*) FILTER (WHERE NOT compensated) > 0
        ORDER BY games DESC, format
        """,
        (row, number) ->
            new FormatSummary(
                row.getString("format"),
                row.getInt("rating"),
                row.getInt("games"),
                List.of(
                    row.getInt("first_places"),
                    row.getInt("second_places"),
                    row.getInt("third_places"),
                    row.getInt("fourth_places")),
                row.getBigDecimal("average_place").doubleValue()),
        accountId);
  }

  /** Итог игрока в одном формате. */
  public record FormatSummary(
      String format, int rating, int games, List<Integer> places, double averagePlace) {}

  /** История игрока, свежее сверху. */
  public List<RatingChange> history(UUID accountId, int limit) {
    return jdbc.query(
        """
        SELECT table_id, account_id, format, place, delta, rating_after, created_at
        FROM rating_change
        WHERE account_id = ?
        ORDER BY seq DESC
        LIMIT ?
        """,
        (row, number) ->
            new RatingChange(
                row.getObject("table_id", UUID.class),
                row.getObject("account_id", UUID.class),
                row.getString("format"),
                row.getInt("place"),
                row.getInt("delta"),
                row.getInt("rating_after"),
                row.getObject("created_at", java.time.OffsetDateTime.class).toInstant()),
        accountId,
        limit);
  }
}
