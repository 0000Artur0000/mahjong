package ru.dorahub.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import ru.dorahub.rules.RulesetSnapshot;
import ru.dorahub.rules.Rulesets;
import ru.dorahub.scoring.HandPayment;
import ru.dorahub.scoring.ScoreEngine;
import ru.dorahub.scoring.Wind;
import ru.dorahub.scoring.WinningHand;
import ru.dorahub.tables.Table.Format;
import ru.dorahub.tables.Table.State;

/** Полный цикл раздачи: ручной ввод, превью, подтверждение, продвижение партии. */
class HandResultsTest {

  private static final RulesetSnapshot RRC = new Rulesets().require("rrc-ru");

  /** Саншоку закрытым роном: 2 хан 40 фу, 2600 с не-дилера. */
  private static final WinningHand SANSHOKU = hand("123m456m123p123s99s", "9s");

  /** Пинфу + таньяо + цумо: 3 хан 20 фу. */
  private static final WinningHand PINFU_TSUMO =
      new WinningHand(
          tiles("234m567m234p567p55s"), List.of(), "7p", true, WinningHand.Dora.NONE, Set.of());

  private final HandResults handResults = new HandResults(new ScoreEngine());
  private Table table;

  @BeforeEach
  void startTable() {
    table = Table.create(UUID.randomUUID(), RRC, Format.HANCHAN, 42L, UUID.randomUUID());
    for (int player = 0; player < 3; player++) {
      table.join(UUID.randomUUID());
    }
    table.start();
  }

  @Test
  void previewDoesNotTouchTheTable() {
    long version = table.version();
    List<Integer> scores = table.scores();

    HandPayment payment = handResults.preview(table, SANSHOKU, 1, 2);

    assertThat(payment.seatDelta()).containsExactly(0, 2600, -2600, 0);
    assertThat(table.version()).isEqualTo(version);
    assertThat(table.scores()).isEqualTo(scores);
    assertThat(table.honba()).isZero();
  }

  @Test
  void previewIsRepeatable() {
    HandPayment first = handResults.preview(table, SANSHOKU, 1, 2);
    HandPayment second = handResults.preview(table, SANSHOKU, 1, 2);

    assertThat(first).isEqualTo(second);
  }

  @Test
  void confirmAppliesPaymentToScores() {
    handResults.confirm(table, SANSHOKU, 1, 2, table.version());

    assertThat(table.scores()).containsExactly(30000, 32600, 27400, 30000);
    assertThat(table.handsPlayed()).isEqualTo(1);
  }

  /** Победа не-дилера: хонба обнуляется, дилерство уходит дальше. */
  @Test
  void nonDealerWinRotatesDealer() {
    handResults.confirm(table, SANSHOKU, 1, 2, table.version());

    assertThat(table.dealerSeat()).isEqualTo(1);
    assertThat(table.honba()).isZero();
    assertThat(table.handNumber()).isEqualTo(2);
    assertThat(table.roundWind()).isEqualTo(Wind.EAST);
  }

  /** Ренчан: дилер победил, остаётся дилером, хонба растёт. */
  @Test
  void dealerWinKeepsDealerAndAddsHonba() {
    handResults.confirm(table, SANSHOKU, 0, 2, table.version());

    assertThat(table.dealerSeat()).isZero();
    assertThat(table.handNumber()).isEqualTo(1);
    assertThat(table.honba()).isEqualTo(1);
  }

  /** §13: следующая раздача уже считается с хонбой. */
  @Test
  void honbaIsChargedOnTheNextHand() {
    handResults.confirm(table, SANSHOKU, 0, 2, table.version());
    HandPayment second = handResults.preview(table, SANSHOKU, 1, 2);

    assertThat(second.seatDelta()).containsExactly(0, 2900, -2900, 0);
  }

