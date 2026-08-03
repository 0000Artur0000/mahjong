package ru.dorahub.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.dorahub.rules.RulesetSnapshot;
import ru.dorahub.rules.Rulesets;

/**
 * Выплаты за раздачу по RRC-RU. Ожидаемые суммы — из таблицы §15.5 и §13 правил.
 *
 * <p>Места: 0 — дилер, дальше по порядку хода.
 */
class ScoreEngineTest {

  private static final RulesetSnapshot RRC = new Rulesets().require("rrc-ru");

  private final ScoreEngine engine = new ScoreEngine();

  /** Саншоку закрытым роном: 2 хан 40 фу, не-дилер получает 2600 со сбросившего. */
  @Test
  void paysNonDealerRon() {
    HandPayment payment =
        engine.score(
            hand("123m456m123p123s99s", "9s", false),
            new HandContext(Wind.EAST, 0, 1, 2, 0, 0),
            RRC);

    assertThat(payment.han()).isEqualTo(2);
    assertThat(payment.fu()).isEqualTo(40);
    assertThat(payment.seatDelta()).containsExactly(0, 2600, -2600, 0);
  }

  /** Пинфу + цумо + таньяо у дилера: 3 хан 20 фу, по 1300 с каждого. */
  @Test
  void paysDealerTsumo() {
    HandPayment payment =
        engine.score(
            hand("234m567m234p567p55s", "7p", true),
            new HandContext(Wind.EAST, 0, 0, null, 0, 0),
            RRC);

    assertThat(payment.han()).isEqualTo(3);
    assertThat(payment.fu()).isEqualTo(20);
    assertThat(payment.seatDelta()).containsExactly(3900, -1300, -1300, -1300);
  }

  /** Цумо не-дилера: дилер платит больше остальных (§15.3). */
  @Test
  void splitsNonDealerTsumoBetweenDealerAndOthers() {
    HandPayment payment =
        engine.score(
            hand("234m567m234p567p55s", "7p", true),
            new HandContext(Wind.EAST, 0, 2, null, 0, 0),
            RRC);

    // 3 хан 20 фу для не-дилера: 700 с не-дилеров, 1300 с дилера
    assertThat(payment.seatDelta()).containsExactly(-1300, -700, 2700, -700);
  }

  /** §13: каждая хонба добавляет 300 к рону. */
  @Test
  void addsHonbaToRon() {
    HandPayment payment =
        engine.score(
            hand("123m456m123p123s99s", "9s", false),
            new HandContext(Wind.EAST, 0, 1, 2, 2, 0),
            RRC);

    assertThat(payment.seatDelta()).containsExactly(0, 2600 + 600, -(2600 + 600), 0);
  }

  /** §13: при цумо каждая хонба добавляет по 100 к каждой из трёх выплат. */
  @Test
  void addsHonbaToEachTsumoPayment() {
    HandPayment payment =
        engine.score(
            hand("234m567m234p567p55s", "7p", true),
            new HandContext(Wind.EAST, 0, 0, null, 2, 0),
            RRC);

    assertThat(payment.seatDelta()).containsExactly(3900 + 600, -1500, -1500, -1500);
  }

  /** Ставки риичи приходят со стола, а не от игроков, поэтому в дельты не входят. */
  @Test
  void reportsRiichiSticksSeparatelyFromDeltas() {
    HandPayment payment =
        engine.score(
            hand("123m456m123p123s99s", "9s", false),
            new HandContext(Wind.EAST, 0, 1, 2, 0, 3),
            RRC);

    assertThat(payment.riichiSticksAwarded()).isEqualTo(3);
    assertThat(payment.seatDelta()).containsExactly(0, 2600, -2600, 0);
  }

  /**
   * §16.5: в RRC-RU 13-стороннее кокуши остаётся одинарным якуманом. Библиотека сама отдаёт 26 хан,
   * поправка живёт здесь.
   */
  @Test
  void countsThirteenWaitKokushiAsSingleYakuman() {
    HandPayment payment =
        engine.score(
            hand("1m9m1p9p1s9s1z1z2z3z4z5z6z7z", "1z", false),
            new HandContext(Wind.EAST, 0, 1, 2, 0, 0),
            RRC);

    assertThat(payment.yakumanCount()).isEqualTo(1);
    assertThat(payment.han()).isEqualTo(13);
    assertThat(payment.seatDelta()).containsExactly(0, 32000, -32000, 0);
  }

  /**
   * §16.5: разные якуманы складываются. Дайсууши здесь совпадает с сууанко-танки — это два
   * одинарных якумана, то есть 64 000. Таблица выплат библиотеки упирается в 32 000.
   */
  @Test
  void stacksDistinctYakuman() {
    HandPayment payment =
        engine.score(
            hand("1z1z1z2z2z2z3z3z3z4z4z4z5m5m", "5m", false),
            new HandContext(Wind.EAST, 0, 1, 2, 0, 0),
            RRC);

    assertThat(payment.yaku()).contains("Daisushi", "SuankoTanki");
    assertThat(payment.yakumanCount()).isEqualTo(2);
    assertThat(payment.han()).isEqualTo(26);
    assertThat(payment.seatDelta()).containsExactly(0, 64000, -64000, 0);
  }

