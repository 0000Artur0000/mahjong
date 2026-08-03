package ru.dorahub.tables;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.dorahub.accounts.AccountRoles;
import ru.dorahub.ratings.RatedPlace;
import ru.dorahub.ratings.Ratings;
import ru.dorahub.rules.Rulesets;
import ru.dorahub.scoring.HandPayment;
import ru.dorahub.scoring.WinningHand;
import ru.dorahub.tables.internal.TableRepository;

/**
 * Публичный вход в партию: загрузить стол, выполнить команду, сохранить состояние и события одной
 * транзакцией.
 *
 * <p>Каждая команда читает стол вместе с его версией и записывает изменения условием {@code WHERE
 * aggregate_version = ?}. Параллельная попытка изменить тот же стол получает {@link
 * OptimisticLockingFailureException} и не применяется частично: состояние и журнал пишутся в одной
 * транзакции.
 */
@Service
@Profile("!test")
public class TableSessions {

  private final TableRepository tables;
  private final Rulesets rulesets;
  private final HandResults handResults;
  private final Ratings ratings;
  private final AccountRoles roles;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  TableSessions(
      TableRepository tables,
      Rulesets rulesets,
      HandResults handResults,
      Ratings ratings,
      AccountRoles roles,
      Clock clock) {
    this.tables = tables;
    this.rulesets = rulesets;
    this.handResults = handResults;
    this.ratings = ratings;
    this.roles = roles;
    this.clock = clock;
  }

  /** Столы игрока, свежие сверху: без этого списка стол не найти с другого телефона. */
  @Transactional(readOnly = true)
  public List<TableRepository.TableSummary> myTables(UUID playerId, int limit) {
    return tables.findByParticipant(playerId, Math.clamp(limit, 1, 50));
  }

  @Transactional
  public Table create(UUID creator, String rulesetKey, Table.Format format) {
    requireNoActiveTable(creator);
    Table table =
        Table.create(
            UUID.randomUUID(), rulesets.require(rulesetKey), format, random.nextLong(), creator);
    tables.insert(table);
    return table;
  }

  @Transactional
  public Table join(UUID tableId, UUID playerId) {
    // join — единственная команда без проверки участия: ею в стол и попадают
    requireNoActiveTable(playerId);
    Table table = require(tableId);
    long storedVersion = table.version();
    table.join(playerId);
    save(table, storedVersion);
    return table;
  }

  @Transactional
  public Table leave(UUID tableId, UUID playerId) {
    return command(tableId, playerId, table -> table.leave(playerId));
  }

  @Transactional
  public Table start(UUID tableId, UUID actor) {
    return command(tableId, actor, Table::start);
  }

  @Transactional
  public Table declareRiichi(UUID tableId, UUID actor, int seat) {
    return command(tableId, actor, table -> table.declareRiichi(seat));
  }

  /** Разбор раздачи без изменения стола: можно вызывать сколько угодно раз. */
  @Transactional(readOnly = true)
  public HandPayment previewHand(
      UUID tableId, WinningHand hand, int winnerSeat, Integer discarderSeat) {
    return handResults.preview(require(tableId), hand, winnerSeat, discarderSeat);
  }

  /**
   * Подтвердить выигрышную раздачу.
   *
   * @param expectedVersion версия стола, на которой клиент строил превью
   */
  @Transactional
  public HandPayment confirmHand(
      UUID tableId,
      UUID actor,
      WinningHand hand,
      int winnerSeat,
      Integer discarderSeat,
      long expectedVersion) {
    Table table = require(tableId);
    table.requireParticipant(actor);
    long storedVersion = table.version();
    HandPayment payment =
        handResults.confirm(table, hand, winnerSeat, discarderSeat, expectedVersion);
    save(table, storedVersion);
    return payment;
  }

  @Transactional
  public Table exhaustiveDraw(UUID tableId, UUID actor, Set<Integer> tenpaiSeats) {
    return command(tableId, actor, table -> table.applyExhaustiveDraw(tenpaiSeats));
  }

  @Transactional
  public Table abortiveDraw(UUID tableId, UUID actor) {
    return command(tableId, actor, Table::applyAbortiveDraw);
  }

  @Transactional
  public Table finish(UUID tableId, UUID actor) {
    return command(tableId, actor, table -> table.finish(Table.FinishReason.EARLY));
  }

  /**
   * Игрок уходит посреди партии: место освобождается, стол ждёт замену.
   *
   * <p>Освободить место может сам игрок или модератор — чужое место не освобождают, иначе из-за
   * стола можно выставить кого угодно.
   */
  @Transactional
  public Table leaveSeat(UUID tableId, UUID actor, int seat) {
    Table table = require(tableId);
    if (!actor.equals(table.seats().get(seat))) {
      roles.require(actor, AccountRoles.Role.MODERATOR);
    }
    long storedVersion = table.version();
    table.leaveSeat(seat);
    save(table, storedVersion);
    return table;
  }