  /** Ставки риичи уходят победителю и снимаются со стола. */
  @Test
  void winnerCollectsRiichiSticks() {
    table.declareRiichi(1);
    table.declareRiichi(2);
    assertThat(table.scores()).containsExactly(30000, 29000, 29000, 30000);
    assertThat(table.riichiSticks()).isEqualTo(2);

    handResults.confirm(table, SANSHOKU, 1, 2, table.version());

    // 29000 + 2600 выплата + 2000 ставки
    assertThat(table.scores()).containsExactly(30000, 33600, 26400, 30000);
    assertThat(table.riichiSticks()).isZero();
  }

  @Test
  void rejectsRiichiWithoutEnoughPoints() {
    for (int hand = 0; hand < 30; hand++) {
      if (table.state() == State.ACTIVE && table.scores().get(3) >= 1000) {
        table.declareRiichi(3);
      }
    }

    assertThat(table.scores().get(3)).isLessThan(1000);
    assertThatThrownBy(() -> table.declareRiichi(3))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("недостаточно очков");
  }

  @Test
  void rejectsConfirmOnStaleVersion() {
    long stale = table.version();
    table.declareRiichi(1);

    assertThatThrownBy(() -> handResults.confirm(table, SANSHOKU, 1, 2, stale))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  /** Повторная отправка того же подтверждения не начисляет очки дважды. */
  @Test
  void doesNotApplyTheSameConfirmationTwice() {
    long version = table.version();
    handResults.confirm(table, SANSHOKU, 1, 2, version);
    List<Integer> afterFirst = table.scores();

    assertThatThrownBy(() -> handResults.confirm(table, SANSHOKU, 1, 2, version))
        .isInstanceOf(OptimisticLockingFailureException.class);

    assertThat(table.scores()).isEqualTo(afterFirst);
    assertThat(table.handsPlayed()).isEqualTo(1);
  }

  /** Ханчан: восемь побед не-дилера проходят восточный и южный раунды и закрывают стол. */
  @Test
  void playsHanchanToTheEnd() {
    for (int hand = 0; hand < 8; hand++) {
      assertThat(table.state()).as("раздача %s", hand + 1).isEqualTo(State.ACTIVE);
      int winner = (table.dealerSeat() + 1) % 4;
      int discarder = (table.dealerSeat() + 2) % 4;
      handResults.confirm(table, SANSHOKU, winner, discarder, table.version());
    }

    assertThat(table.state()).isEqualTo(State.FINISHED);
    // Только доигранная до конца формата партия идёт в зачёт рейтинга.
    assertThat(table.countsForRating()).isTrue();
    assertThat(table.handsPlayed()).isEqualTo(8);
    assertThat(table.scores().stream().mapToInt(Integer::intValue).sum()).isEqualTo(120000);
  }

  @Test
  void tonpuusenEndsAfterEastRound() {
    Table east = Table.create(UUID.randomUUID(), RRC, Format.TONPUUSEN, 1L, UUID.randomUUID());
    for (int player = 0; player < 3; player++) {
      east.join(UUID.randomUUID());
    }
    east.start();

    for (int hand = 0; hand < 4; hand++) {
      int winner = (east.dealerSeat() + 1) % 4;
      handResults.confirm(east, SANSHOKU, winner, (east.dealerSeat() + 2) % 4, east.version());
    }

    assertThat(east.state()).isEqualTo(State.FINISHED);
    assertThat(east.roundWind()).isEqualTo(Wind.EAST);
  }

  @Test
  void confirmsTsumoWithoutDiscarder() {
    handResults.confirm(table, PINFU_TSUMO, 2, null, table.version());

    assertThat(table.scores()).containsExactly(28700, 29300, 32700, 29300);
  }

  @Test
  void rejectsAnyHandAfterTableFinished() {
    table.finish(Table.FinishReason.EARLY);

    assertThatThrownBy(() -> handResults.preview(table, SANSHOKU, 1, 2))
        .isInstanceOf(IllegalStateException.class);
  }

  private static WinningHand hand(String tiles, String winningTile) {
    return new WinningHand(
        tiles(tiles), List.of(), winningTile, false, WinningHand.Dora.NONE, Set.of());
  }

  private static List<String> tiles(String compact) {
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
