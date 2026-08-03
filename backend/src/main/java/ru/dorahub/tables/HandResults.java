package ru.dorahub.tables;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import ru.dorahub.scoring.HandPayment;
import ru.dorahub.scoring.ScoreEngine;
import ru.dorahub.scoring.WinningHand;

/**
 * Ввод результата раздачи: посчитать, показать, подтвердить.
 *
 * <p>{@link #preview} ничего не меняет — его можно вызывать сколько угодно раз, пока игроки сверяют
 * разбор. {@link #confirm} применяет результат ровно один раз: он принимает версию стола, которую
 * видел клиент, и отклоняет запрос, если стол успел уйти вперёд. Повторная отправка того же
 * подтверждения после успешного первого не пройдёт проверку версии и не начислит очки дважды.
 *
 * <p>Ввод полностью ручной и не зависит от фото: распознавание появляется на этапе C и будет лишь
 * заполнять {@link WinningHand} за игрока.
 */
@Service
public class HandResults {

  private final ScoreEngine scoreEngine;

  HandResults(ScoreEngine scoreEngine) {
    this.scoreEngine = scoreEngine;
  }

  /** Разбор и выплаты без изменения стола. */
  public HandPayment preview(Table table, WinningHand hand, int winnerSeat, Integer discarderSeat) {
    return scoreEngine.score(hand, table.handContext(winnerSeat, discarderSeat), table.ruleset());
  }

  /**
   * Подтвердить результат и применить его к столу.
   *
   * @param expectedVersion версия стола, на которой клиент строил превью
   * @throws OptimisticLockingFailureException если стол изменился после превью
   */
  public HandPayment confirm(
      Table table, WinningHand hand, int winnerSeat, Integer discarderSeat, long expectedVersion) {
    if (table.version() != expectedVersion) {
      throw new OptimisticLockingFailureException(
          "стол "
              + table.id()
              + " изменился: ожидалась версия "
              + expectedVersion
              + ", текущая "
              + table.version());
    }

    HandPayment payment = preview(table, hand, winnerSeat, discarderSeat);
    table.applyWin(payment, winnerSeat);
    return payment;
  }
}
