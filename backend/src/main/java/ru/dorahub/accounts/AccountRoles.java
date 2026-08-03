package ru.dorahub.accounts;

import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Роль аккаунта — единственная проверка прав, которая сейчас нужна.
 *
 * <p>Проверка живёт в сервисном слое, а не в конфигурации HTTP: правило «исправить подтверждённый
 * результат может только модератор» относится к домену, и обходить его через другой контроллер быть
 * не должно.
 */
@Service
@Profile("!test")
public class AccountRoles {

  private final JdbcTemplate jdbc;

  AccountRoles(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Роль игрока; у неизвестного аккаунта — {@link Role#PLAYER}. */
  @Transactional(readOnly = true)
  public Role role(UUID accountId) {
    return jdbc
        .query(
            "SELECT role FROM app_user WHERE id = ?",
            (row, number) -> Role.valueOf(row.getString("role").toUpperCase(Locale.ROOT)),
            accountId)
        .stream()
        .findFirst()
        .orElse(Role.PLAYER);
  }

  /**
   * Потребовать роль не ниже указанной.
   *
   * @throws AccessDeniedException если роли не хватает
   */
  @Transactional(readOnly = true)
  public void require(UUID accountId, Role required) {
    if (!role(accountId).covers(required)) {
      throw new AccessDeniedException("нужна роль " + required.name().toLowerCase(Locale.ROOT));
    }
  }

  /** Роли по возрастанию прав: старшая покрывает младшие. */
  public enum Role {
    PLAYER,
    MODERATOR,
    ADMIN;

    boolean covers(Role required) {
      return ordinal() >= required.ordinal();
    }
  }
}
