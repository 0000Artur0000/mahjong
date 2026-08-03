package ru.dorahub.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.dorahub.scoring.HandPayment;
import ru.dorahub.scoring.Wind;
import ru.dorahub.scoring.WinningHand;
import ru.dorahub.tables.Table.Format;
import ru.dorahub.tables.Table.State;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class TableSessionsIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  /** Саншоку закрытым роном: 2 хан 40 фу. */
  private static final WinningHand SANSHOKU =
      new WinningHand(
          List.of(
              "1m", "2m", "3m", "4m", "5m", "6m", "1p", "2p", "3p", "1s", "2s", "3s", "9s", "9s"),
          List.of(),
          "9s",
          false,
          WinningHand.Dora.NONE,
          Set.of());

  @Autowired private TableSessions sessions;
  @Autowired private JdbcTemplate jdbc;

  /** Создатель стола: он же актор всех команд в тестах. */
  private UUID creator;

  @Test
  void keepsRulesetSnapshotAndStateAcrossReload() {
    UUID tableId = startedTable();

    Table reloaded = sessions.require(tableId);

    assertThat(reloaded.state()).isEqualTo(State.ACTIVE);
    assertThat(reloaded.ruleset().id()).isEqualTo("rrc-ru@1.0");
    assertThat(reloaded.ruleset().uma()).containsExactly(15000, 5000, -5000, -15000);
    assertThat(reloaded.scores()).containsExactly(30000, 30000, 30000, 30000);
    assertThat(reloaded.roundWind()).isEqualTo(Wind.EAST);
    assertThat(reloaded.seats()).hasSize(4);
  }

  /** Посадка восстанавливается ровно та же, что была записана. */
  @Test
  void reloadsSeatingUnchanged() {
    UUID tableId = startedTable();
    List<UUID> seats = sessions.require(tableId).seats();

    assertThat(sessions.require(tableId).seats()).isEqualTo(seats);
  }

  @Test
  void appliesHandAndPersistsNewState() {
    UUID tableId = startedTable();
    Table before = sessions.require(tableId);

    HandPayment payment = sessions.confirmHand(tableId, creator, SANSHOKU, 1, 2, before.version());

    assertThat(payment.seatDelta()).containsExactly(0, 2600, -2600, 0);
    Table after = sessions.require(tableId);
    assertThat(after.scores()).containsExactly(30000, 32600, 27400, 30000);
    assertThat(after.dealerSeat()).isEqualTo(1);
    assertThat(after.handsPlayed()).isEqualTo(1);
    assertThat(after.version()).isGreaterThan(before.version());
  }

  @Test
  void previewChangesNothingInTheDatabase() {
    UUID tableId = startedTable();
    long version = sessions.require(tableId).version();

    sessions.previewHand(tableId, SANSHOKU, 1, 2);

    assertThat(sessions.require(tableId).version()).isEqualTo(version);
    assertThat(sessions.require(tableId).scores()).containsExactly(30000, 30000, 30000, 30000);
  }

  /** Журнал заполняется по порядку и совпадает с версиями агрегата. */
  @Test
  void writesMonotonicEventLog() {
    UUID tableId = startedTable();
    sessions.confirmHand(tableId, creator, SANSHOKU, 1, 2, sessions.require(tableId).version());

    List<TableEvent> events = sessions.events(tableId, 0);

    assertThat(events)
        .extracting(TableEvent::type)
        .containsExactly(
            "TABLE_CREATED",
            "PLAYER_JOINED",
            "PLAYER_JOINED",
            "PLAYER_JOINED",
            "PLAYER_JOINED",
            "GAME_STARTED",
            "HAND_WON");
    assertThat(events).extracting(TableEvent::sequence).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
    assertThat(events.getLast().sequence()).isEqualTo(sessions.require(tableId).version());
  }

  @Test
  void pollsOnlyEventsAfterGivenSequence() {
    UUID tableId = startedTable();
    long seen = sessions.require(tableId).version();
    sessions.declareRiichi(tableId, creator, 0);

    List<TableEvent> fresh = sessions.events(tableId, seen);

    assertThat(fresh).extracting(TableEvent::type).containsExactly("RIICHI_DECLARED");
    assertThat(fresh.getFirst().payload()).containsEntry("seat", 0);
  }

  /** Отклонённая команда не оставляет ни строки состояния, ни события. */
  @Test
  void rejectedCommandWritesNothing() {
    UUID tableId = startedTable();
    long version = sessions.require(tableId).version();
    int eventsBefore = sessions.events(tableId, 0).size();

    assertThatThrownBy(() -> sessions.join(tableId, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);

    assertThat(sessions.require(tableId).version()).isEqualTo(version);
    assertThat(sessions.events(tableId, 0)).hasSize(eventsBefore);
  }

  @Test
  void rejectsConfirmOnStaleVersion() {
    UUID tableId = startedTable();
    long stale = sessions.require(tableId).version() - 1;

    assertThatThrownBy(() -> sessions.confirmHand(tableId, creator, SANSHOKU, 1, 2, stale))
        .isInstanceOf(OptimisticLockingFailureException.class);

    assertThat(sessions.require(tableId).scores()).containsExactly(30000, 30000, 30000, 30000);
  }

  /** Полный ханчан переживает перезагрузку стола на каждом шаге и попадает в лестницу. */
  @Test
  void playsHanchanThroughTheDatabase() {
    UUID tableId = startedTable();

    for (int hand = 0; hand < 8; hand++) {
      Table table = sessions.require(tableId);
      assertThat(table.state()).as("раздача %s", hand + 1).isEqualTo(State.ACTIVE);
      int winner = (table.dealerSeat() + 1) % 4;
      int discarder = (table.dealerSeat() + 2) % 4;
      sessions.confirmHand(tableId, creator, SANSHOKU, winner, discarder, table.version());
    }

    Table finished = sessions.require(tableId);
    assertThat(finished.state()).isEqualTo(State.FINISHED);
    assertThat(finished.standings()).hasSize(4);
    assertThat(finished.scores().stream().mapToInt(Integer::intValue).sum()).isEqualTo(120000);
    assertThat(sessions.events(tableId, 0)).extracting(TableEvent::type).contains("TABLE_FINISHED");

    // Доигранная партия уходит в лестницу. Проверяем через хранилище, а не через модуль
    // рейтинга: у столов нет причин знать его API, и граница модулей это запрещает.
    assertThat(finished.countsForRating()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM rating_change WHERE table_id = ? AND format = 'hanchan'",
                Integer.class,
                tableId))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(delta) FROM rating_change WHERE table_id = ?", Integer.class, tableId))
        .isZero();
  }

  @Test
  void persistsDraws() {
    UUID tableId = startedTable();

    sessions.exhaustiveDraw(tableId, creator, Set.of(0, 1));
    sessions.abortiveDraw(tableId, creator);

    Table table = sessions.require(tableId);
    assertThat(table.scores()).containsExactly(31500, 31500, 28500, 28500);
    assertThat(table.honba()).isEqualTo(2);
    assertThat(sessions.events(tableId, 0))
        .extracting(TableEvent::type)
        .contains("EXHAUSTIVE_DRAW", "ABORTIVE_DRAW");
  }

  /** Пресет из classpath зарегистрирован в базе — иначе стол не создастся по внешнему ключу. */
  @Test
  void registersRulesetOnStartup() {
    String status =
        jdbc.queryForObject(
            "SELECT certification_status FROM ruleset WHERE key = 'rrc-ru' AND version = '1.0'",
            String.class);

    assertThat(status).isEqualTo("draft");
  }

  /** Без списка стол не найти с другого устройства. */
  @Test
  void listsTablesOfTheParticipant() {
    UUID tableId = startedTable();
    UUID stranger = UUID.randomUUID();

    assertThat(sessions.myTables(creator, 20)).extracting(s -> s.id()).contains(tableId);
    assertThat(sessions.myTables(stranger, 20)).isEmpty();
  }

  /** §: за двумя идущими партиями одновременно не сидят. */
  @Test
  void rejectsASecondActiveTable() {
    startedTable();

    assertThatThrownBy(() -> sessions.create(creator, "rrc-ru", Format.HANCHAN))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("идущая партия");
  }

  @Test
  void allowsANewTableOnceThePreviousOneIsFinished() {
    UUID tableId = startedTable();
    sessions.finish(tableId, creator);

    assertThat(sessions.create(creator, "rrc-ru", Format.HANCHAN)).isNotNull();
  }

  /** Лобби, в котором так и не начали партию, закрывается само: терять там нечего. */
  @Test
  void closesLobbiesLeftWithoutAStart() {
    creator = UUID.randomUUID();
    UUID lobby = sessions.create(creator, "rrc-ru", Format.HANCHAN).id();
    idleFor(lobby, Duration.ofHours(30));

    assertThat(sessions.closeAbandonedLobbies(Duration.ofHours(24), 100)).isEqualTo(1);

    Table closed = sessions.require(lobby);
    assertThat(closed.state()).isEqualTo(State.FINISHED);
    assertThat(closed.finishedReason()).isEqualTo(Table.FinishReason.ABANDONED_LOBBY);
    assertThat(closed.countsForRating()).isFalse();
    assertThat(sessions.events(lobby, 0))
        .filteredOn(event -> event.type().equals("TABLE_FINISHED"))
        .singleElement()
        .satisfies(event -> assertThat(event.payload()).containsEntry("reason", "ABANDONED_LOBBY"));
  }

  /**
   * Идущая партия сама не закрывается, даже простояв сутки: в ней уже введённые раздачи, а
   * медленный ввод за столом для сервера неотличим от брошенного стола.
   */
  @Test
  void keepsIdleGamesOpen() {
    UUID tableId = startedTable();
    idleFor(tableId, Duration.ofDays(7));

    assertThat(sessions.closeAbandonedLobbies(Duration.ofHours(24), 100)).isZero();
    assertThat(sessions.require(tableId).state()).isEqualTo(State.ACTIVE);
  }

  /** Брошенный стол в лестницу не попадает: считаются только доигранные партии. */
  @Test
  void keepsAbandonedTablesOutOfTheLadder() {
    UUID tableId = startedTable();
    sessions.confirmHand(tableId, creator, SANSHOKU, 1, 2, sessions.require(tableId).version());

    sessions.finish(tableId, creator);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM rating_change WHERE table_id = ?", Integer.class, tableId))
        .isZero();
  }

  /** Доигранная до конца формата партия идёт в зачёт, закрытая кнопкой — нет. */
  @Test
  void countsOnlyGamesPlayedToTheEndOfTheFormat() {
    UUID tableId = startedTable();
    sessions.finish(tableId, creator);

    Table finished = sessions.require(tableId);
    assertThat(finished.finishedReason()).isEqualTo(Table.FinishReason.EARLY);
    assertThat(finished.countsForRating()).isFalse();
  }

  /**
   * Ошибочно подтверждённая раздача отменяется откатом: очки и дилер возвращаются, а журнал
   * остаётся целым.
   */
  @Test
  void revertsAConfirmedHandKeepingTheJournal() {
    UUID tableId = startedTable();
    UUID moderator = moderator();
    long beforeHand = sessions.require(tableId).version();
    sessions.confirmHand(tableId, creator, SANSHOKU, 1, 2, beforeHand);
    List<Integer> wrong = sessions.require(tableId).scores();

    Table reverted = sessions.revert(tableId, moderator, beforeHand, "ошиблись победителем");

    assertThat(reverted.scores()).containsExactly(30000, 30000, 30000, 30000).isNotEqualTo(wrong);
    assertThat(reverted.handsPlayed()).isZero();
    assertThat(reverted.version()).isGreaterThan(beforeHand + 1);
    assertThat(sessions.events(tableId, 0))
        .extracting(TableEvent::type)
        .containsSubsequence("HAND_WON", "TABLE_REVERTED");
    assertThat(sessions.events(tableId, 0).getLast().payload())
        .containsEntry("reason", "ошиблись победителем");
  }

  /** После отката можно ввести раздачу заново — стол снова принимает команды. */
  @Test
  void acceptsANewHandAfterTheRevert() {
    UUID tableId = startedTable();
    long beforeHand = sessions.require(tableId).version();
    sessions.confirmHand(tableId, creator, SANSHOKU, 1, 2, beforeHand);
    sessions.revert(tableId, moderator(), beforeHand, "ошиблись победителем");

    sessions.confirmHand(tableId, creator, SANSHOKU, 2, 1, sessions.require(tableId).version());

    assertThat(sessions.require(tableId).handsPlayed()).isEqualTo(1);
  }

  /** Игрок за столом откатывать не может: иначе всегда найдётся желающий переиграть. */
  @Test
  void refusesRevertToPlayers() {
    UUID tableId = startedTable();
    long beforeHand = sessions.require(tableId).version();
    sessions.confirmHand(tableId, creator, SANSHOKU, 1, 2, beforeHand);

    assertThatThrownBy(() -> sessions.revert(tableId, creator, beforeHand, "хочу переиграть"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(sessions.require(tableId).handsPlayed()).isEqualTo(1);
  }

  /** Откатиться можно только к версии, которую стол показывал наружу. */
  @Test
  void refusesRevertToAVersionThatNeverExisted() {
    UUID tableId = startedTable();
    UUID moderator = moderator();
    long current = sessions.require(tableId).version();

    assertThatThrownBy(() -> sessions.revert(tableId, moderator, current + 5, "мимо"))
        .isInstanceOf(IllegalArgumentException.class);
    // Промежуточная версия внутри команды наружу не выходила: к ней тоже нельзя.
    assertThatThrownBy(() -> sessions.revert(tableId, moderator, 1, "мимо"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Доигранную партию тоже можно поправить: рейтинг за неё снимается встречным начислением, а после
   * переигровки начисляется заново.
   */
  @Test
  void revertsAFinishedGameAndTakesItOffTheLadder() {
    UUID tableId = startedTable();
    UUID moderator = moderator();
    long beforeLastHand = 0;
    for (int hand = 0; hand < 8; hand++) {
      Table table = sessions.require(tableId);
      beforeLastHand = table.version();
      sessions.confirmHand(
          tableId,
          creator,
          SANSHOKU,
          (table.dealerSeat() + 1) % 4,
          (table.dealerSeat() + 2) % 4,
          table.version());
    }
    UUID player = sessions.require(tableId).seats().getFirst();
    assertThat(activeRatingRows(tableId)).isEqualTo(4);

    sessions.revert(tableId, moderator, beforeLastHand, "последняя раздача введена неверно");

    assertThat(sessions.require(tableId).state()).isEqualTo(State.ACTIVE);
    assertThat(activeRatingRows(tableId)).isZero();
    // Рейтинг вернулся к тому, с чего игрок начинал: других партий у него не было.
    assertThat(currentRating(player)).isEqualTo(1500);

    // Переигрываем с другим платящим: иначе все восемь раздач дают ровный счёт, все делят
    // первое место, и нулевые дельты ничего не докажут.
    Table replayed = sessions.require(tableId);
    sessions.confirmHand(
        tableId,
        creator,
        SANSHOKU,
        (replayed.dealerSeat() + 1) % 4,
        (replayed.dealerSeat() + 3) % 4,
        replayed.version());

    assertThat(sessions.require(tableId).state()).isEqualTo(State.FINISHED);
    assertThat(activeRatingRows(tableId)).isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM rating_change WHERE table_id = ? AND NOT compensated"
                    + " AND delta <> 0",
                Integer.class,
                tableId))
        .isPositive();
  }

  /** Архив рук ищется по человеку: место через месяц ничего не скажет. */
  @Test
  void keepsWonHandsOfThePlayerAndDropsRevertedOnes() {
    UUID tableId = startedTable();
    long beforeHand = sessions.require(tableId).version();
    UUID winner = sessions.require(tableId).seats().get(1);
    sessions.confirmHand(tableId, creator, SANSHOKU, 1, 2, beforeHand);

    assertThat(sessions.wonHands(winner, 20))
        .singleElement()
        .satisfies(
            hand -> {
              assertThat(hand.tableId()).isEqualTo(tableId);
              assertThat(hand.han()).isEqualTo(2);
              assertThat(hand.fu()).isEqualTo(40);
              assertThat(hand.yaku()).contains("Sanshoku");
            });
    assertThat(sessions.wonHands(sessions.require(tableId).seats().get(2), 20)).isEmpty();

    sessions.revert(tableId, moderator(), beforeHand, "раздача введена неверно");

    // Откатанная раздача официально не случалась, хотя в журнале осталась.
    assertThat(sessions.wonHands(winner, 20)).isEmpty();
  }

  /** Человек уходит посреди ханчана: стол ждёт замену, а не закрывается. */
  @Test
  void substitutesAPlayerMidGame() {
    UUID tableId = startedTable();
    // Уходит не создатель: он вводит раздачи и должен остаться участником.
    int seat = (sessions.require(tableId).seatOf(creator) + 1) % 4;
    UUID leaving = sessions.require(tableId).seats().get(seat);
    UUID incoming = UUID.randomUUID();
    sessions.confirmHand(tableId, creator, SANSHOKU, 1, 2, sessions.require(tableId).version());
    List<Integer> scores = sessions.require(tableId).scores();

    sessions.leaveSeat(tableId, leaving, seat);

    Table waiting = sessions.require(tableId);
    assertThat(waiting.state()).isEqualTo(State.ACTIVE);
    assertThat(waiting.vacantSeats()).containsExactly(seat);
    assertThat(waiting.scores()).isEqualTo(scores);
    // С пустым местом раздачу вводить нельзя: победа отсутствующего — запись о том, чего не было.
    assertThatThrownBy(
            () ->
                sessions.confirmHand(
                    tableId, creator, SANSHOKU, 1, 2, sessions.require(tableId).version()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("пустое место");
    // Ушедший свободен и может сесть за другой стол.
    assertThat(sessions.create(leaving, "rrc-ru", Format.HANCHAN)).isNotNull();

    sessions.takeSeat(tableId, incoming, seat);

    Table resumed = sessions.require(tableId);
    assertThat(resumed.seats().get(seat)).isEqualTo(incoming);
    assertThat(resumed.vacantSeats()).isEmpty();
    assertThat(resumed.scores()).isEqualTo(scores);
    sessions.confirmHand(tableId, creator, SANSHOKU, 1, 2, resumed.version());
    assertThat(sessions.require(tableId).handsPlayed()).isEqualTo(2);
  }

  /** Партия с заменой не идёт в рейтинг: за одно место играли двое. */
  @Test
  void keepsSubstitutedGamesOutOfTheLadder() {
    UUID tableId = startedTable();
    int seat = (sessions.require(tableId).seatOf(creator) + 1) % 4;
    sessions.leaveSeat(tableId, sessions.require(tableId).seats().get(seat), seat);
    sessions.takeSeat(tableId, UUID.randomUUID(), seat);

    for (int hand = 0; hand < 8; hand++) {
      Table table = sessions.require(tableId);
      sessions.confirmHand(
          tableId,
          creator,
          SANSHOKU,
          (table.dealerSeat() + 1) % 4,
          (table.dealerSeat() + 2) % 4,
          table.version());
    }

    Table finished = sessions.require(tableId);
    assertThat(finished.state()).isEqualTo(State.FINISHED);
    assertThat(finished.finishedReason()).isEqualTo(Table.FinishReason.COMPLETED);
    assertThat(finished.countsForRating()).isFalse();
    assertThat(activeRatingRows(tableId)).isZero();
  }

  /** Чужое место не освобождают: иначе из-за стола можно выставить кого угодно. */
  @Test
  void refusesToFreeSomeoneElsesSeat() {
    UUID tableId = startedTable();

    int someoneElse = (sessions.require(tableId).seatOf(creator) + 1) % 4;

    assertThatThrownBy(() -> sessions.leaveSeat(tableId, creator, someoneElse))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(sessions.require(tableId).vacantSeats()).isEmpty();

    // Модератор может: он за столом не сидит, но разбирает спорные ситуации.
    sessions.leaveSeat(tableId, moderator(), someoneElse);
    assertThat(sessions.require(tableId).vacantSeats()).containsExactly(someoneElse);
  }

  /** Занять можно только свободное место. */
  @Test
  void refusesToTakeAnOccupiedSeat() {
    UUID tableId = startedTable();

    assertThatThrownBy(
            () ->
                sessions.takeSeat(
                    tableId, UUID.randomUUID(), sessions.require(tableId).seatOf(creator)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("занято");
  }

  /**
   * Из лобби можно выйти.
   *
   * <p>Без этого случайно занятое место запирает игрока: за двумя столами сразу не сидят, а
   * распустить чужой стол он не может.
   */
  @Test
  void letsAPlayerLeaveTheLobbyAndSitElsewhere() {
    creator = UUID.randomUUID();
    UUID lobby = sessions.create(creator, "rrc-ru", Format.HANCHAN).id();
    UUID guest = UUID.randomUUID();
    sessions.join(lobby, guest);

    sessions.leave(lobby, guest);

    assertThat(sessions.require(lobby).participants()).containsExactly(creator);
    assertThat(sessions.create(guest, "rrc-ru", Format.HANCHAN)).isNotNull();
  }

  /** Из начатой партии так не уходят: для этого есть освобождение места. */
  @Test
  void refusesToLeaveAStartedGame() {
    UUID tableId = startedTable();

    assertThatThrownBy(() -> sessions.leave(tableId, creator))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("лобби");
  }

  @Test
  void rejectsUnknownTable() {
    assertThatThrownBy(() -> sessions.require(UUID.randomUUID()))
        .isInstanceOf(java.util.NoSuchElementException.class);
  }

  /** Сколько начислений за партию действует сейчас: отменённые не считаются. */
  private int activeRatingRows(UUID tableId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM rating_change WHERE table_id = ? AND NOT compensated",
        Integer.class,
        tableId);
  }

  private int currentRating(UUID accountId) {
    return jdbc.queryForObject(
        "SELECT rating_after FROM rating_change WHERE account_id = ? ORDER BY seq DESC LIMIT 1",
        Integer.class,
        accountId);
  }

  /** Модератор: единственная роль, которая сейчас что-то может. */
  private UUID moderator() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO app_user (id, status, nickname, nickname_normalized, role)
        VALUES (?, 'active', ?, ?, 'moderator')
        """,
        id,
        "mod-" + id,
        "mod-" + id);
    return id;
  }

  /** Отодвинуть последний ход стола в прошлое: ждать сутки в тесте нечем. */
  private void idleFor(UUID tableId, Duration idle) {
    jdbc.update(
        "UPDATE game_table SET updated_at = ? WHERE id = ?",
        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minus(idle),
        tableId);
  }

  private UUID startedTable() {
    creator = UUID.randomUUID();
    Table table = sessions.create(creator, "rrc-ru", Format.HANCHAN);
    for (int player = 0; player < 3; player++) {
      sessions.join(table.id(), UUID.randomUUID());
    }
    sessions.start(table.id(), creator);
    return table.id();
  }
}
