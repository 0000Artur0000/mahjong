package ru.dorahub.scoring.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Сверка mahjong-utils с PRAVILA_RIICHI_RU.md, пресет RRC-RU.
 *
 * <p>Тест ломается, если новая версия библиотеки начнёт считать иначе. Ожидаемые значения взяты из
 * §15.5 (таблица выплат), §15.4 (лимиты) и §15.2 (фу) правил, а не из поведения библиотеки.
 */
class MahjongUtilsGoldenTest {

  /** §15.5 и §15.4: хан, фу, рон и цумо для не-дилера и дилера. 0 — клетка «—» в таблице. */
  static Stream<Arguments> payoutTable() {
    return Stream.of(
        // хан, фу, рон не-дилера, цумо с дилера, цумо с не-дилера, рон дилера, цумо дилера
        Arguments.of(1, 30, 1000, 500, 300, 1500, 500),
        Arguments.of(1, 40, 1300, 700, 400, 2000, 700),
        Arguments.of(1, 50, 1600, 800, 400, 2400, 800),
        Arguments.of(1, 60, 2000, 1000, 500, 2900, 1000),
        Arguments.of(2, 20, 0, 700, 400, 0, 700),
        Arguments.of(2, 25, 1600, 0, 0, 2400, 0),
        Arguments.of(2, 30, 2000, 1000, 500, 2900, 1000),
        Arguments.of(2, 40, 2600, 1300, 700, 3900, 1300),
        Arguments.of(2, 50, 3200, 1600, 800, 4800, 1600),
        Arguments.of(2, 60, 3900, 2000, 1000, 5800, 2000),
        Arguments.of(3, 20, 0, 1300, 700, 0, 1300),
        Arguments.of(3, 25, 3200, 1600, 800, 4800, 1600),
        Arguments.of(3, 30, 3900, 2000, 1000, 5800, 2000),
        Arguments.of(3, 40, 5200, 2600, 1300, 7700, 2600),
        Arguments.of(3, 50, 6400, 3200, 1600, 9600, 3200),
        Arguments.of(3, 60, 7700, 3900, 2000, 11600, 3900),
        Arguments.of(4, 20, 0, 2600, 1300, 0, 2600),
        Arguments.of(4, 25, 6400, 3200, 1600, 9600, 3200),
        Arguments.of(4, 30, 7700, 3900, 2000, 11600, 3900),
        // §15.4: манган наступает по хан и по фу
        Arguments.of(4, 40, 8000, 4000, 2000, 12000, 4000),
        Arguments.of(3, 70, 8000, 4000, 2000, 12000, 4000),
        Arguments.of(5, 30, 8000, 4000, 2000, 12000, 4000),
        Arguments.of(6, 30, 12000, 6000, 3000, 18000, 6000), // ханеман
        Arguments.of(8, 30, 16000, 8000, 4000, 24000, 8000), // байман
        Arguments.of(11, 30, 24000, 12000, 6000, 36000, 12000), // санбайман
        Arguments.of(13, 30, 32000, 16000, 8000, 48000, 16000)); // казоэ-якуман
  }

  @ParameterizedTest(name = "{0} хан {1} фу")
  @MethodSource("payoutTable")
  void matchesPayoutTable(
      int han,
      int hu,
      int nonDealerRon,
      int tsumoFromDealer,
      int tsumoFromNonDealer,
      int dealerRon,
      int dealerTsumo) {
    var nonDealer = MahjongUtils.nonDealerPoints(han, hu, Map.of());
    var dealer = MahjongUtils.dealerPoints(han, hu, Map.of());

    if (nonDealerRon > 0) {
      assertThat(nonDealer.ron()).isEqualTo(nonDealerRon);
      assertThat(dealer.ron()).isEqualTo(dealerRon);
    }
    if (tsumoFromDealer > 0) {
      assertThat(nonDealer.tsumoFromDealer()).isEqualTo(tsumoFromDealer);
      assertThat(nonDealer.tsumoFromNonDealer()).isEqualTo(tsumoFromNonDealer);
      assertThat(dealer.tsumoFromEach()).isEqualTo(dealerTsumo);
    }
  }

