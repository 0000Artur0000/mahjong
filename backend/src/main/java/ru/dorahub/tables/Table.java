package ru.dorahub.tables;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import ru.dorahub.rules.RulesetSnapshot;
import ru.dorahub.scoring.FinalScore;
import ru.dorahub.scoring.HandContext;
import ru.dorahub.scoring.HandPayment;
import ru.dorahub.scoring.Wind;

/**
 * Стол — единственный владелец состояния партии.
 *
 * <p>Очки, дилер, ветер раунда, хонба и ставки меняются только его командами: ни контроллер, ни
 * соседний модуль не пишут в них напрямую. Недопустимая команда бросает исключение и не оставляет
 * следа — состояние и {@link #version()} не меняются.
 *
 * <p>Правила партии фиксируются снимком в момент создания и дальше не перечитываются, поэтому
 * изменение пресета не влияет на уже идущую игру.
 *
 * <p>Ротация дилера и ветров, ничьи и конец формата приходят в A6; ввод и подтверждение раздачи — в
 * A5.
 */
public class Table {

  /** Ставка риичи в очках, §13. */
  private static final int RIICHI_STICK = 1000;

  /** Общая выплата нотен-пенальти при обычной ничьей, §12.1. */
  private static final int NOTEN_PENALTY = 3000;

  private final UUID id;
  private final RulesetSnapshot ruleset;
  private final Format format;
  private final long seatingSeed;
  private final List<UUID> participants = new ArrayList<>();
  private final List<UUID> seats = new ArrayList<>();
  private final List<Integer> scores = new ArrayList<>();

  private final List<TableEvent> pendingEvents = new ArrayList<>();

  private State state = State.LOBBY;
  private int dealerSeat;
  private Wind roundWind = Wind.EAST;
  private int handNumber = 1;
  private int honba;
  private int riichiSticks;
  private int handsPlayed;
  private final List<Integer> vacantSeats = new ArrayList<>();
  private boolean substituted;
  private FinishReason finishedReason;
  private long version;

  private Table(UUID id, RulesetSnapshot ruleset, Format format, long seatingSeed) {
    this.id = id;
    this.ruleset = ruleset;
    this.format = format;
    this.seatingSeed = seatingSeed;
  }

  /**
   * Новый стол в лобби.
   *
   * @param seatingSeed зерно жеребьёвки; хранится, чтобы посадку можно было воспроизвести при
   *     разборе спора
   */
  public static Table create(
      UUID id, RulesetSnapshot ruleset, Format format, long seatingSeed, UUID creator) {
    Table table = new Table(id, ruleset, format, seatingSeed);
    table.record("TABLE_CREATED", Map.of("format", format.name(), "ruleset", ruleset.id()));
    table.join(creator);
    return table;
  }

  /** Добавить участника в лобби. Повторное добавление того же игрока ничего не меняет. */
  public void join(UUID playerId) {
    require(state == State.LOBBY, "присоединиться можно только в лобби");
    if (participants.contains(playerId)) {
      return;
    }
    require(
        participants.size() < HandContext.SEATS, "за столом уже " + HandContext.SEATS + " игрока");
    participants.add(playerId);
    record("PLAYER_JOINED", Map.of("playerId", playerId.toString()));
  }

  /** Убрать участника из лобби. */
  public void leave(UUID playerId) {
    require(state == State.LOBBY, "выйти можно только из лобби");
    if (participants.remove(playerId)) {
      record("PLAYER_LEFT", Map.of("playerId", playerId.toString()));
    }
  }

  /**
   * Начать партию: жеребьёвка мест и первого дилера.
   *
   * <p>Посадка определяется только {@code seatingSeed}, поэтому повторный расчёт даёт тот же
   * результат. Первым дилером становится игрок, попавший на место 0.
   */
  public void start() {
    require(state == State.LOBBY, "партия уже начата или завершена");
    require(
        participants.size() == HandContext.SEATS,
        "нужно " + HandContext.SEATS + " игрока, сейчас " + participants.size());

    List<UUID> order = new ArrayList<>(participants);
    Collections.shuffle(order, new Random(seatingSeed));
    seats.addAll(order);
    for (int seat = 0; seat < HandContext.SEATS; seat++) {
      scores.add(ruleset.startingPoints());
    }

    dealerSeat = 0;
    roundWind = Wind.EAST;
    handNumber = 1;
    state = State.ACTIVE;
    record("GAME_STARTED", Map.of("seats", seats.stream().map(UUID::toString).toList()));
  }

