package ru.dorahub.scoring;

import java.util.List;
import java.util.Set;

/**
 * Состав выигрышной руки в нотации mjai: {@code 1m..9m}, {@code 1p..9p}, {@code 1s..9s}, {@code
 * 1z..7z}.
 *
 * <p>Открытые сеты записываются строкой: {@code "333p"} — пон, {@code "234m"} — чи, {@code "1111s"}
 * — кан.
 *
 * @param concealedTiles закрытая часть руки, включая выигрышный тайл
 * @param melds открытые сеты
 * @param winningTile выигрышный тайл, указывается вручную и фото не доказывается
 * @param tsumo победа собственным взятием
 * @param dora все виды доры отдельными счётчиками
 * @param declaredYaku яку, которые видно только по ходу игры и которые игрок объявляет сам
 */
public record WinningHand(
    List<String> concealedTiles,
    List<String> melds,
    String winningTile,
    boolean tsumo,
    Dora dora,
    Set<String> declaredYaku) {

  /**
   * Яку, которых фотография не доказывает: их источником всегда является объявление игрока или
   * полный лог раздачи. Имена совпадают с моделью mahjong-utils.
   */
  public static final Set<String> DECLARABLE_YAKU =
      Set.of(
          "Richi", "WRichi", "Ippatsu", "Rinshan", "Chankan", "Haitei", "Houtei", "Tenhou",
          "Chihou");

  /**
   * Рука с дорой, посчитанной по индикаторам, а не по готовому числу хан.
   *
   * <p>Ура-дора существует только у победителя с объявленным риичи (§11), поэтому она проверяется
   * здесь, где видно и то и другое. Ака не передаётся вовсе — красные пятёрки уже входят в состав
   * руки.
   *
   * @param doraIndicators первый — базовый, остальные открыты канами
   * @param uraIndicators нижние индикаторы; по одному на каждый верхний
   */
  public static WinningHand withIndicators(
      List<String> concealedTiles,
      List<String> melds,
      String winningTile,
      boolean tsumo,
      List<String> doraIndicators,
      List<String> uraIndicators,
      Set<String> declaredYaku) {
    if (doraIndicators.size() > DoraIndicators.MAX_INDICATORS) {
      throw new IllegalArgumentException(
          "индикаторов не может быть больше "
              + DoraIndicators.MAX_INDICATORS
              + ": базовый и по одному на кан");
    }
    if (uraIndicators.size() > doraIndicators.size()) {
      throw new IllegalArgumentException(
          "нижних индикаторов больше, чем верхних: у каждого верхнего ровно один нижний");
    }
    boolean riichi = declaredYaku.contains("Richi") || declaredYaku.contains("WRichi");
    if (!uraIndicators.isEmpty() && !riichi) {
      throw new IllegalArgumentException("ура-дора доступна только победителю с риичи");
    }

    return new WinningHand(
        concealedTiles,
        melds,
        winningTile,
        tsumo,
        DoraIndicators.count(concealedTiles, melds, doraIndicators, uraIndicators),
        declaredYaku);
  }

  public WinningHand {
    concealedTiles = List.copyOf(concealedTiles);
    melds = List.copyOf(melds);
    declaredYaku = Set.copyOf(declaredYaku);
    if (concealedTiles.isEmpty()) {
      throw new IllegalArgumentException("закрытая часть руки пуста");
    }
    if (winningTile == null || winningTile.isBlank()) {
      throw new IllegalArgumentException("не указан выигрышный тайл");
    }
    Set<String> unknown = new java.util.HashSet<>(declaredYaku);
    unknown.removeAll(DECLARABLE_YAKU);
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException("нельзя объявить вручную: " + unknown);
    }
  }

  /**
   * Дора по видам. Считаются раздельно ради разбора, в стоимость руки входят одинаково.
   *
   * @param ura ура-дора, показывается только победителю с риичи
   * @param kan кан-дора от дополнительных индикаторов
   * @param aka красные пятёрки
   */
  public record Dora(int ordinary, int ura, int kan, int aka) {

    public static final Dora NONE = new Dora(0, 0, 0, 0);

    public Dora {
      if (ordinary < 0 || ura < 0 || kan < 0 || aka < 0) {
        throw new IllegalArgumentException("счётчик доры не может быть отрицательным");
      }
    }

    public int total() {
      return ordinary + ura + kan + aka;
    }
  }
}
