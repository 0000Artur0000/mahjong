package ru.dorahub.accounts;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Имена аккаунтов для чужих экранов.
 *
 * <p>Ник — единственное, что показывают за столом и в лестнице, и он публичный: город и клубы
 * закрыты приватностью, а имя видно всем, иначе за столом не понять, кто где сидит.
 *
 * <p>Отдаётся пачкой, а не по одному: четыре запроса на экран стола — это четыре круга по сети на
 * телефоне в клубе.
 */
@Service
@Profile("!test")
public class AccountDirectory {

  private final JdbcTemplate jdbc;

  AccountDirectory(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Ники по идентификаторам; неизвестных в ответе просто нет. */
  @Transactional(readOnly = true)
  public Map<UUID, String> nicknames(Collection<UUID> accountIds) {
    List<UUID> unique = accountIds.stream().distinct().toList();
    if (unique.isEmpty()) {
      return Map.of();
    }
    String placeholders = unique.stream().map(id -> "?").collect(Collectors.joining(", "));
    return jdbc
        .query(
            "SELECT id, nickname FROM app_user WHERE id IN (%s)".formatted(placeholders),
            (row, number) -> Map.entry(row.getObject("id", UUID.class), row.getString("nickname")),
            Stream.of(unique).flatMap(Collection::stream).toArray())
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }
}