  /**
   * Объявить риичи: ставка 1000 уходит со счёта игрока на стол.
   *
   * <p>Правила §10 говорят, что возможность поставить 1000 очков определяется пресетом, но ни один
   * из текущих пресетов её не переопределяет, поэтому требуем наличие ставки. Отмена риичи, когда
   * на объявляющий сброс объявили рон, делается ручной правкой модератора.
   */
  public void declareRiichi(int seat) {
    requirePlaying();
    requireSeatIndex(seat);
    require(scores.get(seat) >= RIICHI_STICK, "недостаточно очков для ставки риичи");
    scores.set(seat, scores.get(seat) - RIICHI_STICK);
    riichiSticks++;
    record("RIICHI_DECLARED", Map.of("seat", seat));
  }

  /**
   * Применить подтверждённый результат выигрышной раздачи.
   *
   * <p>Меняет очки, забирает ставки победителю и двигает партию: победа дилера продлевает его
   * дилерство и добавляет хонбу, победа не-дилера сбрасывает хонбу и передаёт дилерство дальше.
   * Последняя раздача последнего раунда формата завершает стол.
   *
   * <p>Ничьи, чомбо и нотен-выплаты приходят в A6.
   */
  public void applyWin(HandPayment payment, int winnerSeat) {
    requirePlaying();
    requireSeatIndex(winnerSeat);
    require(
        payment.riichiSticksAwarded() == riichiSticks,
        "результат посчитан для "
            + payment.riichiSticksAwarded()
            + " ставок, на столе "
            + riichiSticks);

    for (int seat = 0; seat < HandContext.SEATS; seat++) {
      scores.set(seat, scores.get(seat) + payment.seatDelta().get(seat));
    }
    scores.set(winnerSeat, scores.get(winnerSeat) + riichiSticks * RIICHI_STICK);
    riichiSticks = 0;
    handsPlayed++;

    record(
        "HAND_WON",
        Map.of(
            "winnerSeat", winnerSeat,
            // Место без игрока ничего не значит через месяц: за столом пересаживаются, а
            // архив рук ищется по человеку.
            "winnerAccount", seats.get(winnerSeat).toString(),
            "han", payment.han(),
            "fu", payment.fu(),
            "yaku", payment.yaku(),
            "seatDelta", payment.seatDelta(),
            "riichiSticks", payment.riichiSticksAwarded()));

    if (winnerSeat == dealerSeat) {
      honba++; // ренчан: дилер остаётся
    } else {
      honba = 0;
      advanceDealer();
    }
  }

  /**
   * Исчерпание стены — обычная ничья §12.1.
   *
   * <p>Нотен-пенальти в 3000 очков целиком переходит от игроков без темпая к игрокам с темпаем и
   * делится поровну на каждой стороне. При нуле и четырёх темпаях выплат нет. Ставки риичи остаются
   * на столе, хонба растёт, дилер сохраняет дилерство только с объявленным темпаем.
   *
   * <p>Правила §12.1 в строке «2 темпая» сформулированы как «каждый нотен платит каждому темпай по
   * 1500», что дало бы суммарные 6000. Источником истины взята строка «общая выплата нотен-пенальти
   * равна 3000» — она согласуется со всеми тремя строками таблицы.
   */
  public void applyExhaustiveDraw(Set<Integer> tenpaiSeats) {
    requirePlaying();
    tenpaiSeats.forEach(Table::requireSeatIndex);

    int tenpai = tenpaiSeats.size();
    if (tenpai > 0 && tenpai < HandContext.SEATS) {
      int perTenpai = NOTEN_PENALTY / tenpai;
      int perNoten = NOTEN_PENALTY / (HandContext.SEATS - tenpai);
      for (int seat = 0; seat < HandContext.SEATS; seat++) {
        int delta = tenpaiSeats.contains(seat) ? perTenpai : -perNoten;
        scores.set(seat, scores.get(seat) + delta);
      }
    }

    handsPlayed++;
    honba++;
    record("EXHAUSTIVE_DRAW", Map.of("tenpaiSeats", List.copyOf(tenpaiSeats)));
    if (!tenpaiSeats.contains(dealerSeat)) {
      advanceDealer();
    }
  }

