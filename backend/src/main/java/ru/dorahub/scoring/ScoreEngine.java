package ru.dorahub.scoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import ru.dorahub.rules.RulesetSnapshot;
import ru.dorahub.scoring.internal.MahjongUtils;

/**
 * Детерминированный расчёт стоимости раздачи.
 *
 * <p>Разложение руки, яку, хан и фу считает mahjong-utils (см. docs/adr/0003). Здесь остаётся то,
 * чего в библиотеке нет: хонба, ставки риичи, нормализация якуманов под правила и распределение
 * выплат по местам.
 *
 * <p>Известные поправки к библиотеке:
 *
 * <ul>
 *   <li>кириаге-манган для 3 хан 60 фу — библиотека применяет кириаге только к 4 хан 30 фу;
 *   <li>стоимость нескольких сложившихся якуманов — таблица библиотеки упирается в один якуман;
 *   <li>особые ожидания (сууанко-танки, 13-стороннее кокуши, 9-стороннее чуурен) — библиотека
 *       считает их двойными независимо от флага, правила §16.5 в RRC-RU оставляют одинарными.
 * </ul>
 */
@Service
public class ScoreEngine {

  /** Имена якуманов в модели mahjong-utils. */
  private static final Set<String> YAKUMAN =
      Set.of(
          "Kokushi",
          "KokushiThirteenWaiting",
          "Suanko",
          "SuankoTanki",
          "Daisangen",
          "Tsuiso",
          "Shousushi",
          "Daisushi",
          "Lyuiso",
          "Chinroto",
          "Sukantsu",
          "Churen",
          "ChurenNineWaiting");

  private static final int YAKUMAN_HAN = 13;

  // Константы правил §13, а не настройка пресета: они одинаковы во всех известных ruleset.
  private static final int HONBA_PER_RON = 300;
  private static final int HONBA_PER_TSUMO_PAYMENT = 100;

  /** Стоимость раздачи и изменение очков по местам. */
  public HandPayment score(WinningHand hand, HandContext context, RulesetSnapshot ruleset) {
    if (hand.tsumo() != (context.discarderSeat() == null)) {
      throw new IllegalArgumentException(
          hand.tsumo()
              ? "при цумо сбросивший не указывается"
              : "при роне нужно указать сбросившего");
    }

    MahjongUtils.Hora hora = MahjongUtils.hora(horaArgs(hand, context, ruleset));
    // §3: для победы нужно хотя бы одно яку, а дора, кандора, урадора и акадора яку не
    // дают. Библиотека считает такую руку молча — она отвечает на вопрос «сколько это
    // стоит», а не «был ли выигрыш». Отказ живёт здесь: иначе за столом записывается
    // победа, которой по правилам не было.
    if (hora.yaku().isEmpty()) {
      throw new IllegalArgumentException(
          "рука без яку: победа невозможна. Дора яку не даёт — если было риичи, иппацу"
              + " или хайтей, отметьте их в разборе");
    }
    int units = yakumanUnits(hora, ruleset);
    int han = units > 0 ? YAKUMAN_HAN * units : hora.han();
    Payout payout = payout(han, hora.hu(), units, context.winnerIsDealer(), ruleset);

    return new HandPayment(
        han,
        hora.hu(),
        hora.yaku(),
        hand.dora(),
        units,
        deltas(payout, hand.tsumo(), context),
        context.riichiSticks());
  }

  /**
   * Сколько якуманов оплачивается. Одна единица — один якуман по таблице §15.4.
   *
   * <p>Когда пресет оставляет особые ожидания одинарными (RRC-RU), считаем якуманы по именам: на
   * han библиотеки полагаться нельзя, она отдаёт двойной якуман независимо от флага. Когда пресет
   * их удваивает, наоборот, берём han библиотеки — она уже сложила множители.
   */
  private static int yakumanUnits(MahjongUtils.Hora hora, RulesetSnapshot ruleset) {
    long named = hora.yaku().stream().filter(YAKUMAN::contains).count();
    if (named == 0) {
      return 0; // казоэ-якуман считает сама библиотека по хан
    }
    int units =
        ruleset.complexYakumanCountsDouble() ? Math.max(1, hora.han() / YAKUMAN_HAN) : (int) named;
    return ruleset.stackYakuman() ? units : 1;
  }

