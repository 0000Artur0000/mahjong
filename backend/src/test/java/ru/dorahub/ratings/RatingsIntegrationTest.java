package ru.dorahub.ratings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class RatingsIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Autowired private Ratings ratings;

  @Test
  void startsEveryoneAtTheSameRatingAndKeepsTheSumAtZero() {
    List<UUID> players = players();

    List<RatingChange> changes = ratings.record(UUID.randomUUID(), "hanchan", places(players));

    assertThat(changes).extracting(RatingChange::delta).satisfies(deltas -> sumIsZero(deltas));
    assertThat(changes.getFirst().ratingAfter()).isGreaterThan(1500);
    assertThat(changes.getLast().ratingAfter()).isLessThan(1500);
  }

  /** Вторая партия считается от рейтинга после первой, а не от стартового. */
  @Test
  void buildsOnThePreviousResult() {
    List<UUID> players = players();
    ratings.record(UUID.randomUUID(), "hanchan", places(players));
    int afterFirst = ratings.history(players.getFirst(), 1).getFirst().ratingAfter();

    List<RatingChange> second = ratings.record(UUID.randomUUID(), "hanchan", places(players));

    assertThat(second.getFirst().ratingAfter() - second.getFirst().delta()).isEqualTo(afterFirst);
    assertThat(ratings.history(players.getFirst(), 10)).hasSize(2);
  }

  /** Одна партия — одна запись: повтор не начисляет рейтинг дважды. */
  @Test
  void ignoresTheSameTableTwice() {
    List<UUID> players = players();
    UUID tableId = UUID.randomUUID();
    ratings.record(tableId, "hanchan", places(players));

    ratings.record(tableId, "hanchan", places(players));

    assertThat(ratings.history(players.getFirst(), 10)).hasSize(1);
  }

  /** Лестница показывает последнюю партию игрока, а не первую попавшуюся. */
  @Test
  void showsTheLatestRatingInTheLadder() {
    List<UUID> players = players();
    ratings.record(UUID.randomUUID(), "wanpaku", places(players));
    ratings.record(UUID.randomUUID(), "wanpaku", places(players.reversed()));

    assertThat(ratings.ladder("wanpaku", 100))
        .filteredOn(entry -> entry.accountId().equals(players.getFirst()))
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.games()).isEqualTo(2);
              assertThat(entry.rating())
                  .isEqualTo(ratings.history(players.getFirst(), 1).getFirst().ratingAfter());
            });
  }

  /** Отменённая партия не держит игрока в лестнице. */
  @Test
  void dropsPlayersWhoseOnlyGameWasRevoked() {
    List<UUID> players = players();
    UUID tableId = UUID.randomUUID();
    ratings.record(tableId, "kirinuke", places(players));
    ratings.revoke(tableId);

    assertThat(ratings.ladder("kirinuke", 100)).isEmpty();
  }

  /** Ханчан и тонпуусен — разные лестницы: общий рейтинг смешивал бы несравнимое. */
  @Test
  void keepsFormatsApart() {
    List<UUID> players = players();
    ratings.record(UUID.randomUUID(), "tonpuusen", places(players));

    assertThat(ratings.ladder("tonpuusen", 100))
        .extracting(LadderEntry::accountId)
        .containsAll(players);
    assertThat(ratings.ladder("hanchan", 100))
        .extracting(LadderEntry::accountId)
        .doesNotContainAnyElementsOf(players);
  }

  /** Отмена возвращает рейтинг встречным начислением, а не удалением строк. */
  @Test
  void revokesAGameByCompensating() {
    List<UUID> players = players();
    UUID tableId = UUID.randomUUID();
    ratings.record(tableId, "hanchan", places(players));

    assertThat(ratings.revoke(tableId)).isEqualTo(4);

    assertThat(ratings.history(players.getFirst(), 10)).hasSize(2);
    assertThat(ratings.history(players.getFirst(), 1).getFirst().ratingAfter()).isEqualTo(1500);
    // Игрок, чья единственная партия отменена, из лестницы уходит совсем.
    assertThat(ratings.ladder("hanchan", 100))
        .extracting(LadderEntry::accountId)
        .doesNotContainAnyElementsOf(players);
  }

  /** После отмены партию доигрывают заново, и это законный второй результат. */
  @Test
  void ratesTheSameTableAgainAfterRevoking() {
    List<UUID> players = players();
    UUID tableId = UUID.randomUUID();
    ratings.record(tableId, "hanchan", places(players));
    ratings.revoke(tableId);

    List<RatingChange> replay = ratings.record(tableId, "hanchan", places(players.reversed()));

    assertThat(replay).hasSize(4);
    assertThat(replay.getFirst().accountId()).isEqualTo(players.getLast());
    assertThat(ratings.history(players.getLast(), 10)).hasSize(3);
  }

  /** Отмена того, чего не было, ничего не делает. */
  @Test
  void revokesNothingForAnUnratedTable() {
    assertThat(ratings.revoke(UUID.randomUUID())).isZero();
  }

  /** Профиль: сколько партий, какие места, какой рейтинг — по форматам раздельно. */
  @Test
  void summarisesGamesByFormat() {
    List<UUID> players = players();
    ratings.record(UUID.randomUUID(), "hanchan", places(players));
    ratings.record(UUID.randomUUID(), "hanchan", places(players.reversed()));
    ratings.record(UUID.randomUUID(), "tonpuusen", places(players));

    var summary = ratings.summary(players.getFirst());

    assertThat(summary).extracting(entry -> entry.format()).containsExactly("hanchan", "tonpuusen");
    var hanchan = summary.getFirst();
    assertThat(hanchan.games()).isEqualTo(2);
    // Первое место и последнее: средним выходит ровно 2.5.
    assertThat(hanchan.places()).containsExactly(1, 0, 0, 1);
    assertThat(hanchan.averagePlace()).isEqualTo(2.5);
    // Рейтинг в сводке — именно ханчановый: история игрока идёт вперемешку по форматам.
    assertThat(hanchan.rating())
        .isEqualTo(
            ratings.ladder("hanchan", 100).stream()
                .filter(entry -> entry.accountId().equals(players.getFirst()))
                .findFirst()
                .orElseThrow()
                .rating());
  }

  /** Отменённая партия исчезает из статистики, но рейтинг остаётся текущим. */
  @Test
  void leavesRevokedGamesOutOfTheSummary() {
    List<UUID> players = players();
    UUID tableId = UUID.randomUUID();
    ratings.record(tableId, "hanchan", places(players));
    ratings.revoke(tableId);

    assertThat(ratings.summary(players.getFirst())).isEmpty();
  }

  @Test
  void sortsTheLadderByRating() {
    ratings.record(UUID.randomUUID(), "sanma", places(players()));

    List<LadderEntry> ladder = ratings.ladder("sanma", 100);

    assertThat(ladder).extracting(LadderEntry::rating).isSortedAccordingTo(reverseOrder());
    assertThat(ladder).extracting(LadderEntry::games).containsOnly(1);
  }

  private static java.util.Comparator<Integer> reverseOrder() {
    return (left, right) -> Integer.compare(right, left);
  }

  private static void sumIsZero(List<? extends Integer> deltas) {
    assertThat(deltas.stream().mapToInt(Integer::intValue).sum()).isZero();
  }

  private static List<UUID> players() {
    return List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  }

  private static List<RatedPlace> places(List<UUID> players) {
    return List.of(
        new RatedPlace(players.get(0), 1),
        new RatedPlace(players.get(1), 2),
        new RatedPlace(players.get(2), 3),
        new RatedPlace(players.get(3), 4));
  }
}
