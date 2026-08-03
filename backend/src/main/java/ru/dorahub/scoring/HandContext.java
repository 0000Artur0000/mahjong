package ru.dorahub.scoring;

/**
 * Положение раздачи за столом: кто победил, кто заплатил и что накопилось на столе.
 *
 * <p>Места нумеруются 0..3 по порядку хода.
 *
 * @param discarderSeat сбросивший выигрышный тайл; {@code null} при цумо
 * @param riichiSticks ставки на столе, включая объявленные в этой раздаче
 */
public record HandContext(
    Wind roundWind,
    int dealerSeat,
    int winnerSeat,
    Integer discarderSeat,
    int honba,
    int riichiSticks) {

  public static final int SEATS = 4;

  public HandContext {
    requireSeat(dealerSeat, "dealerSeat");
    requireSeat(winnerSeat, "winnerSeat");
    if (discarderSeat != null) {
      requireSeat(discarderSeat, "discarderSeat");
      if (discarderSeat == winnerSeat) {
        throw new IllegalArgumentException("нельзя объявить рон на собственный сброс");
      }
    }
    if (honba < 0) {
      throw new IllegalArgumentException("хонба не может быть отрицательной");
    }
    if (riichiSticks < 0) {
      throw new IllegalArgumentException("число ставок риичи не может быть отрицательным");
    }
  }

  public boolean winnerIsDealer() {
    return winnerSeat == dealerSeat;
  }

  /** Ветер места победителя. */
  public Wind winnerWind() {
    return Wind.ofSeat(winnerSeat, dealerSeat);
  }

  private static void requireSeat(int seat, String name) {
    if (seat < 0 || seat >= SEATS) {
      throw new IllegalArgumentException(name + " вне диапазона 0..3: " + seat);
    }
  }
}
