package ru.dorahub.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.dorahub.rules.RulesetSnapshot;
import ru.dorahub.rules.Rulesets;
import ru.dorahub.scoring.Wind;
import ru.dorahub.tables.Table.Format;
import ru.dorahub.tables.Table.State;

class TableTest {

  private static final RulesetSnapshot RRC = new Rulesets().require("rrc-ru");
  private static final long SEED = 42L;

  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
  private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");
  private static final UUID E = UUID.fromString("00000000-0000-0000-0000-00000000000e");

  @Test
  void startsInLobbyWithCreatorJoined() {
    Table table = table();

    assertThat(table.state()).isEqualTo(State.LOBBY);
    assertThat(table.participants()).containsExactly(A);
    assertThat(table.seats()).isEmpty();
    assertThat(table.scores()).isEmpty();
  }

  @Test
  void seatsEveryoneAndDealsStartingPointsOnStart() {
    Table table = full();
    table.start();

    assertThat(table.state()).isEqualTo(State.ACTIVE);
    assertThat(table.seats()).containsExactlyInAnyOrder(A, B, C, D);
    assertThat(table.scores()).containsExactly(30000, 30000, 30000, 30000);
    assertThat(table.dealerSeat()).isZero();
    assertThat(table.roundWind()).isEqualTo(Wind.EAST);
    assertThat(table.handNumber()).isEqualTo(1);
    assertThat(table.honba()).isZero();
    assertThat(table.riichiSticks()).isZero();
  }

  /** Жеребьёвка зависит только от зерна, чтобы посадку можно было воспроизвести при разборе. */
  @Test
  void seatingIsReproducibleFromSeed() {
    Table first = full();
    Table second = full();
    first.start();
    second.start();

    assertThat(first.seats()).isEqualTo(second.seats());
  }

  @Test
  void differentSeedsGiveDifferentSeating() {
    Table table = full();
    Table other = Table.create(UUID.randomUUID(), RRC, Format.HANCHAN, 7L, A);
    other.join(B);
    other.join(C);
    other.join(D);
    table.start();
    other.start();

    assertThat(table.seats()).isNotEqualTo(other.seats());
  }

  @Test
  void derivesSeatWindsFromDealer() {
    Table table = full();
    table.start();

    assertThat(table.windOf(0)).isEqualTo(Wind.EAST);
    assertThat(table.windOf(1)).isEqualTo(Wind.SOUTH);
    assertThat(table.windOf(2)).isEqualTo(Wind.WEST);
    assertThat(table.windOf(3)).isEqualTo(Wind.NORTH);
  }

  @Test
  void keepsRulesetSnapshotTakenAtCreation() {
    Table table = table();

    assertThat(table.ruleset().id()).isEqualTo("rrc-ru@1.0");
    assertThat(table.ruleset().checksum()).isEqualTo(RRC.checksum());
  }

  @Test
  void ignoresRepeatedJoinFromSamePlayer() {
    Table table = table();
    long before = table.version();
    table.join(A);

    assertThat(table.participants()).containsExactly(A);
    assertThat(table.version()).isEqualTo(before);
  }

  @Test
  void bumpsVersionOnEveryAcceptedCommand() {
    Table table = table();
    long created = table.version();

    table.join(B);
    assertThat(table.version()).isGreaterThan(created);

    long joined = table.version();
    table.leave(B);
    assertThat(table.version()).isGreaterThan(joined);
  }

  @Test
  void rejectedCommandChangesNothing() {
    Table table = table();
    long before = table.version();

    assertThatThrownBy(table::start).isInstanceOf(IllegalStateException.class);

    assertThat(table.version()).isEqualTo(before);
    assertThat(table.state()).isEqualTo(State.LOBBY);
    assertThat(table.scores()).isEmpty();
  }

  @Test
  void rejectsFifthPlayer() {
    Table table = full();

    assertThatThrownBy(() -> table.join(E))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("4");
  }

  @Test
  void rejectsStartWithoutFourPlayers() {
    Table table = table();
    table.join(B);

    assertThatThrownBy(table::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("нужно 4");
  }

  @Test
  void rejectsSecondStart() {
    Table table = full();
    table.start();

    assertThatThrownBy(table::start).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsJoinAfterStart() {
    Table table = full();
    table.start();

    assertThatThrownBy(() -> table.join(E))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("лобби");
  }

  @Test
  void finishesFromLobbyAndFromActive() {
    Table abandoned = table();
    abandoned.finish(Table.FinishReason.EARLY);
    assertThat(abandoned.state()).isEqualTo(State.FINISHED);

    Table played = full();
    played.start();
    played.finish(Table.FinishReason.EARLY);
    assertThat(played.state()).isEqualTo(State.FINISHED);
  }

  @Test
  void rejectsSecondFinish() {
    Table table = table();
    table.finish(Table.FinishReason.EARLY);

    assertThatThrownBy(() -> table.finish(Table.FinishReason.EARLY))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void buildsHandContextFromCurrentTableState() {
    Table table = full();
    table.start();

    var context = table.handContext(table.seatOf(B), table.seatOf(C));

    assertThat(context.dealerSeat()).isZero();
    assertThat(context.roundWind()).isEqualTo(Wind.EAST);
    assertThat(context.winnerSeat()).isEqualTo(table.seatOf(B));
    assertThat(context.discarderSeat()).isEqualTo(table.seatOf(C));
    assertThat(context.honba()).isZero();
  }

  @Test
  void rejectsHandContextBeforeStart() {
    Table table = full();

    assertThatThrownBy(() -> table.handContext(0, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOBBY");
  }

  @Test
  void rejectsSeatLookupForOutsider() {
    Table table = full();
    table.start();

    assertThatThrownBy(() -> table.seatOf(E)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void knowsLastRoundOfFormat() {
    assertThat(Format.HANCHAN.lastRoundWind()).isEqualTo(Wind.SOUTH);
    assertThat(Format.TONPUUSEN.lastRoundWind()).isEqualTo(Wind.EAST);
  }

  private static Table table() {
    return Table.create(UUID.randomUUID(), RRC, Format.HANCHAN, SEED, A);
  }

  private static Table full() {
    Table table = table();
    for (UUID player : List.of(B, C, D)) {
      table.join(player);
    }
    return table;
  }
}
