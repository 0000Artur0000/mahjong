package ru.dorahub.scoring;

/** Ветер раунда или места. Порядок совпадает с порядком хода. */
public enum Wind {
  EAST,
  SOUTH,
  WEST,
  NORTH;

  private static final Wind[] ORDER = values();

  /** Ветер места игрока: восток — у дилера, дальше по порядку хода. */
  public static Wind ofSeat(int seat, int dealerSeat) {
    return ORDER[Math.floorMod(seat - dealerSeat, ORDER.length)];
  }

  /** Следующий ветер раунда. */
  public Wind next() {
    return ORDER[(ordinal() + 1) % ORDER.length];
  }
}
