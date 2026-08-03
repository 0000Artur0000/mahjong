package ru.dorahub.scoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import ru.dorahub.rules.RulesetSnapshot;

/**
 * Итог партии: места, ума и ока.
 *
 * <p>Правила §14: {@code результат = игровые очки − возвратные очки + ума + ока победителю}. При
 * равенстве игровых очков места делятся, а ума связанных мест складывается и делится поровну.
 */
public final class FinalScore {

  private FinalScore() {}

  /**
   * Итоговая таблица по игровым очкам мест 0..3.
   *
   * <p>Порядок результата — от первого места к четвёртому.
   */
  public static List<Placement> of(List<Integer> points, RulesetSnapshot ruleset) {
    if (points.size() != HandContext.SEATS) {
      throw new IllegalArgumentException("ожидалось 4 места, а не " + points.size());
    }

    List<Integer> seats = new ArrayList<>(List.of(0, 1, 2, 3));
    seats.sort(Comparator.comparingInt(points::get).reversed());

    List<Placement> table = new ArrayList<>(HandContext.SEATS);
    int index = 0;
    while (index < seats.size()) {
      int tied = 1;
      while (index + tied < seats.size()
          && points.get(seats.get(index + tied)).equals(points.get(seats.get(index)))) {
        tied++;
      }

      int place = index + 1;
      int uma = 0;
      for (int offset = 0; offset < tied; offset++) {
        uma += ruleset.uma().get(index + offset);
      }
      // ponytail: целочисленное деление. Для ум вида 15/5/−5/−15 любая группа делится нацело.
      uma /= tied;
      int oka = place == 1 ? ruleset.oka() / tied : 0;

      for (int offset = 0; offset < tied; offset++) {
        int seat = seats.get(index + offset);
        int score = points.get(seat);
        table.add(
            new Placement(seat, place, score, uma, score - ruleset.returnPoints() + uma + oka));
      }
      index += tied;
    }
    return List.copyOf(table);
  }

  /**
   * Место игрока по итогам партии.
   *
   * @param place 1..4; при равенстве очков несколько игроков делят одно место
   * @param points игровые очки на конец партии
   * @param result итог с учётом возвратных очков, умы и оки
   */
  public record Placement(int seat, int place, int points, int uma, int result) {}
}
