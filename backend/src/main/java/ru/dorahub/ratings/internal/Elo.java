package ru.dorahub.ratings.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Эло для партии на четверых: каждая пара игроков считается отдельным матчем.
 *
 * <p>Дельта игрока — сумма по соперникам {@code (факт − ожидание)}, делённая на число соперников.
 * Так рейтинг остаётся с нулевой суммой: {@code факт(i,j) + факт(j,i) = 1} и {@code ожидание(i,j) +
 * ожидание(j,i) = 1}, значит сумма по столу равна нулю точно, а не приблизительно. Очки за партию в
 * счёт не идут — только место: считать «на сколько выиграл» значит поощрять добивание уже
 * проигравших.
 *
 * <p>Делённые места (§14 допускает равенство очков) дают пол-очка обеим сторонам.
 */
public final class Elo {

  /** Рейтинг новичка. */
  public static final int START = 1500;

  /** Цена партии. 32 — стандарт для быстро сходящейся лестницы клубного размера. */
  private static final int K = 32;

  private Elo() {}

  /**
   * Изменения рейтинга по местам. Порядок ответа совпадает с порядком входа, сумма равна нулю.
   *
   * @param ratings текущие рейтинги игроков
   * @param places места 1..4; равные значения означают делённое место
   */
  public static List<Integer> deltas(List<Integer> ratings, List<Integer> places) {
    int players = ratings.size();
    if (players < 2 || places.size() != players) {
      throw new IllegalArgumentException("нужно не меньше двух игроков и место каждому");
    }

    double[] raw = new double[players];
    for (int self = 0; self < players; self++) {
      double sum = 0;
      for (int rival = 0; rival < players; rival++) {
        if (self == rival) {
          continue;
        }
        double expected = 1 / (1 + Math.pow(10, (ratings.get(rival) - ratings.get(self)) / 400.0));
        sum += outcome(places.get(self), places.get(rival)) - expected;
      }
      raw[self] = K * sum / (players - 1);
    }
    return roundKeepingZeroSum(raw);
  }

  private static double outcome(int self, int rival) {
    if (self == rival) {
      return 0.5;
    }
    return self < rival ? 1 : 0;
  }

  /**
   * Округление, не ломающее нулевую сумму.
   *
   * <p>Обычный {@code Math.round} по каждому игроку рисует из воздуха до двух очков за партию — на
   * длинной дистанции лестница уезжает. Дробные остатки раздаются по методу наибольшего остатка:
   * сумма целых равна нулю всегда.
   */
  private static List<Integer> roundKeepingZeroSum(double[] raw) {
    int[] whole = new int[raw.length];
    int remainder = 0;
    for (int player = 0; player < raw.length; player++) {
      whole[player] = (int) Math.floor(raw[player]);
      remainder -= whole[player];
    }

    List<Integer> byFraction = new ArrayList<>(raw.length);
    for (int player = 0; player < raw.length; player++) {
      byFraction.add(player);
    }
    byFraction.sort(
        Comparator.comparingDouble((Integer player) -> raw[player] - Math.floor(raw[player]))
            .reversed());

    for (int index = 0; index < remainder; index++) {
      whole[byFraction.get(index)]++;
    }

    List<Integer> result = new ArrayList<>(raw.length);
    for (int value : whole) {
      result.add(value);
    }
    return List.copyOf(result);
  }
}
