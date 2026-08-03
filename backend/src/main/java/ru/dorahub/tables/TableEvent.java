package ru.dorahub.tables;

import java.util.Map;

/**
 * Запись журнала стола.
 *
 * <p>Журнал — это аудит и лента для клиента, а не источник истины: текущее состояние партии хранит
 * сам стол. Клиент подтягивает события начиная с известного ему номера и вместе с ними получает
 * актуальное состояние, поэтому пропуск события не ломает картину.
 *
 * @param sequence номер в пределах стола, строго возрастает и совпадает с версией агрегата
 */
public record TableEvent(long sequence, String type, Map<String, Object> payload) {

  public TableEvent {
    payload = Map.copyOf(payload);
    if (sequence <= 0) {
      throw new IllegalArgumentException("номер события должен быть положительным: " + sequence);
    }
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("у события должен быть тип");
    }
  }
}
