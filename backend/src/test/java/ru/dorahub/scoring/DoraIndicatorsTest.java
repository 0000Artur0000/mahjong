package ru.dorahub.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** §11: индикатор показывает предыдущий тайл, дора — следующий за ним в цикле. */
class DoraIndicatorsTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    // внутри масти 1→…→9→1
    "1m,2m",
    "8m,9m",
    "9m,1m",
    "1p,2p",
    "9p,1p",
    "1s,2s",
    "9s,1s",
    // ветра: восток→юг→запад→север→восток
    "1z,2z",
    "2z,3z",
    "3z,4z",
    "4z,1z",
    // драконы: красный→белый→зелёный→красный, то есть 中→白→發→中
    "7z,5z",
    "5z,6z",
    "6z,7z",
    // красная пятёрка как индикатор — это пятёрка
    "0m,6m",
    "0p,6p",
    "0s,6s",
  })
  void followsTheCycle(String indicator, String dora) {
    assertThat(DoraIndicators.dora(indicator)).isEqualTo(dora);
  }

  @Test
  void countsOrdinaryDoraAcrossConcealedTilesAndMelds() {
    // индикатор 2m -> дора 3m; в руке две 3m закрытых и одна в сете
    var dora =
        DoraIndicators.count(
            List.of("3m", "3m", "1p", "2p"), List.of("333m"), List.of("2m"), List.of());

    assertThat(dora.ordinary()).isEqualTo(5);
    assertThat(dora.total()).isEqualTo(5);
  }

  @Test
  void separatesKanDoraFromOrdinary() {
    // первый индикатор базовый, остальные открыты канами
    var dora =
        DoraIndicators.count(
            List.of("3m", "3m", "5p", "9s"), List.of(), List.of("2m", "4p"), List.of());

    assertThat(dora.ordinary()).isEqualTo(2);
    assertThat(dora.kan()).isEqualTo(1);
  }

  @Test
  void countsUraSeparately() {
    var dora =
        DoraIndicators.count(List.of("3m", "9s", "9s"), List.of(), List.of("2m"), List.of("8s"));

    assertThat(dora.ordinary()).isEqualTo(1);
    assertThat(dora.ura()).isEqualTo(2);
  }

  /** §11: красная пятёрка сама даёт хан и одновременно может быть обычной дорой. */
  @Test
  void redFiveCountsAsBothAkaAndOrdinaryDora() {
    var dora = DoraIndicators.count(List.of("0m", "1p"), List.of(), List.of("4m"), List.of());

    assertThat(dora.aka()).isEqualTo(1);
    assertThat(dora.ordinary()).as("дора 5m, красная пятёрка — тоже 5m").isEqualTo(1);
    assertThat(dora.total()).isEqualTo(2);
  }

  /** §11: если на один тайл указывают несколько индикаторов, бонусы складываются. */
  @Test
  void addsUpWhenSeveralIndicatorsPointAtTheSameTile() {
    var dora = DoraIndicators.count(List.of("3m", "3m"), List.of(), List.of("2m", "2m"), List.of());

    assertThat(dora.ordinary()).isEqualTo(2);
    assertThat(dora.kan()).isEqualTo(2);
    assertThat(dora.total()).isEqualTo(4);
  }

  @Test
  void countsNothingWithoutIndicators() {
    assertThat(DoraIndicators.count(List.of("3m"), List.of(), List.of(), List.of()).total())
        .isZero();
  }

  @Test
  void rejectsUnknownTile() {
    assertThatThrownBy(() -> DoraIndicators.dora("8z"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DoraIndicators.dora("1x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void buildsHandFromIndicators() {
    var hand =
        WinningHand.withIndicators(
            List.of("3m", "3m", "0p"), List.of(), "3m", false, List.of("2m"), List.of(), Set.of());

    assertThat(hand.dora().ordinary()).isEqualTo(2);
    assertThat(hand.dora().aka()).isEqualTo(1);
  }

  /** §11: ура открывается только победителю с объявленным риичи. */
  @Test
  void rejectsUraWithoutRiichi() {
    assertThatThrownBy(
            () ->
                WinningHand.withIndicators(
                    List.of("3m"), List.of(), "3m", false, List.of("2m"), List.of("8s"), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("риичи");
  }

  @Test
  void acceptsUraWithRiichi() {
    var hand =
        WinningHand.withIndicators(
            List.of("9s"), List.of(), "9s", false, List.of("2m"), List.of("8s"), Set.of("Richi"));

    assertThat(hand.dora().ura()).isEqualTo(1);
  }

  @Test
  void rejectsMoreIndicatorsThanKansAllow() {
    assertThatThrownBy(
            () ->
                WinningHand.withIndicators(
                    List.of("3m"),
                    List.of(),
                    "3m",
                    false,
                    List.of("1m", "2m", "3m", "4m", "5m", "6m"),
                    List.of(),
                    Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMoreUraThanDoraIndicators() {
    assertThatThrownBy(
            () ->
                WinningHand.withIndicators(
                    List.of("3m"),
                    List.of(),
                    "3m",
                    false,
                    List.of("1m"),
                    List.of("2m", "3m"),
                    Set.of("Richi")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