  static Payout payout(
      int han, int fu, int units, boolean winnerIsDealer, RulesetSnapshot ruleset) {
    int lookupHan = han;
    int lookupFu = fu;
    if (units > 0) {
      lookupHan = YAKUMAN_HAN;
      lookupFu = 30;
    } else if (ruleset.kiriageMangan() && han == 3 && fu == 60) {
      // §15.4: кириаге накрывает и 3 хан 60 фу, библиотека знает только 4 хан 30 фу.
      lookupHan = 5;
      lookupFu = 30;
    }

    Map<String, Object> options =
        Map.of(
            "aotenjou",
            ruleset.aotenjou(),
            "hasKiriageMangan",
            ruleset.kiriageMangan(),
            // настоящий якуман оплачивается как якуман независимо от настройки казоэ
            "hasKazoeYakuman",
            units > 0 || ruleset.kazoeYakuman());

    int times = Math.max(1, units);
    if (winnerIsDealer) {
      MahjongUtils.DealerPoints points = MahjongUtils.dealerPoints(lookupHan, lookupFu, options);
      return new Payout(points.ron() * times, points.tsumoFromEach() * times, 0);
    }
    MahjongUtils.NonDealerPoints points =
        MahjongUtils.nonDealerPoints(lookupHan, lookupFu, options);
    return new Payout(
        points.ron() * times,
        points.tsumoFromDealer() * times,
        points.tsumoFromNonDealer() * times);
  }

  private static List<Integer> deltas(Payout payout, boolean tsumo, HandContext context) {
    int[] delta = new int[HandContext.SEATS];

    if (!tsumo) {
      int paid = payout.ron() + HONBA_PER_RON * context.honba();
      delta[context.discarderSeat()] -= paid;
      delta[context.winnerSeat()] += paid;
      return box(delta);
    }

    int honbaShare = HONBA_PER_TSUMO_PAYMENT * context.honba();
    for (int seat = 0; seat < HandContext.SEATS; seat++) {
      if (seat == context.winnerSeat()) {
        continue;
      }
      int paid =
          (context.winnerIsDealer() || seat == context.dealerSeat()
                  ? payout.tsumoFromDealer()
                  : payout.tsumoFromNonDealer())
              + honbaShare;
      delta[seat] -= paid;
      delta[context.winnerSeat()] += paid;
    }
    return box(delta);
  }

  private static Map<String, Object> horaArgs(
      WinningHand hand, HandContext context, RulesetSnapshot ruleset) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("tiles", hand.concealedTiles());
    args.put("furo", hand.melds());
    args.put("agari", hand.winningTile());
    args.put("tsumo", hand.tsumo());
    args.put("dora", hand.dora().total());
    args.put("selfWind", libraryName(context.winnerWind()));
    args.put("roundWind", libraryName(context.roundWind()));
    args.put("extraYaku", List.copyOf(hand.declaredYaku()));
    args.put(
        "options",
        Map.of(
            "aotenjou", ruleset.aotenjou(),
            "allowKuitan", ruleset.openTanyao(),
            "hasRenpuuJyantouHu", ruleset.doubleWindPairFu() == 4,
            "hasKiriageMangan", ruleset.kiriageMangan(),
            "hasKazoeYakuman", ruleset.kazoeYakuman(),
            "hasMultipleYakuman", ruleset.stackYakuman(),
            "hasComplexYakuman", ruleset.complexYakumanCountsDouble()));
    return args;
  }

  private static String libraryName(Wind wind) {
    String name = wind.name();
    return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
  }

  private static List<Integer> box(int[] delta) {
    List<Integer> boxed = new ArrayList<>(delta.length);
    for (int value : delta) {
      boxed.add(value);
    }
    return boxed;
  }

  /** Базовые выплаты до хонбы. Для дилера цумо одинаково со всех, поэтому третье поле не нужно. */
  record Payout(int ron, int tsumoFromDealer, int tsumoFromNonDealer) {}
}