  /** §15.2 и §16: разложение, яку и фу на эталонных руках. */
  static Stream<Arguments> hands() {
    return Stream.of(
        // пинфу + мензен цумо + таньяо: 20 фу без округления
        Arguments.of(
            "пинфу+цумо+таньяо",
            "234m567m234p567p55s",
            List.of(),
            "7p",
            true,
            List.of(),
            3,
            20,
            List.of("Pinhu", "Tsumo", "Tanyao")),
        // риичи объявляется вручную и приходит как extraYaku
        Arguments.of(
            "+риичи",
            "234m567m234p567p55s",
            List.of(),
            "7p",
            true,
            List.of("Richi"),
            4,
            20,
            List.of("Richi", "Pinhu", "Tsumo", "Tanyao")),
        // 20 + 10 закрытый рон + 8 закрытый пон терминалов + 2 танки = 40 фу
        Arguments.of(
            "риичи, закрытый рон, 40 фу",
            "111m234m567m234p99p",
            List.of(),
            "9p",
            false,
            List.of("Richi"),
            1,
            40,
            List.of("Richi")),
        // §15.2: чиитойцу всегда ровно 25 фу
        Arguments.of(
            "чиитойцу",
            "11m44m77m22p55p88p33s",
            List.of(),
            "3s",
            false,
            List.of(),
            2,
            25,
            List.of("Chitoi")),
        // кокуши на 13 сторон — двойной якуман (hasComplexYakuman включён по умолчанию)
        Arguments.of(
            "кокуши на 13 сторон",
            "1m9m1p9p1s9s1z1z2z3z4z5z6z7z",
            List.of(),
            "1z",
            false,
            List.of(),
            26,
            0,
            List.of("KokushiThirteenWaiting")),
        // 20 + 2 открытый пон простых = 22 -> 30 фу
        Arguments.of(
            "открытый таньяо",
            "234m567m456s22s",
            List.of("333p"),
            "6s",
            false,
            List.of(),
            1,
            30,
            List.of("Tanyao")),
        // 20 + 10 закрытый рон + 2 танки = 32 -> 40 фу
        Arguments.of(
            "саншоку",
            "123m456m123p123s99s",
            List.of(),
            "9s",
            false,
            List.of(),
            2,
            40,
            List.of("Sanshoku")),
        // 20 + 4 открытый пон благородных = 24 -> 30 фу
        Arguments.of(
            "якухай хаку открытым поном",
            "234m567m23p99s",
            List.of("555z"),
            "4p",
            false,
            List.of(),
            1,
            30,
            List.of("Haku")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("hands")
  void matchesReferenceHands(
      String name,
      String tiles,
      List<String> furo,
      String agari,
      boolean tsumo,
      List<String> extraYaku,
      int expectedHan,
      int expectedFu,
      List<String> expectedYaku) {
    var hora = MahjongUtils.hora(args(tiles, furo, agari, tsumo, 0, extraYaku));

    assertThat(hora.han()).as("хан").isEqualTo(expectedHan);
    if (expectedFu > 0) {
      assertThat(hora.hu()).as("фу").isEqualTo(expectedFu);
    }
    assertThat(hora.yaku()).containsAll(expectedYaku);
  }

  @Test
  void countsDoraAsHan() {
    var withoutDora =
        MahjongUtils.hora(args("234m567m456s22s", List.of("333p"), "6s", false, 0, List.of()));
    var withDora =
        MahjongUtils.hora(args("234m567m456s22s", List.of("333p"), "6s", false, 2, List.of()));

    assertThat(withDora.han()).isEqualTo(withoutDora.han() + 2);
  }

  @Test
  void doraAloneDoesNotAllowWinning() {
    // Открытая рука без яку: три доры добавляют хан, но яку не появляется.
    var hora =
        MahjongUtils.hora(args("456p789s234s55m", List.of("123m"), "4s", false, 3, List.of()));

    assertThat(hora.yaku()).isEmpty();
  }

  @Test
  void rejectsInvalidHand() {
    assertThatThrownBy(() -> MahjongUtils.hora(args("123m", List.of(), "1m", false, 0, List.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * §15.4: EMA-2025 объявляет манганом и 4 хан 30 фу, и 3 хан 60 фу. Библиотека применяет кириаге
   * только к 4/30. Расхождение известно и закрывается слоем выплат Dorahub; тест зафиксирует
   * момент, когда библиотека это поправит.
   */
  @Test
  void kiriageManganCoversOnlyFourHanThirtyFu() {
    Map<String, Object> kiriage = Map.of("hasKiriageMangan", true);

    assertThat(MahjongUtils.nonDealerPoints(4, 30, kiriage).ron()).isEqualTo(8000);
    assertThat(MahjongUtils.nonDealerPoints(3, 60, kiriage).ron())
        .as("правила требуют 8000; библиотека даёт 7700 — компенсируется в слое выплат")
        .isEqualTo(7700);
    assertThat(MahjongUtils.nonDealerPoints(4, 30, Map.of()).ron())
        .as("RRC-RU без кириаге")
        .isEqualTo(7700);
  }

  private static Map<String, Object> args(
      String tiles,
      List<String> furo,
      String agari,
      boolean tsumo,
      int dora,
      List<String> extraYaku) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("tiles", expand(tiles));
    args.put("furo", furo);
    args.put("agari", agari);
    args.put("tsumo", tsumo);
    args.put("dora", dora);
    args.put("selfWind", "South");
    args.put("roundWind", "East");
    args.put("extraYaku", extraYaku);
    return args;
  }

  /** "234m55s" -> ["2m","3m","4m","5s","5s"]. */
  private static List<String> expand(String compact) {
    List<String> tiles = new ArrayList<>();
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