  /**
   * Досрочная ничья §12.2: кюсюкюхай, четыре одинаковых первых ветра, четыре риичи или четыре кана
   * разных игроков.
   *
   * <p>Очки не меняются, ставки остаются на столе, дилер сохраняет дилерство, хонба растёт.
   * Конкретная причина записывается событием раздачи, а не состоянием стола.
   */
  public void applyAbortiveDraw() {
    requirePlaying();
    require(ruleset.abortiveDraws(), "пресет " + ruleset.id() + " не допускает досрочные ничьи");
    handsPlayed++;
    honba++;
    record("ABORTIVE_DRAW", Map.of());
  }

  /**
   * Завершить стол с указанием причины.
   *
   * <p>Причина не украшение: досчитанная до конца формата партия и брошенное лобби выглядят
   * одинаково по состоянию, а в рейтинг должна идти только первая. Плюс на вопрос «куда делся мой
   * стол» появляется ответ из журнала.
   */
  public void finish(FinishReason reason) {
    require(state != State.FINISHED, "стол уже завершён");
    state = State.FINISHED;
    finishedReason = reason;
    awardLeftoverSticks();
    record("TABLE_FINISHED", Map.of("handsPlayed", handsPlayed, "reason", reason.name()));
  }

  /**
   * Итоговая таблица мест с умой и окой.
   *
   * @throws IllegalStateException если партия ещё идёт
   */
  public List<FinalScore.Placement> standings() {
    require(state == State.FINISHED, "итоги доступны только у завершённого стола");
    require(!scores.isEmpty(), "партия не начиналась");
    return FinalScore.of(scores, ruleset);
  }

  private void advanceDealer() {
    dealerSeat = (dealerSeat + 1) % HandContext.SEATS;
    if (handNumber < HandContext.SEATS) {
      handNumber++;
      return;
    }
    if (roundWind == format.lastRoundWind()) {
      finish(FinishReason.COMPLETED);
      return;
    }
    roundWind = roundWind.next();
    handNumber = 1;
  }

  /**
   * §13: оставшиеся ставки получает лидер, при равенстве лидеров — поровну с округлением вниз.
   *
   * <p>Остаток от деления пропадает со стола, как и на живой игре.
   */
  private void awardLeftoverSticks() {
    if (riichiSticks == 0 || scores.isEmpty()) {
      return;
    }
    int best = scores.stream().mapToInt(Integer::intValue).max().orElseThrow();
    List<Integer> leaders =
        java.util.stream.IntStream.range(0, HandContext.SEATS)
            .filter(seat -> scores.get(seat) == best)
            .boxed()
            .toList();
    int share = riichiSticks * RIICHI_STICK / leaders.size();
    leaders.forEach(seat -> scores.set(seat, scores.get(seat) + share));
    riichiSticks = 0;
  }

  /**
   * Освободить место: игрок уходит посреди партии.
   *
   * <p>Стол не закрывается и не теряет очки — место становится пустым, и партия ждёт замену.
   * Уходящий перестаёт быть участником, иначе он не сможет сесть за другой стол.
   */
  public void leaveSeat(int seat) {
    requireActive();
    requireSeatIndex(seat);
    require(!vacantSeats.contains(seat), "место уже свободно");
    UUID leaving = seats.get(seat);
    participants.remove(leaving);
    vacantSeats.add(seat);
    record("SEAT_LEFT", Map.of("seat", seat, "playerId", leaving.toString()));
  }

