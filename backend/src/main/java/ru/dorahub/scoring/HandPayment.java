package ru.dorahub.scoring;

import java.util.List;

/**
 * Разбор и выплаты за одну раздачу.
 *
 * <p>{@link #seatDelta()} всегда суммируется в ноль: очки только переходят между игроками. Ставки
 * риичи приходят не от игроков, а со стола, поэтому вынесены отдельно — стол начисляет их
 * победителю и обнуляет банк.
 *
 * @param yakumanCount сколько якуманов сложилось; 0 для обычной руки
 * @param seatDelta изменение очков по местам 0..3, включая хонбу
 */
public record HandPayment(
    int han,
    int fu,
    List<String> yaku,
    WinningHand.Dora dora,
    int yakumanCount,
    List<Integer> seatDelta,
    int riichiSticksAwarded) {

  public HandPayment {
    yaku = List.copyOf(yaku);
    seatDelta = List.copyOf(seatDelta);
    if (seatDelta.size() != HandContext.SEATS) {
      throw new IllegalArgumentException("ожидалось 4 места, а не " + seatDelta.size());
    }
    int sum = seatDelta.stream().mapToInt(Integer::intValue).sum();
    if (sum != 0) {
      throw new IllegalArgumentException("сумма изменений очков должна быть нулевой, а не " + sum);
    }
  }

  public boolean isYakuman() {
    return yakumanCount > 0;
  }
}