  /**
   * Сесть на освободившееся место.
   *
   * <p>Само-обслуживание: замена приходит со своим телефоном и садится сама. Выгнать кого-то этим
   * нельзя — место должно быть уже свободным.
   */
  @Transactional
  public Table takeSeat(UUID tableId, UUID playerId, int seat) {
    requireNoActiveTable(playerId);
    Table table = require(tableId);
    long storedVersion = table.version();
    table.takeSeat(seat, playerId);
    save(table, storedVersion);
    return table;
  }

  /**
   * Вернуть партию к прошлой версии.
   *
   * <p>Право модератора, а не участника: игрок правит только то, что ещё не подтвердил, иначе за
   * столом всегда найдётся желающий переиграть проигранную раздачу. Участие в партии не требуется —
   * модератор за столом не сидит.
   *
   * @param toVersion версия, к которой возвращаемся; должна быть той, что стол показывал наружу
   * @param reason повод, попадает в журнал
   */
  @Transactional
  public Table revert(UUID tableId, UUID actor, long toVersion, String reason) {
    roles.require(actor, AccountRoles.Role.MODERATOR);
    Table table = require(tableId);
    TableRepository.TableSnapshot restored =
        tables
            .stateAt(tableId, toVersion)
            .orElseThrow(
                () -> new IllegalArgumentException("версии " + toVersion + " у стола не было"));
    long storedVersion = table.version();
    // Партия, уже посчитанная в лестницу, сначала снимается с неё: иначе неверный результат
    // остался бы в рейтинге навсегда, а доигранная заново партия начислилась бы второй раз.
    if (table.countsForRating()) {
      ratings.revoke(tableId);
    }
    table.revertTo(restored.state(), restored.gameState(), toVersion, reason);
    save(table, storedVersion);
    return table;
  }

  /**
   * Закрыть лобби, которые так и не начали партию.
   *
   * <p>Идущие партии не трогаются: там уже введённые раздачи, а медленный ввод за столом для
   * сервера неотличим от брошенного стола. Их клиент показывает остывшими, и закрывает участник
   * кнопкой.
   */
  @Transactional
  public int closeAbandonedLobbies(Duration idleFor, int limit) {
    int closed = 0;
    for (UUID tableId : tables.findAbandonedLobbies(clock.instant().minus(idleFor), limit)) {
      Table table = require(tableId);
      long storedVersion = table.version();
      table.finish(Table.FinishReason.ABANDONED_LOBBY);
      save(table, storedVersion);
      closed++;
    }
    return closed;
  }

  /**
   * Сводка по столам для пилота: доводят ли партии до конца и сколько это занимает.
   *
   * <p>Право модератора: это внутренняя картина продукта, а не публичная статистика.
   *
   * @param staleAfter простой, после которого идущая партия считается остывшей
   */
  @Transactional(readOnly = true)
  public TableRepository.TableStats stats(UUID actor, Duration staleAfter) {
    roles.require(actor, AccountRoles.Role.MODERATOR);
    return tables.stats(clock.instant().minus(staleAfter));
  }

  /** Когда стол последний раз менялся. */
  @Transactional(readOnly = true)
  public Instant updatedAt(UUID tableId) {
    return tables.updatedAt(tableId);
  }

  @Transactional(readOnly = true)
  public Table require(UUID tableId) {
    return tables
        .find(tableId)
        .orElseThrow(() -> new NoSuchElementException("стол не найден: " + tableId));
  }

  /** Выигранные игроком раздачи, свежие сверху. Откатанные не показываются. */
  @Transactional(readOnly = true)
  public List<TableRepository.WonHand> wonHands(UUID accountId, int limit) {
    return tables.wonHands(accountId, Math.clamp(limit, 1, 100));
  }

  /** Лента событий стола для опроса клиентом. */
  @Transactional(readOnly = true)
  public List<TableEvent> events(UUID tableId, long since) {
    return tables.events(tableId, since);
  }

  /**
   * За двумя идущими столами одновременно не сидят.
   *
   * <p>Проверка живёт здесь, а не в агрегате: стол по определению не знает о других столах. Отказ
   * приходит из сервиса, поэтому прямой HTTP-вызов его не обходит.
   */
  private void requireNoActiveTable(UUID playerId) {
    if (tables.hasActiveTable(playerId)) {
      throw new IllegalStateException("у игрока уже есть идущая партия");
    }
  }

  /**
   * Сохранить стол и, если партия только что доиграна до конца формата, обновить лестницу.
   *
   * <p>Рейтинг пишется той же транзакцией: партия, посчитанная в лестницу, но не сохранённая, — это
   * разъехавшаяся история. Брошенные и закрытые досрочно столы сюда не попадают, за это отвечает
   * {@link Table#countsForRating()}.
   */
  private void save(Table table, long storedVersion) {
    tables.update(table, storedVersion);
    if (table.countsForRating()) {
      ratings.record(
          table.id(),
          table.format().name().toLowerCase(Locale.ROOT),
          table.standings().stream()
              .map(place -> new RatedPlace(table.seats().get(place.seat()), place.place()))
              .toList());
    }
  }

  private Table command(UUID tableId, UUID actor, Consumer<Table> command) {
    Table table = require(tableId);
    table.requireParticipant(actor);
    long storedVersion = table.version();
    command.accept(table);
    save(table, storedVersion);
    return table;
  }
}