  /**
   * Сесть на освободившееся место.
   *
   * <p>Партия с заменой перестаёт идти в рейтинг: за одно место играли двое, и приписать результат
   * кому-то одному значит соврать. Очки при этом сохраняются — доигранная партия остаётся правдой
   * про сам стол, а не про игроков.
   */
  public void takeSeat(int seat, UUID playerId) {
    requireActive();
    requireSeatIndex(seat);
    require(vacantSeats.contains(seat), "место занято");
    require(!participants.contains(playerId), "игрок уже за этим столом");
    UUID leaving = seats.get(seat);
    seats.set(seat, playerId);
    participants.add(playerId);
    vacantSeats.remove(Integer.valueOf(seat));
    substituted = true;
    record(
        "SEAT_TAKEN",
        Map.of("seat", seat, "playerId", playerId.toString(), "replaced", leaving.toString()));
  }

  /** Места, за которыми сейчас никто не сидит. */
  public List<Integer> vacantSeats() {
    return List.copyOf(vacantSeats);
  }

  /**
   * Проверить, что игрок за этим столом.
   *
   * <p>Полноценные роли — платформенные и клубные — приходят на этапе B. Пока действует минимальное
   * правило: партию меняют только её участники, и проверка живёт в агрегате, а не в контроллере.
   */
  public void requireParticipant(UUID playerId) {
    require(participants.contains(playerId), "игрок не участвует в этом столе");
  }

  /** Место игрока за столом. */
  public int seatOf(UUID playerId) {
    int seat = seats.indexOf(playerId);
    require(seat >= 0, "игрок не сидит за этим столом");
    return seat;
  }

  /** Ветер места. */
  public Wind windOf(int seat) {
    requireActive();
    return Wind.ofSeat(seat, dealerSeat);
  }

  /** Положение текущей раздачи для расчёта выплат. */
  public HandContext handContext(int winnerSeat, Integer discarderSeat) {
    requireActive();
    return new HandContext(roundWind, dealerSeat, winnerSeat, discarderSeat, honba, riichiSticks);
  }

  public UUID id() {
    return id;
  }

  public RulesetSnapshot ruleset() {
    return ruleset;
  }

  public Format format() {
    return format;
  }

  public long seatingSeed() {
    return seatingSeed;
  }

  public State state() {
    return state;
  }

  public List<UUID> participants() {
    return List.copyOf(participants);
  }

  public List<UUID> seats() {
    return List.copyOf(seats);
  }

  public List<Integer> scores() {
    return List.copyOf(scores);
  }

  public int dealerSeat() {
    return dealerSeat;
  }

  public Wind roundWind() {
    return roundWind;
  }

  public int handNumber() {
    return handNumber;
  }

  public int honba() {
    return honba;
  }

  public int riichiSticks() {
    return riichiSticks;
  }

  /** Сколько раздач уже подтверждено. Отличает сыгранную партию от брошенного лобби. */
  public int handsPlayed() {
    return handsPlayed;
  }

  /** Почему стол завершён; {@code null}, пока партия не закончена. */
  public FinishReason finishedReason() {
    return finishedReason;
  }

  /**
   * Идёт ли партия в зачёт: только доигранная до конца формата и без замен.
   *
   * <p>За место с заменой играли двое; приписать результат кому-то одному нельзя, а делить его по
   * раздачам — это другая задача, и она пока не стоит.
   */
  public boolean countsForRating() {
    return finishedReason == FinishReason.COMPLETED && !substituted;
  }

  /** Версия агрегата для оптимистичной блокировки: растёт на каждой принятой команде. */
  public long version() {
    return version;
  }

  /**
   * Записать событие и продвинуть версию.
   *
   * <p>Единственное место, где растёт {@link #version()}: события и версия не могут разойтись, а
   * отклонённая команда до этого метода не доходит и следа не оставляет. Номер события совпадает с
   * версией агрегата после команды, поэтому последовательность строго монотонна.
   */
  private void record(String type, Map<String, Object> payload) {
    version++;
    pendingEvents.add(new TableEvent(version, type, payload));
  }

  /** События, ещё не записанные в журнал. */
  public List<TableEvent> pendingEvents() {
    return List.copyOf(pendingEvents);
  }

  /** Забрать накопленные события: вызывается после успешной записи в журнал. */
  public List<TableEvent> drainPendingEvents() {
    List<TableEvent> drained = List.copyOf(pendingEvents);
    pendingEvents.clear();
    return drained;
  }