  /** §15.4: кириаге-манган накрывает и 3 хан 60 фу — библиотека знает только 4 хан 30 фу. */
  @Test
  void appliesKiriageToThreeHanSixtyFu() {
    RulesetSnapshot withKiriage = kiriage(true);

    assertThat(ScoreEngine.payout(3, 60, 0, false, withKiriage).ron()).isEqualTo(8000);
    assertThat(ScoreEngine.payout(4, 30, 0, false, withKiriage).ron()).isEqualTo(8000);
    assertThat(ScoreEngine.payout(3, 60, 0, false, kiriage(false)).ron()).isEqualTo(7700);
  }

  @Test
  void rejectsRonWithoutDiscarder() {
    assertThatThrownBy(
            () ->
                engine.score(
                    hand("123m456m123p123s99s", "9s", false),
                    new HandContext(Wind.EAST, 0, 1, null, 0, 0),
                    RRC))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("сбросившего");
  }

  @Test
  void rejectsTsumoWithDiscarder() {
    assertThatThrownBy(
            () ->
                engine.score(
                    hand("234m567m234p567p55s", "7p", true),
                    new HandContext(Wind.EAST, 0, 1, 2, 0, 0),
                    RRC))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsYakuThatCannotBeDeclaredManually() {
    assertThatThrownBy(
            () ->
                new WinningHand(
                    List.of("1m"), List.of(), "1m", false, WinningHand.Dora.NONE, Set.of("Pinhu")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Pinhu");
  }

  /**
   * §3: без яку не выигрывают, а дора яку не даёт.
   *
   * <p>Библиотека считает такую руку молча — она отвечает, сколько это стоит, а не был ли выигрыш.
   * Проверка поймана на живом столе: рука 234m 567p 234s 789s + пара белых драконов роном
   * принималась и оплачивалась как 0 хан 40 фу.
   */
  @Test
  void refusesAHandWithoutYaku() {
    assertThatThrownBy(
            () ->
                engine.score(
                    hand("234m567p234s789s55z", "9s", false),
                    new HandContext(Wind.EAST, 0, 1, 2, 0, 0),
                    RRC))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("без яку");
  }

  /** Дора не спасает: три доры складываются в хан, но выигрышем рука не становится. */
  @Test
  void refusesAHandWhereOnlyDoraAddsHan() {
    WinningHand withDora =
        new WinningHand(
            expand("234m567p234s789s55z"),
            List.of(),
            "9s",
            false,
            new WinningHand.Dora(3, 0, 0, 0),
            Set.of());

    assertThatThrownBy(() -> engine.score(withDora, new HandContext(Wind.EAST, 0, 1, 2, 0, 0), RRC))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("без яку");
  }

  /** Одного объявленного риичи достаточно: отказ не должен задевать честные руки. */
  @Test
  void acceptsAHandWhoseOnlyYakuIsDeclaredRiichi() {
    WinningHand riichi =
        new WinningHand(
            expand("234m567p234s789s55z"),
            List.of(),
            "9s",
            false,
            WinningHand.Dora.NONE,
            Set.of("Richi"));

    HandPayment payment = engine.score(riichi, new HandContext(Wind.EAST, 0, 1, 2, 0, 0), RRC);

    assertThat(payment.yaku()).contains("Richi");
    assertThat(payment.han()).isEqualTo(1);
  }

  private static WinningHand hand(String tiles, String winningTile, boolean tsumo) {
    return new WinningHand(
        expand(tiles), List.of(), winningTile, tsumo, WinningHand.Dora.NONE, Set.of());
  }

  private static RulesetSnapshot kiriage(boolean enabled) {
    return new RulesetSnapshot(
        "test",
        "1.0",
        "Test",
        RRC.startingPoints(),
        RRC.returnPoints(),
        RRC.uma(),
        RRC.oka(),
        true,
        enabled,
        RRC.kazoeYakuman(),
        RRC.stackYakuman(),
        RRC.complexYakumanCountsDouble(),
        RRC.doubleWindPairFu(),
        false,
        false,
        true,
        false,
        false,
        -20000,
        "test");
  }

  /** "234m55s" -> ["2m","3m","4m","5s","5s"]. */
  private static List<String> expand(String compact) {
    List<String> tiles = new java.util.ArrayList<>();
    StringBuilder digits = new StringBuilder();
    for (char c : compact.toCharArray()) {
      if (Character.isDigit(c)) {
        digits.append(c);
      } else {
        for (int i = 0; i < digits.length(); i++) {
          tiles.add("" + digits.charAt(i) + c);
        }
        digits.setLength(0);
      }
    }
    return tiles;
  }
}
