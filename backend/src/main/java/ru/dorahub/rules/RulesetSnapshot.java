package ru.dorahub.rules;

import java.util.List;

/**
 * Неизменяемый снимок правил, по которым игралась партия.
 *
 * <p>Стол сохраняет снимок целиком до старта, поэтому пересчёт старой партии не зависит от того,
 * как потом поменяли пресет. {@link #checksum()} — SHA-256 исходного файла пресета.
 *
 * <p>Пока покрыты только развилки, которые читают Rules и Scoring. Нотен-пенальти, абортивные ничьи
 * и условия ренчана добавляются вместе с A6, санма и EMA — на этапе B.
 *
 * @param uma бонусы за места в очках, от первого к четвёртому
 * @param oka бонус победителю в очках
 * @param stackYakuman складываются ли несколько разных якуманов (правила §16.5)
 * @param complexYakumanCountsDouble считаются ли сууанко-танки, 13-стороннее кокуши, чистое
 *     9-стороннее чуурен и дайсууши двойным якуманом; в RRC-RU — нет
 * @param doubleWindPairFu фу за пару, совпадающую с ветром места и раунда: 4 в RRC-RU, 2 в EMA-2025
 * @param abortiveDraws допускает ли пресет досрочные ничьи §12.2; в EMA-2025 их нет
 */
public record RulesetSnapshot(
    String key,
    String version,
    String displayName,
    int startingPoints,
    int returnPoints,
    List<Integer> uma,
    int oka,
    boolean openTanyao,
    boolean kiriageMangan,
    boolean kazoeYakuman,
    boolean stackYakuman,
    boolean complexYakumanCountsDouble,
    int doubleWindPairFu,
    boolean aotenjou,
    boolean atamahane,
    boolean abortiveDraws,
    boolean tripleRonAbort,
    boolean nagashiMangan,
    int chomboPenalty,
    String checksum) {

  public RulesetSnapshot {
    uma = List.copyOf(uma);
    if (uma.size() != 4) {
      throw new IllegalArgumentException("uma должна содержать 4 значения, а не " + uma.size());
    }
    if (uma.stream().mapToInt(Integer::intValue).sum() != 0) {
      throw new IllegalArgumentException("сумма умы должна быть нулевой: " + uma);
    }
    if (doubleWindPairFu != 2 && doubleWindPairFu != 4) {
      throw new IllegalArgumentException("doubleWindPairFu допускает только 2 или 4");
    }
    if (startingPoints <= 0 || returnPoints <= 0) {
      throw new IllegalArgumentException("стартовые и возвратные очки должны быть положительными");
    }
  }

  /** Полный идентификатор версии правил для логов, событий и снимков. */
  public String id() {
    return key + "@" + version;
  }
}
