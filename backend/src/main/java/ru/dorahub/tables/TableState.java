package ru.dorahub.tables;

import java.util.List;
import java.util.UUID;
import ru.dorahub.scoring.Wind;

/**
 * Изменяемая часть состояния стола — то, что хранится в {@code game_table.game_state}.
 *
 * <p>Формат, состояние и версия лежат отдельными колонками, потому что по ним идут выборки и
 * оптимистичная блокировка; остальное читается только вместе со столом целиком.
 */
public record TableState(
    List<UUID> participants,
    List<UUID> seats,
    List<Integer> scores,
    int dealerSeat,
    Wind roundWind,
    int handNumber,
    int honba,
    int riichiSticks,
    int handsPlayed,
    List<Integer> vacantSeats,
    boolean substituted,
    Table.FinishReason finishedReason) {

  public TableState {
    participants = List.copyOf(participants);
    seats = List.copyOf(seats);
    scores = List.copyOf(scores);
    vacantSeats = List.copyOf(vacantSeats);
  }
}
