package ru.dorahub.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.dorahub.rules.RulesetSnapshot;
import ru.dorahub.rules.Rulesets;
import ru.dorahub.scoring.FinalScore.Placement;
import ru.dorahub.scoring.Wind;
import ru.dorahub.tables.Table.Format;
import ru.dorahub.tables.Table.State;

/** Ничьи §12, продолжение партии §13 и итоги §14. */
class TableDrawTest {

  private static final RulesetSnapshot RRC = new Rulesets().require("rrc-ru");

  private Table table;

  @BeforeEach
  void startTable() {
    table = Table.create(UUID.randomUUID(), RRC, Format.HANCHAN, 42L, UUID.randomUUID());
    for (int player = 0; player < 3; player++) {
      table.join(UUID.randomUUID());
    }
    table.start();
  }

  /** §12.1: общая выплата нотен-пенальти всегда 3000. */
  static java.util.stream.Stream<Arguments> notenPayments() {
    return java.util.stream.Stream.of(
        Arguments.of(Set.of(), List.of(30000, 30000, 30000, 30000)),
        Arguments.of(Set.of(0), List.of(33000, 29000, 29000, 29000)),
        Arguments.of(Set.of(0, 1), List.of(31500, 31500, 28500, 28500)),
        Arguments.of(Set.of(0, 1, 2), List.of(31000, 31000, 31000, 27000)),
        Arguments.of(Set.of(0, 1, 2, 3), List.of(30000, 30000, 30000, 30000)));
  }

  @ParameterizedTest(name = "темпай у {0}")
  @MethodSource("notenPayments")
  void paysNotenPenalty(Set<Integer> tenpai, List<Integer> expected) {
    table.applyExhaustiveDraw(tenpai);

    assertThat(table.scores()).isEqualTo(expected);
    assertThat(table.scores().stream().mapToInt(Integer::intValue).sum()).isEqualTo(120000);
  }

  /** §13: дилер с темпаем сохраняет дилерство, хонба растёт в любом случае. */
  @Test
  void dealerKeepsDealershipWhenTenpai() {
    table.applyExhaustiveDraw(Set.of(0, 2));

    assertThat(table.dealerSeat()).isZero();
    assertThat(table.handNumber()).isEqualTo(1);
    assertThat(table.honba()).isEqualTo(1);
  }

  @Test
  void dealerLosesDealershipWhenNoten() {
    table.applyExhaustiveDraw(Set.of(1, 2));

    assertThat(table.dealerSeat()).isEqualTo(1);
    assertThat(table.handNumber()).isEqualTo(2);
    assertThat(table.honba()).isEqualTo(1);
  }

  /** §12.1: ставки риичи при ничьей остаются на столе. */
  @Test
  void riichiSticksStayOnTheTableAfterDraw() {
    table.declareRiichi(1);
    table.applyExhaustiveDraw(Set.of(1));

    assertThat(table.riichiSticks()).isEqualTo(1);
  }

  /** §12.2: досрочная ничья не меняет очки, дилер остаётся, хонба растёт. */
  @Test
  void abortiveDrawKeepsScoresAndDealer() {
    table.applyAbortiveDraw();

    assertThat(table.scores()).containsExactly(30000, 30000, 30000, 30000);
    assertThat(table.dealerSeat()).isZero();
    assertThat(table.handNumber()).isEqualTo(1);
    assertThat(table.honba()).isEqualTo(1);
    assertThat(table.handsPlayed()).isEqualTo(1);
  }

  /** §1.1: пресет без досрочных ничьих обязан отклонить команду. */
  @Test
  void rejectsAbortiveDrawWhenRulesetForbidsIt() {
    Table strict = tableWithoutAbortiveDraws();

    assertThatThrownBy(strict::applyAbortiveDraw)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("не допускает");
  }

  /** §13: оставшиеся ставки в конце партии получает лидер. */
  @Test
  void leaderCollectsLeftoverSticksAtTheEnd() {
    table.declareRiichi(2);
    table.applyExhaustiveDraw(Set.of(2));
    assertThat(table.riichiSticks()).isEqualTo(1);

    table.finish(Table.FinishReason.EARLY);

    // место 2 объявляло риичи (−1000) и было единственным в темпае (+3000), значит оно лидер
    assertThat(table.scores()).containsExactly(29000, 29000, 33000, 29000);
    assertThat(table.riichiSticks()).isZero();
  }

  @Test
  void splitsLeftoverSticksBetweenTiedLeaders() {
    table.declareRiichi(0);
    table.declareRiichi(1);
    table.applyExhaustiveDraw(Set.of(0, 1));
    // 0 и 1: −1000 ставка +1500 темпай = 30500; 2 и 3: −1500 = 28500
    assertThat(table.scores()).containsExactly(30500, 30500, 28500, 28500);

    table.finish(Table.FinishReason.EARLY);

    assertThat(table.scores()).containsExactly(31500, 31500, 28500, 28500);
    assertThat(table.riichiSticks()).isZero();
  }

  @Test
  void rejectsDrawOnTableThatIsNotRunning() {
    table.finish(Table.FinishReason.EARLY);

    assertThatThrownBy(() -> table.applyExhaustiveDraw(Set.of(0)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsTenpaiSeatOutOfRange() {
    assertThatThrownBy(() -> table.applyExhaustiveDraw(Set.of(0, 4)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("вне диапазона");
  }

  /** Ничьи с нотен-дилером доводят ханчан до конца так же, как победы. */
  @Test
  void drawsAdvanceTheGameToTheEnd() {
    for (int hand = 0; hand < 8; hand++) {
      assertThat(table.state()).as("раздача %s", hand + 1).isEqualTo(State.ACTIVE);
      int tenpai = (table.dealerSeat() + 1) % 4;
      table.applyExhaustiveDraw(Set.of(tenpai));
    }

    assertThat(table.state()).isEqualTo(State.FINISHED);
    assertThat(table.roundWind()).isEqualTo(Wind.SOUTH);
    assertThat(table.honba()).isEqualTo(8);
  }

  /** §14: итоговая таблица считает уму от игровых очков. */
  @Test
  void producesFinalStandings() {
    table.applyExhaustiveDraw(Set.of(0));
    table.finish(Table.FinishReason.EARLY);

    List<Placement> standings = table.standings();

    assertThat(standings).extracting(Placement::place).containsExactly(1, 2, 2, 2);
    assertThat(standings.get(0).points()).isEqualTo(33000);
    assertThat(standings.stream().mapToInt(Placement::result).sum()).isZero();
  }

  @Test
  void rejectsStandingsWhileGameIsRunning() {
    assertThatThrownBy(table::standings)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("завершённого");
  }

  private static Table tableWithoutAbortiveDraws() {
    RulesetSnapshot ema =
        new RulesetSnapshot(
            "test-no-abort",
            "1.0",
            "Без досрочных ничьих",
            RRC.startingPoints(),
            RRC.returnPoints(),
            RRC.uma(),
            RRC.oka(),
            true,
            true,
            false,
            false,
            false,
            2,
            false,
            false,
            false,
            false,
            false,
            -20000,
            "test");
    Table table = Table.create(UUID.randomUUID(), ema, Format.HANCHAN, 1L, UUID.randomUUID());
    for (int player = 0; player < 3; player++) {
      table.join(UUID.randomUUID());
    }
    table.start();
    return table;
  }
}
