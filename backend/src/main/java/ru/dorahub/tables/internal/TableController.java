package ru.dorahub.tables.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.dorahub.accounts.AccountDirectory;
import ru.dorahub.scoring.FinalScore;
import ru.dorahub.scoring.HandPayment;
import ru.dorahub.scoring.WinningHand;
import ru.dorahub.tables.Table;
import ru.dorahub.tables.TableEvent;
import ru.dorahub.tables.TableSessions;

/**
 * HTTP-вход в партию.
 *
 * <p>Клиент получает состояние стола вместе с {@code version} и возвращает её в подтверждении
 * раздачи: если стол успел измениться, ответ будет 409 и очки не применятся дважды. Лента событий
 * читается опросом {@code ?since=}; она нужна для истории и обновления соседних телефонов, но
 * источником истины остаётся состояние стола.
 */
@RestController
@RequestMapping("/api/v1/tables")
@Profile("!test")
class TableController {

  private final TableSessions sessions;
  private final AccountDirectory accounts;

  TableController(TableSessions sessions, AccountDirectory accounts) {
    this.sessions = sessions;
    this.accounts = accounts;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  TableView create(@Valid @RequestBody CreateTableRequest body, Authentication authentication) {
    return view(
        guarded(() -> sessions.create(actor(authentication), body.rulesetKey(), body.format())));
  }

  /** Столы игрока: свежие сверху. Без него стол не найти с другого устройства. */
  @GetMapping
  List<TableSummaryView> myTables(
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
      Authentication authentication) {
    return guarded(() -> sessions.myTables(actor(authentication), limit)).stream()
        .map(
            summary ->
                new TableSummaryView(
                    summary.id(),
                    lower(summary.state().name()),
                    lower(summary.format().name()),
                    summary.handsPlayed(),
                    summary.updatedAt().toString()))
        .toList();
  }

  /**
   * Пилотные метрики: доводят ли партии до конца и сколько времени это занимает.
   *
   * <p>Без PII — только столы. Считается из уже накопленного, отдельного сбора событий нет.
   */
  @GetMapping("/stats")
  TableStatsView stats(Authentication authentication) {
    var stats =
        guarded(() -> sessions.stats(actor(authentication), java.time.Duration.ofHours(12)));
    int finished = stats.completed() + stats.abandonedEarly() + stats.abandonedLobby();
    return new TableStatsView(
        stats.lobbies(),
        stats.active(),
        stats.stale(),
        stats.completed(),
        stats.abandonedEarly(),
        stats.abandonedLobby(),
        finished == 0 ? 0 : Math.round(stats.completed() * 1000.0 / finished) / 10.0,
        Math.round(stats.handsPerCompletedGame() * 10) / 10.0,
        Math.round(stats.medianMinutes()),
        Math.round(stats.p90Minutes()));
  }

  /** Архив своих выигранных раздач: лучшая рука видна по хан и фу. */
  @GetMapping("/hands/mine")
  List<WonHandView> myHands(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      Authentication authentication) {
    return guarded(() -> sessions.wonHands(actor(authentication), limit)).stream()
        .map(
            hand ->
                new WonHandView(
                    hand.tableId(),
                    hand.sequence(),
                    hand.han(),
                    hand.fu(),
                    hand.yaku(),
                    hand.at().toString()))
        .toList();
  }

  @GetMapping("/{tableId}")
  TableView get(@PathVariable UUID tableId) {
    return view(guarded(() -> sessions.require(tableId)));
  }

  @GetMapping("/{tableId}/events")
  List<TableEventView> events(
      @PathVariable UUID tableId, @RequestParam(defaultValue = "0") @Min(0) long since) {
    return guarded(() -> sessions.events(tableId, since)).stream()
        .map(TableController::view)
        .toList();
  }

  @PostMapping("/{tableId}/players")
  TableView join(@PathVariable UUID tableId, Authentication authentication) {
    return view(guarded(() -> sessions.join(tableId, actor(authentication))));
  }

  /**
   * Выйти из лобби.
   *
   * <p>Без этого случайно занятое место запирает игрока: за двумя столами сразу не сидят, а
   * распустить чужой стол он не может.
   */
  @DeleteMapping("/{tableId}/players/me")
  TableView leave(@PathVariable UUID tableId, Authentication authentication) {
    return view(guarded(() -> sessions.leave(tableId, actor(authentication))));
  }

  @PostMapping("/{tableId}/start")
  TableView start(@PathVariable UUID tableId, Authentication authentication) {
    return view(guarded(() -> sessions.start(tableId, actor(authentication))));
  }

  @PostMapping("/{tableId}/riichi")
  TableView declareRiichi(
      @PathVariable UUID tableId,
      @Valid @RequestBody RiichiRequest body,
      Authentication authentication) {
    return view(guarded(() -> sessions.declareRiichi(tableId, actor(authentication), body.seat())));
  }

  /** Разбор раздачи без изменения стола. */
  @PostMapping("/{tableId}/hands/preview")
  HandPaymentView preview(@PathVariable UUID tableId, @Valid @RequestBody HandRequest body) {
    return view(
        guarded(
            () ->
                sessions.previewHand(
                    tableId, body.toHand(), body.winnerSeat(), body.discarderSeat())));
  }

  @PostMapping("/{tableId}/hands")
  HandPaymentView confirm(
      @PathVariable UUID tableId,
      @Valid @RequestBody HandRequest body,
      Authentication authentication) {
    if (body.expectedVersion() == null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "подтверждение требует expectedVersion");
    }
    return view(
        guarded(
            () ->
                sessions.confirmHand(
                    tableId,
                    actor(authentication),
                    body.toHand(),
                    body.winnerSeat(),
                    body.discarderSeat(),
                    body.expectedVersion())));
  }

