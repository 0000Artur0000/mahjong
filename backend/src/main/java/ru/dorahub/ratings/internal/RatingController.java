package ru.dorahub.ratings.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.dorahub.accounts.AccountDirectory;
import ru.dorahub.ratings.Ratings;

/** Лестница и своя история рейтинга. */
@RestController
@RequestMapping("/api/v1/ratings")
@Profile("!test")
class RatingController {

  private final Ratings ratings;
  private final AccountDirectory accounts;

  RatingController(Ratings ratings, AccountDirectory accounts) {
    this.ratings = ratings;
    this.accounts = accounts;
  }

  @GetMapping
  List<LadderEntryView> ladder(
      @RequestParam @NotBlank String format,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
    List<ru.dorahub.ratings.LadderEntry> ladder = ratings.ladder(format, limit);
    // Лестница из UUID нечитаема: имя здесь не украшение, а весь смысл экрана.
    var nicknames = accounts.nicknames(ladder.stream().map(entry -> entry.accountId()).toList());
    return ladder.stream()
        .map(
            entry ->
                new LadderEntryView(
                    entry.accountId(),
                    nicknames.get(entry.accountId()),
                    entry.rating(),
                    entry.games()))
        .toList();
  }

  /** Свой итог по форматам: партии, места и рейтинг. */
  @GetMapping("/me/summary")
  List<FormatSummaryView> summary(Authentication authentication) {
    return ratings.summary(actor(authentication)).stream()
        .map(
            entry ->
                new FormatSummaryView(
                    entry.format(),
                    entry.rating(),
                    entry.games(),
                    entry.places(),
                    Math.round(entry.averagePlace() * 100) / 100.0))
        .toList();
  }

  /** Своя история: на вопрос «почему я потерял 12» отвечает строка, а не пересчёт. */
  @GetMapping("/me")
  List<RatingChangeView> mine(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      Authentication authentication) {
    return ratings.history(actor(authentication), limit).stream()
        .map(
            change ->
                new RatingChangeView(
                    change.tableId(),
                    change.format(),
                    change.place(),
                    change.delta(),
                    change.ratingAfter(),
                    change.at().toString()))
        .toList();
  }

  private static UUID actor(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || "anonymousUser".equals(authentication.getName())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
    try {
      return UUID.fromString(authentication.getName());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
  }

  record LadderEntryView(UUID accountId, String nickname, int rating, int games) {}

  record FormatSummaryView(
      String format, int rating, int games, List<Integer> places, double averagePlace) {}

  record RatingChangeView(
      UUID tableId, String format, int place, int delta, int ratingAfter, String at) {}
}
