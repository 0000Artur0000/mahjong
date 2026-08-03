package ru.dorahub.ratings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Эло по местам: главное свойство — лестница не создаёт и не теряет очки. */
class EloTest {

  private static final List<Integer> PLACES = List.of(1, 2, 3, 4);

  @Test
  void keepsTheSumAtZero() {
    assertThat(sum(Elo.deltas(List.of(1500, 1500, 1500, 1500), PLACES))).isZero();
    assertThat(sum(Elo.deltas(List.of(1900, 1500, 1400, 1200), PLACES))).isZero();
    assertThat(sum(Elo.deltas(List.of(1200, 1400, 1500, 1900), PLACES))).isZero();
    assertThat(sum(Elo.deltas(List.of(1000, 1001, 1002, 1003), List.of(1, 1, 3, 3)))).isZero();
  }

  /**
   * Округление по игроку в отдельности рисовало бы очки из воздуха; проверяем на дробном случае.
   */
  @Test
  void keepsTheSumAtZeroWhenDeltasAreFractional() {
    for (int leader = 1500; leader <= 2100; leader += 37) {
      var deltas = Elo.deltas(List.of(leader, 1490, 1483, 1477), PLACES);
      assertThat(sum(deltas)).as("рейтинг лидера %s", leader).isZero();
    }
  }

  @Test
  void rewardsTheWinnerAndPunishesTheLast() {
    var deltas = Elo.deltas(List.of(1500, 1500, 1500, 1500), PLACES);

    assertThat(deltas.get(0)).isPositive();
    assertThat(deltas.get(3)).isNegative();
    assertThat(deltas).isSortedAccordingTo((left, right) -> Integer.compare(right, left));
  }

  /** Победа над слабыми стоит дешевле, чем над равными: в этом весь смысл ожидания. */
  @Test
  void pricesTheWinByTheStrengthOfTheField() {
    int overEquals = Elo.deltas(List.of(1500, 1500, 1500, 1500), PLACES).getFirst();
    int overWeak = Elo.deltas(List.of(1900, 1300, 1300, 1300), PLACES).getFirst();
    int overStrong = Elo.deltas(List.of(1300, 1900, 1900, 1900), PLACES).getFirst();

    assertThat(overWeak).isLessThan(overEquals);
    assertThat(overStrong).isGreaterThan(overEquals);
  }

  /** Делённое место §14: обе стороны получают по половине очка. */
  @Test
  void splitsTiedPlaces() {
    var deltas = Elo.deltas(List.of(1500, 1500, 1500, 1500), List.of(1, 1, 3, 3));

    assertThat(deltas.get(0)).isEqualTo(deltas.get(1));
    assertThat(deltas.get(2)).isEqualTo(deltas.get(3));
    assertThat(deltas.get(0)).isPositive();
  }

  /** Полное равенство — рейтинг не двигается вовсе. */
  @Test
  void leavesRatingsAloneWhenEveryoneTies() {
    assertThat(Elo.deltas(List.of(1500, 1500, 1500, 1500), List.of(1, 1, 1, 1)))
        .containsExactly(0, 0, 0, 0);
  }

  @Test
  void rejectsMismatchedInput() {
    assertThatThrownBy(() -> Elo.deltas(List.of(1500, 1500), List.of(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Elo.deltas(List.of(1500), List.of(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static int sum(List<Integer> deltas) {
    return deltas.stream().mapToInt(Integer::intValue).sum();
  }
}