  @PostMapping("/{tableId}/draws")
  TableView draw(
      @PathVariable UUID tableId,
      @Valid @RequestBody DrawRequest body,
      Authentication authentication) {
    UUID actor = actor(authentication);
    Set<Integer> tenpai = body.tenpaiSeats() == null ? Set.of() : body.tenpaiSeats();
    return view(
        guarded(
            () ->
                switch (body.type()) {
                  case EXHAUSTIVE -> sessions.exhaustiveDraw(tableId, actor, tenpai);
                  case ABORTIVE -> sessions.abortiveDraw(tableId, actor);
                }));
  }

  /** Уйти из-за стола посреди партии: место освобождается, очки остаются. */
  @DeleteMapping("/{tableId}/seats/{seat}/player")
  TableView leaveSeat(
      @PathVariable UUID tableId,
      @PathVariable @Min(0) @Max(3) int seat,
      Authentication authentication) {
    return view(guarded(() -> sessions.leaveSeat(tableId, actor(authentication), seat)));
  }

  /** Сесть на освободившееся место. Партия с заменой перестаёт идти в рейтинг. */
  @PostMapping("/{tableId}/seats/{seat}/player")
  TableView takeSeat(
      @PathVariable UUID tableId,
      @PathVariable @Min(0) @Max(3) int seat,
      Authentication authentication) {
    return view(guarded(() -> sessions.takeSeat(tableId, actor(authentication), seat)));
  }

  /** Откат подтверждённой раздачи: право модератора, см. {@link TableSessions#revert}. */
  @PostMapping("/{tableId}/revert")
  TableView revert(
      @PathVariable UUID tableId,
      @Valid @RequestBody RevertRequest body,
      Authentication authentication) {
    return view(
        guarded(
            () ->
                sessions.revert(tableId, actor(authentication), body.toVersion(), body.reason())));
  }

  @PostMapping("/{tableId}/finish")
  TableView finish(@PathVariable UUID tableId, Authentication authentication) {
    return view(guarded(() -> sessions.finish(tableId, actor(authentication))));
  }

  /**
   * Перевод доменных отказов в HTTP.
   *
   * <p>Отображение живёт здесь, а не в общем обработчике ошибок: там оно превратило бы любое
   * внутреннее {@code IllegalStateException} всего приложения в 409 с текстом наружу. Сюда попадают
   * только исключения этого контроллера, а их сообщения домен пишет для игрока.
   */
  private static <T> T guarded(Supplier<T> call) {
    try {
      return call.get();
    } catch (AccessDeniedException e) {
      // Иначе общий обработчик превратит отказ в 500: он ловит всё подряд намеренно.
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException | OptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }
  }

