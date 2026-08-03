package ru.dorahub.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RulesetsTest {

  private final Rulesets rulesets = new Rulesets();

  @Test
  void loadsRrcRuPreset() {
    RulesetSnapshot rrc = rulesets.require("rrc-ru");

    // Значения из таблицы §1.1 правил.
    assertThat(rrc.id()).isEqualTo("rrc-ru@1.0");
    assertThat(rrc.startingPoints()).isEqualTo(30000);
    assertThat(rrc.returnPoints()).isEqualTo(30000);
    assertThat(rrc.uma()).containsExactly(15000, 5000, -5000, -15000);
    assertThat(rrc.oka()).isZero();
    assertThat(rrc.openTanyao()).isTrue();
    assertThat(rrc.kiriageMangan()).isFalse();
    assertThat(rrc.kazoeYakuman()).isTrue();
    assertThat(rrc.stackYakuman()).isTrue();
    assertThat(rrc.complexYakumanCountsDouble()).isFalse();
    assertThat(rrc.doubleWindPairFu()).isEqualTo(4);
    assertThat(rrc.atamahane()).isFalse();
    assertThat(rrc.chomboPenalty()).isEqualTo(-20000);
  }

  @Test
  void computesStableChecksum() {
    assertThat(rulesets.require("rrc-ru").checksum())
        .hasSize(64)
        .isEqualTo(new Rulesets().require("rrc-ru").checksum());
  }

  @Test
  void rejectsUnknownKey() {
    assertThatThrownBy(() -> rulesets.require("нет-такого"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUmaThatDoesNotSumToZero() {
    assertThatThrownBy(() -> snapshotWithUma(List.of(15000, 5000, -5000, -10000)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("нулевой");
  }

  @Test
  void rejectsWrongNumberOfUmaValues() {
    assertThatThrownBy(() -> snapshotWithUma(List.of(15000, -15000)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void snapshotWithUma(List<Integer> uma) {
    new RulesetSnapshot(
        "test",
        "1.0",
        "Test",
        30000,
        30000,
        uma,
        0,
        true,
        false,
        true,
        true,
        false,
        4,
        false,
        false,
        true,
        false,
        false,
        -20000,
        "checksum");
  }
}
