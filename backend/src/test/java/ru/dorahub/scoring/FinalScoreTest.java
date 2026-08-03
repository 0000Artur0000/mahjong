package ru.dorahub.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.dorahub.rules.RulesetSnapshot;
import ru.dorahub.rules.Rulesets;
import ru.dorahub.scoring.FinalScore.Placement;

/** §14: результат = игровые очки − возвратные очки + ума + ока. */
class FinalScoreTest {

  private static final RulesetSnapshot RRC = new Rulesets().require("rrc-ru");

  @Test
  void appliesUmaByPlace() {
    List<Placement> table = FinalScore.of(List.of(40000, 32000, 28000, 20000), RRC);

    assertThat(table).extracting(Placement::seat).containsExactly(0, 1, 2, 3);
    assertThat(table).extracting(Placement::place).containsExactly(1, 2, 3, 4);
    assertThat(table).extracting(Placement::uma).containsExactly(15000, 5000, -5000, -15000);
    assertThat(table).extracting(Placement::result).containsExactly(25000, 7000, -7000, -25000);
  }

  @Test
  void ordersByPointsNotBySeat() {
    List<Placement> table = FinalScore.of(List.of(20000, 40000, 28000, 32000), RRC);

    assertThat(table).extracting(Placement::seat).containsExactly(1, 3, 2, 0);
  }

  /** §14: связанные места делят одно место, а их ума складывается и делится поровну. */
  @Test
  void splitsUmaBetweenTiedPlaces() {
    List<Placement> table = FinalScore.of(List.of(40000, 30000, 30000, 20000), RRC);

    assertThat(table).extracting(Placement::place).containsExactly(1, 2, 2, 4);
    // второе и третье место: (5000 + (−5000)) / 2 = 0
    assertThat(table).extracting(Placement::uma).containsExactly(15000, 0, 0, -15000);
  }

  @Test
  void splitsUmaBetweenAllFourTied() {
    List<Placement> table = FinalScore.of(List.of(30000, 30000, 30000, 30000), RRC);

    assertThat(table).extracting(Placement::place).containsExactly(1, 1, 1, 1);
    assertThat(table).extracting(Placement::uma).containsExactly(0, 0, 0, 0);
    assertThat(table).extracting(Placement::result).containsExactly(0, 0, 0, 0);
  }

  /** §14: при возвратных 30 000 и оке 0 сумма итогов нулевая. */
  @Test
  void resultsSumToZero() {
    for (List<Integer> points :
        List.of(
            List.of(40000, 32000, 28000, 20000),
            List.of(55300, 31200, 20500, 13000),
            List.of(40000, 30000, 30000, 20000),
            List.of(70000, 30000, 20000, 0))) {
      assertThat(FinalScore.of(points, RRC).stream().mapToInt(Placement::result).sum())
          .as("сумма итогов для %s", points)
          .isZero();
    }
  }
}
