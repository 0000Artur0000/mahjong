package ru.dorahub.scoring;

import java.util.ArrayList;
import java.util.List;

/**
 * Дора по индикаторам, §11 правил.
 *
 * <p>Индикатор показывает предыдущий тайл: дора — следующий за ним в цикле. Внутри масти {@code
 * 1→…→9→1}, ветра {@code восток→юг→запад→север→восток}, драконы {@code
 * красный→белый→зелёный→красный} — в записи mjai это {@code 7z→5z→6z→7z}, то есть тот же цикл
 * {@code 5z→6z→7z→5z}, записанный с другого места.
 *
 * <p>Считать дору должен сервер, а не клиент: это правило игры, и ошибка в нём — самая частая
 * арифметическая ошибка за столом. Игрок вводит тайлы-индикаторы, а не число хан.
 */
public final class DoraIndicators {

  /** Базовый индикатор плюс по одному на каждый из четырёх возможных канов. */
  public static final int MAX_INDICATORS = 5;

  private DoraIndicators() {}

  /**
   * Тайл, который является дорой при данном индикаторе.
   *
   * @param indicator тайл в нотации mjai; красная пятёрка считается обычной пятёркой
   */
  public static String dora(String indicator) {
    String tile = normalize(indicator);
    char suit = tile.charAt(1);
    int number = tile.charAt(0) - '0';

    if (suit == 'z') {
      // Ветра и драконы — два независимых цикла: 1z→2z→3z→4z→1z и 5z→6z→7z→5z.
      return number <= 4 ? (number % 4 + 1) + "z" : ((number - 4) % 3 + 5) + "z";
    }
    return (number % 9 + 1) + String.valueOf(suit);
  }

  /**
   * Посчитать доры всех видов в руке.
   *
   * <p>Первый индикатор даёт обычную дору, остальные — кан-дору. Ура считается по нижним
   * индикаторам целиком. Ака не считается по индикаторам вообще: красные пятёрки уже входят в
   * состав руки, поэтому спрашивать их отдельно значит напрашиваться на расхождение.
   *
   * <p>§11: если на один тайл указывают несколько индикаторов, бонусы складываются, а красная
   * пятёрка может одновременно быть обычной дорой.
   */
  public static WinningHand.Dora count(
      List<String> concealedTiles,
      List<String> melds,
      List<String> doraIndicators,
      List<String> uraIndicators) {
    List<String> tiles = allTiles(concealedTiles, melds);

    int ordinary = doraIndicators.isEmpty() ? 0 : matches(tiles, doraIndicators.get(0));
    int kan = 0;
    for (int i = 1; i < doraIndicators.size(); i++) {
      kan += matches(tiles, doraIndicators.get(i));
    }
    int ura = 0;
    for (String indicator : uraIndicators) {
      ura += matches(tiles, indicator);
    }
    int aka = (int) tiles.stream().filter(DoraIndicators::isAka).count();

    return new WinningHand.Dora(ordinary, ura, kan, aka);
  }

  /** Все тайлы руки: закрытая часть плюс развёрнутые открытые сеты. */
  static List<String> allTiles(List<String> concealedTiles, List<String> melds) {
    List<String> tiles = new ArrayList<>(concealedTiles);
    for (String meld : melds) {
      tiles.addAll(expand(meld));
    }
    return tiles;
  }

  /** "333p" → [3p, 3p, 3p]; "234m" → [2m, 3m, 4m]; "1111s" → четыре 1s. */
  static List<String> expand(String meld) {
    if (meld.length() < 2) {
      throw new IllegalArgumentException("непонятный сет: " + meld);
    }
    String suit = meld.substring(meld.length() - 1);
    List<String> tiles = new ArrayList<>(meld.length() - 1);
    for (int i = 0; i < meld.length() - 1; i++) {
      tiles.add(meld.charAt(i) + suit);
    }
    return tiles;
  }

  private static int matches(List<String> tiles, String indicator) {
    String dora = dora(indicator);
    return (int) tiles.stream().filter(tile -> normalize(tile).equals(dora)).count();
  }

  private static boolean isAka(String tile) {
    return tile.length() == 2 && tile.charAt(0) == '0' && "mps".indexOf(tile.charAt(1)) >= 0;
  }

  /** Красная пятёрка — это пятёрка: и как индикатор, и как цель доры. */
  private static String normalize(String tile) {
    if (tile == null || tile.length() != 2) {
      throw new IllegalArgumentException("непонятный тайл: " + tile);
    }
    char suit = tile.charAt(1);
    if ("mps".indexOf(suit) < 0 && suit != 'z') {
      throw new IllegalArgumentException("непонятная масть: " + tile);
    }
    int number = tile.charAt(0) - '0';
    if (suit == 'z' ? number < 1 || number > 7 : number < 0 || number > 9) {
      throw new IllegalArgumentException("непонятный тайл: " + tile);
    }
    return isAka(tile) ? "5" + suit : tile;
  }
}