  private static UUID actor(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || "anonymousUser".equals(authentication.getName())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
    try {
      return UUID.fromString(authentication.getName());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
  }

  private TableView view(Table table) {
    return new TableView(
        table.id(),
        lower(table.state().name()),
        lower(table.format().name()),
        table.ruleset().id(),
        table.participants(),
        table.seats(),
        table.scores(),
        table.dealerSeat(),
        lower(table.roundWind().name()),
        table.handNumber(),
        table.honba(),
        table.riichiSticks(),
        table.handsPlayed(),
        table.vacantSeats(),
        table.version(),
        // Время последнего хода отдаём наружу: по нему экран показывает, что партия
        // остыла. Сам сервер идущие столы не закрывает — там уже введённые раздачи.
        // Ветер без имени не говорит ничего: за столом надо понимать, кто где сидит.
        accounts.nicknames(table.participants()).entrySet().stream()
            .collect(Collectors.toMap(entry -> entry.getKey().toString(), Map.Entry::getValue)),
        sessions.updatedAt(table.id()).toString(),
        table.finishedReason() == null ? null : lower(table.finishedReason().name()),
        table.state() == Table.State.FINISHED && !table.scores().isEmpty()
            ? table.standings().stream().map(TableController::view).toList()
            : null);
  }

  private static PlacementView view(FinalScore.Placement placement) {
    return new PlacementView(
        placement.seat(),
        placement.place(),
        placement.points(),
        placement.uma(),
        placement.result());
  }

  private static HandPaymentView view(HandPayment payment) {
    return new HandPaymentView(
        payment.han(),
        payment.fu(),
        payment.yaku(),
        new DoraRequest(
            payment.dora().ordinary(),
            payment.dora().ura(),
            payment.dora().kan(),
            payment.dora().aka()),
        payment.yakumanCount(),
        payment.seatDelta(),
        payment.riichiSticksAwarded());
  }

  private static TableEventView view(TableEvent event) {
    return new TableEventView(event.sequence(), event.type(), event.payload());
  }

  private static String lower(String value) {
    return value.toLowerCase(Locale.ROOT);
  }

  record CreateTableRequest(@NotBlank String rulesetKey, @NotNull Table.Format format) {}

  record RevertRequest(@Min(0) long toVersion, @NotBlank String reason) {}

  record RiichiRequest(@Min(0) @Max(3) int seat) {}

  record DrawRequest(@NotNull DrawType type, Set<@Min(0) @Max(3) Integer> tenpaiSeats) {}

  enum DrawType {
    EXHAUSTIVE,
    ABORTIVE
  }

  record DoraRequest(@Min(0) int ordinary, @Min(0) int ura, @Min(0) int kan, @Min(0) int aka) {}

  /**
   * Ручной ввод результата. {@code expectedVersion} читает только подтверждение, превью его
   * игнорирует.
   */
  record HandRequest(
      @NotEmpty List<@NotBlank String> tiles,
      List<@NotBlank String> melds,
      @NotBlank String winningTile,
      boolean tsumo,
      @Min(0) @Max(3) int winnerSeat,
      @Min(0) @Max(3) Integer discarderSeat,
      List<@NotBlank String> doraIndicators,
      List<@NotBlank String> uraIndicators,
      DoraRequest dora,
      Set<@NotBlank String> declaredYaku,
      Long expectedVersion) {

    WinningHand toHand() {
      List<String> sets = melds == null ? List.of() : melds;
      Set<String> declared = declaredYaku == null ? Set.of() : declaredYaku;

      // Индикаторы — предпочтительный путь: дору считает сервер. Готовые счётчики
      // остаются для совместимости и игнорируются, если индикаторы заданы.
      if (doraIndicators != null) {
        return WinningHand.withIndicators(
            tiles,
            sets,
            winningTile,
            tsumo,
            doraIndicators,
            uraIndicators == null ? List.of() : uraIndicators,
            declared);
      }

      DoraRequest counts = dora == null ? new DoraRequest(0, 0, 0, 0) : dora;
      return new WinningHand(
          tiles,
          sets,
          winningTile,
          tsumo,
          new WinningHand.Dora(counts.ordinary(), counts.ura(), counts.kan(), counts.aka()),
          declared);
    }
  }

  record TableView(
      UUID id,
      String state,
      String format,
      String rulesetId,
      List<UUID> participants,
      List<UUID> seats,
      List<Integer> scores,
      int dealerSeat,
      String roundWind,
      int handNumber,
      int honba,
      int riichiSticks,
      int handsPlayed,
      List<Integer> vacantSeats,
      long version,
      Map<String, String> nicknames,
      String updatedAt,
      String finishedReason,
      List<PlacementView> standings) {}

  record PlacementView(int seat, int place, int points, int uma, int result) {}

  record TableSummaryView(
      UUID id, String state, String format, int handsPlayed, String updatedAt) {}

  record HandPaymentView(
      int han,
      int fu,
      List<String> yaku,
      DoraRequest dora,
      int yakumanCount,
      List<Integer> seatDelta,
      int riichiSticksAwarded) {}

  record TableStatsView(
      int lobbies,
      int active,
      int stale,
      int completed,
      int abandonedEarly,
      int abandonedLobby,
      double completionRate,
      double handsPerCompletedGame,
      long medianMinutes,
      long p90Minutes) {}

  record WonHandView(UUID tableId, long sequence, int han, int fu, List<String> yaku, String at) {}

  record TableEventView(long sequence, String type, Map<String, Object> payload) {}
}
