package ru.dorahub.tables.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.dorahub.rules.RulesetSnapshot;
import ru.dorahub.tables.Table;
import ru.dorahub.tables.TableEvent;
import ru.dorahub.tables.TableState;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Хранение стола и журнала его событий. */
@Repository
@Profile("!test")
public class TableRepository {

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final Clock clock;
  private static final TypeReference<java.util.Map<String, Object>> MAP = new TypeReference<>() {};

  TableRepository(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  public Optional<Table> find(UUID id) {
    return jdbc
        .query(
            """
            SELECT id, format, state, aggregate_version, ruleset_snapshot, seating_seed, game_state
            FROM game_table
            WHERE id = ?
            """,
            (row, number) ->
                Table.restore(
                    row.getObject("id", UUID.class),
                    json.readValue(row.getString("ruleset_snapshot"), RulesetSnapshot.class),
                    format(row.getString("format")),
                    row.getLong("seating_seed"),
                    Table.State.valueOf(row.getString("state").toUpperCase(Locale.ROOT)),
                    json.readValue(row.getString("game_state"), TableState.class),
                    row.getLong("aggregate_version")),
            id)
        .stream()
        .findFirst();
  }

  /** Записать новый стол вместе с его первыми событиями. */
  public void insert(Table table) {
    jdbc.update(
        """
        INSERT INTO game_table (
            id, format, state, aggregate_version,
            ruleset_key, ruleset_version, ruleset_checksum, ruleset_snapshot,
            seating_seed, game_state, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?)
        """,
        table.id(),
        format(table.format()),
        state(table),
        table.version(),
        table.ruleset().key(),
        table.ruleset().version(),
        table.ruleset().checksum(),
        json.writeValueAsString(table.ruleset()),
        table.seatingSeed(),
        json.writeValueAsString(table.snapshot()),
        now(),
        now());
    appendEvents(table);
  }

  /**
   * Сохранить изменения стола.
   *
   * @param expectedVersion версия, с которой стол был прочитан
   * @throws OptimisticLockingFailureException если строку успели изменить
   */
  public void update(Table table, long expectedVersion) {
    int rows =
        jdbc.update(
            """
            UPDATE game_table
            SET state = ?, aggregate_version = ?, game_state = ?::jsonb, updated_at = ?
            WHERE id = ? AND aggregate_version = ?
            """,
            state(table),
            table.version(),
            json.writeValueAsString(table.snapshot()),
            now(),
            table.id(),
            expectedVersion);

    if (rows == 0) {
      throw new OptimisticLockingFailureException(
          "стол " + table.id() + " изменён параллельно: ожидалась версия " + expectedVersion);
    }
    appendEvents(table);
  }

  /**
   * Столы, за которыми сидит игрок, свежие сверху.
   *
   * <p>Участники лежат в {@code game_state} как массив JSON, поэтому фильтр идёт
   * containment-оператором. Для списка полный агрегат не поднимается: экрану нужны только
   * идентификатор, состояние и время последнего хода.
   */
  public List<TableSummary> findByParticipant(UUID playerId, int limit) {
    return jdbc.query(
        """
        SELECT id, state, format, hands_played, updated_at
        FROM game_table,
             LATERAL (SELECT (game_state ->> 'handsPlayed')::int AS hands_played) counts
        WHERE game_state -> 'participants' @> to_jsonb(?::text)
        ORDER BY updated_at DESC
        LIMIT ?
        """,
        (row, number) ->
            new TableSummary(
                row.getObject("id", UUID.class),
                Table.State.valueOf(row.getString("state").toUpperCase(Locale.ROOT)),
                format(row.getString("format")),
                row.getInt("hands_played"),
                row.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()),
        playerId.toString(),
        limit);
  }

  /**
   * Лобби, которые так и не начали партию: раздач в них нет, терять нечего.
   *
   * <p>Идущие партии сюда не попадают намеренно. Для сервера медленный ввод за столом выглядит так
   * же, как брошенный стол, а внутри уже введённые раздачи — закрывать их автоматически значит
   * терять данные.
   */
  public List<UUID> findAbandonedLobbies(java.time.Instant idleBefore, int limit) {
    return jdbc.query(
        """
        SELECT id
        FROM game_table
        WHERE state = 'lobby' AND updated_at < ?
        ORDER BY updated_at
        LIMIT ?
        """,
        (row, number) -> row.getObject("id", UUID.class),
        java.time.OffsetDateTime.ofInstant(idleBefore, java.time.ZoneOffset.UTC),
        limit);
  }

  /** Когда стол последний раз менялся: по этому клиент понимает, что партия остыла. */
  public java.time.Instant updatedAt(UUID id) {
    return jdbc.queryForObject(
        "SELECT updated_at FROM game_table WHERE id = ?",
        (row, number) -> row.getObject("updated_at", java.time.OffsetDateTime.class).toInstant(),
        id);
  }

  /**
   * Состояние стола на указанной версии.
   *
   * <p>Пусто, если такой версии не было или она промежуточная внутри команды: откатиться можно
   * только к тому, что стол когда-то показывал наружу.
   */
  public Optional<TableSnapshot> stateAt(UUID tableId, long version) {
    return jdbc
        .query(
            """
            SELECT table_state, game_state
            FROM table_event
            WHERE aggregate_id = ? AND sequence = ? AND game_state IS NOT NULL
            """,
            (row, number) ->
                new TableSnapshot(
                    Table.State.valueOf(row.getString("table_state").toUpperCase(Locale.ROOT)),
                    json.readValue(row.getString("game_state"), TableState.class)),
            tableId,
            version)
        .stream()
        .findFirst();
  }

  /** Состояние стола на момент события. */
  public record TableSnapshot(Table.State state, TableState gameState) {}

  /**
   * Выигранные раздачи игрока, свежие сверху.
   *
   * <p>Откатанные не показываются: раздача, отменённая модератором, официально не случалась, а в
   * журнале остаётся — журнал и есть история, а не витрина.
   */
  public List<WonHand> wonHands(UUID accountId, int limit) {
    return jdbc.query(
        """
        SELECT hand.aggregate_id, hand.sequence, hand.payload, hand.created_at
        FROM table_event hand
        WHERE hand.type = 'HAND_WON'
          AND hand.payload ->> 'winnerAccount' = ?
          AND NOT EXISTS (
              SELECT 1
              FROM table_event revert
              WHERE revert.aggregate_id = hand.aggregate_id
                AND revert.type = 'TABLE_REVERTED'
                AND (revert.payload ->> 'toVersion')::bigint < hand.sequence)
        ORDER BY hand.created_at DESC
        LIMIT ?
        """,
        (row, number) -> {
          var payload = json.readValue(row.getString("payload"), MAP);
          return new WonHand(
              row.getObject("aggregate_id", UUID.class),
              row.getLong("sequence"),
              ((Number) payload.get("han")).intValue(),
              ((Number) payload.get("fu")).intValue(),
              ((List<?>) payload.getOrDefault("yaku", List.of()))
                  .stream().map(String::valueOf).toList(),
              row.getObject("created_at", OffsetDateTime.class).toInstant());
        },
        accountId.toString(),
        limit);
  }

  /** Выигранная раздача в архиве игрока. */
  public record WonHand(
      UUID tableId, long sequence, int han, int fu, List<String> yaku, java.time.Instant at) {}

  /**
   * Сводка по столам для пилота.
   *
   * <p>Без игроков и без их идентификаторов: вопрос «доводят ли партии до конца» — про продукт, а
   * не про людей, и подмешивать сюда PII незачем.
   *
   * @param staleBefore граница, после которой идущая партия считается остывшей
   */
  public TableStats stats(java.time.Instant staleBefore) {
    return jdbc.queryForObject(
        """
        SELECT
            count(*) FILTER (WHERE state = 'lobby') AS lobbies,
            count(*) FILTER (WHERE state = 'active') AS active,
            count(*) FILTER (WHERE state = 'active' AND updated_at < ?) AS stale,
            count(*) FILTER (WHERE reason = 'COMPLETED') AS completed,
            count(*) FILTER (WHERE reason = 'EARLY') AS abandoned_early,
            count(*) FILTER (WHERE reason = 'ABANDONED_LOBBY') AS abandoned_lobby,
            coalesce(avg(hands) FILTER (WHERE reason = 'COMPLETED'), 0) AS hands_per_game,
            coalesce(
                percentile_cont(0.5) WITHIN GROUP (ORDER BY minutes)
                    FILTER (WHERE reason = 'COMPLETED'), 0) AS median_minutes,
            coalesce(
                percentile_cont(0.9) WITHIN GROUP (ORDER BY minutes)
                    FILTER (WHERE reason = 'COMPLETED'), 0) AS p90_minutes
        FROM game_table,
             LATERAL (
                 SELECT game_state ->> 'finishedReason' AS reason,
                        (game_state ->> 'handsPlayed')::int AS hands,
                        extract(epoch FROM updated_at - created_at) / 60 AS minutes
             ) derived
        """,
        (row, number) ->
            new TableStats(
                row.getInt("lobbies"),
                row.getInt("active"),
                row.getInt("stale"),
                row.getInt("completed"),
                row.getInt("abandoned_early"),
                row.getInt("abandoned_lobby"),
                row.getDouble("hands_per_game"),
                row.getDouble("median_minutes"),
                row.getDouble("p90_minutes")),
        java.time.OffsetDateTime.ofInstant(staleBefore, java.time.ZoneOffset.UTC));
  }

  /** Сводка по столам: сколько партий доводят до конца и сколько времени это занимает. */
  public record TableStats(
      int lobbies,
      int active,
      int stale,
      int completed,
      int abandonedEarly,
      int abandonedLobby,
      double handsPerCompletedGame,
      double medianMinutes,
      double p90Minutes) {}

  /** Есть ли у игрока уже идущая партия: за двумя столами сразу не сидят. */
  public boolean hasActiveTable(UUID playerId) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM game_table
            WHERE state = 'active' AND game_state -> 'participants' @> to_jsonb(?::text)
            """,
            Integer.class,
            playerId.toString());
    return count != null && count > 0;
  }

  /** Строка списка столов: полный агрегат для неё поднимать незачем. */
  public record TableSummary(
      UUID id,
      Table.State state,
      Table.Format format,
      int handsPlayed,
      java.time.Instant updatedAt) {}

  /** События стола, начиная со следующего после {@code since}. */
  public List<TableEvent> events(UUID tableId, long since) {
    return jdbc.query(
        """
        SELECT sequence, type, payload
        FROM table_event
        WHERE aggregate_id = ? AND sequence > ?
        ORDER BY sequence
        """,
        (row, number) ->
            new TableEvent(
                row.getLong("sequence"),
                row.getString("type"),
                json.readValue(row.getString("payload"), new TypeReference<>() {})),
        tableId,
        since);
  }

  private void appendEvents(Table table) {
    List<TableEvent> events = table.drainPendingEvents();
    for (TableEvent event : events) {
      // Состояние пишется только у последнего события команды: промежуточные версии наружу
      // не выходили, и откат к ним означал бы состояние, которого никто не видел.
      boolean last = event == events.getLast();
      jdbc.update(
          """
          INSERT INTO table_event (
              aggregate_id, sequence, type, source, payload, table_state, game_state, created_at)
          VALUES (?, ?, ?, 'manual', ?::jsonb, ?, ?::jsonb, ?)
          """,
          table.id(),
          event.sequence(),
          event.type(),
          json.writeValueAsString(event.payload()),
          last ? state(table) : null,
          last ? json.writeValueAsString(table.snapshot()) : null,
          now());
    }
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  private static String state(Table table) {
    return table.state().name().toLowerCase(Locale.ROOT);
  }

  // Схема заложена под санму, поэтому формат хранится с явным числом игроков.
  private static String format(Table.Format format) {
    return switch (format) {
      case HANCHAN -> "yonma_hanchan";
      case TONPUUSEN -> "yonma_tonpusen";
    };
  }

  private static Table.Format format(String stored) {
    return switch (stored) {
      case "yonma_hanchan" -> Table.Format.HANCHAN;
      case "yonma_tonpusen" -> Table.Format.TONPUUSEN;
      default -> throw new IllegalStateException("формат пока не поддержан: " + stored);
    };
  }
}