  /** Снимок изменяемого состояния для хранения. */
  public TableState snapshot() {
    return new TableState(
        participants,
        seats,
        scores,
        dealerSeat,
        roundWind,
        handNumber,
        honba,
        riichiSticks,
        handsPlayed,
        vacantSeats,
        substituted,
        finishedReason);
  }

  /** Восстановить стол из хранилища. Событий у восстановленного стола нет. */
  public static Table restore(
      UUID id,
      RulesetSnapshot ruleset,
      Format format,
      long seatingSeed,
      State state,
      TableState gameState,
      long version) {
    Table table = new Table(id, ruleset, format, seatingSeed);
    table.apply(state, gameState);
    table.version = version;
    return table;
  }

  /**
   * Вернуть партию к ранее существовавшему состоянию.
   *
   * <p>История не стирается: откат — это ещё одно событие поверх журнала, а не удаление старых.
   * Версия растёт, поэтому телефоны за столом увидят изменение обычным опросом, а подтверждение
   * раздачи, построенное на старой версии, отвалится с конфликтом.
   *
   * <p>Доигранную партию править можно: её результат снимается с лестницы встречным начислением, а
   * не молча. Возвращается она всегда в идущую — доиграть придётся заново.
   *
   * @param toVersion версия, к которой возвращаемся; идёт в журнал как повод
   * @param reason зачем — без него откат нельзя отличить от произвола
   */
  public void revertTo(State restoredState, TableState restored, long toVersion, String reason) {
    require(state != State.LOBBY, "в лобби откатывать нечего");
    require(restoredState == State.ACTIVE, "откатывать можно только к идущей партии");
    require(toVersion < version, "откат возможен только к прошлой версии");
    require(reason != null && !reason.isBlank(), "у отката должна быть причина");

    participants.clear();
    seats.clear();
    scores.clear();
    apply(restoredState, restored);
    record("TABLE_REVERTED", Map.of("toVersion", toVersion, "reason", reason));
  }

  private void apply(State restoredState, TableState gameState) {
    state = restoredState;
    participants.addAll(gameState.participants());
    seats.addAll(gameState.seats());
    scores.addAll(gameState.scores());
    dealerSeat = gameState.dealerSeat();
    roundWind = gameState.roundWind();
    handNumber = gameState.handNumber();
    honba = gameState.honba();
    riichiSticks = gameState.riichiSticks();
    handsPlayed = gameState.handsPlayed();
    vacantSeats.clear();
    vacantSeats.addAll(gameState.vacantSeats());
    substituted = gameState.substituted();
    finishedReason = gameState.finishedReason();
  }

  private void requireActive() {
    require(state == State.ACTIVE, "партия не идёт, текущее состояние " + state);
  }

  /**
   * Партия идёт и за столом все четверо.
   *
   * <p>С пустым местом раздачу вводить нельзя: победа или темпай отсутствующего игрока — это запись
   * о том, чего не было. Стол ждёт замену.
   */
  private void requirePlaying() {
    requireActive();
    require(vacantSeats.isEmpty(), "за столом пустое место — посадите игрока");
  }

  private static void requireSeatIndex(int seat) {
    require(seat >= 0 && seat < HandContext.SEATS, "место вне диапазона 0..3: " + seat);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  /** Почему партия закончилась. */
  public enum FinishReason {
    /** Доигран последний раунд формата — только такая партия идёт в рейтинг. */
    COMPLETED,
    /** Участник закрыл стол досрочно. */
    EARLY,
    /** Лобби так и не начало партию и закрыто автоматически. */
    ABANDONED_LOBBY
  }

  /** Состояния стола. Терминальное — {@code FINISHED}. */
  public enum State {
    LOBBY,
    ACTIVE,
    FINISHED
  }

  /** Формат партии. Произвольное число раздач появится вместе с клубными настройками. */
  public enum Format {
    /** Восточный и южный раунды. */
    HANCHAN(Wind.SOUTH),
    /** Только восточный раунд. */
    TONPUUSEN(Wind.EAST);

    private final Wind lastRoundWind;

    Format(Wind lastRoundWind) {
      this.lastRoundWind = lastRoundWind;
    }

    /** Ветер последнего раунда формата. */
    public Wind lastRoundWind() {
      return lastRoundWind;
    }
  }
}
