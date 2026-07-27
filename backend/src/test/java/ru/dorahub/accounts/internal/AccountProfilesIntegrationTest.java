package ru.dorahub.accounts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "dorahub.accounts.cleanup-delay=PT1H")
class AccountProfilesIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Autowired private AccountProfiles profiles;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private Clock clock;

  @Test
  void keepsPublicProfileSafeAndAnonymizesDeletedAccount() throws Exception {
    var accountId = createAccount("initial_player", "player@example.com");

    var updated =
        profiles.update(
            accountId,
            "  Игрок_42  ",
            "Екатеринбург",
            UUID.randomUUID(),
            new AccountProfiles.Privacy(false, true));

    assertThat(updated.nickname()).isEqualTo("Игрок_42");
    assertThat(profiles.publicProfile(accountId).city()).isNull();
    assertThat(objectMapper.writeValueAsString(profiles.publicProfile(accountId)))
        .contains("\"nickname\":\"Игрок_42\"")
        .doesNotContain(
            "email", "provider", "totp", "ip", "appeal", "photo", "avatarMediaId", "privacy");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_nickname_history WHERE account_id = ?",
                Integer.class,
                accountId))
        .isEqualTo(2);

    var otherAccount = createAccount("other_player", "other@example.com");
    assertThatThrownBy(
            () ->
                profiles.update(
                    otherAccount,
                    "ИГРОК_42",
                    null,
                    null,
                    new AccountProfiles.Privacy(false, false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409 CONFLICT");

    var deletion = profiles.requestDeletion(accountId);
    assertThatThrownBy(() -> profiles.publicProfile(accountId))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404 NOT_FOUND");
    assertThat(profiles.processNext()).isTrue();

    assertThat(
            jdbc.queryForMap(
                """
                SELECT status, nickname, city, avatar_media_id
                FROM app_user
                WHERE id = ?
                """,
                accountId))
        .containsEntry("status", "anonymized")
        .containsEntry("city", null)
        .containsEntry("avatar_media_id", null);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_identity WHERE user_id = ?",
                Integer.class,
                accountId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*)
                FROM account_nickname_history
                WHERE account_id = ? AND nickname = 'Игрок_42'
                """,
                Integer.class,
                accountId))
        .isZero();
    assertThat(
            jdbc.queryForMap(
                "SELECT status, report FROM account_cleanup_job WHERE id = ?", deletion.jobId()))
        .containsEntry("status", "completed")
        .hasEntrySatisfying("report", value -> assertThat(value.toString()).contains("identities"));
  }

  private UUID createAccount(String nickname, String email) {
    var accountId = UUID.randomUUID();
    var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    jdbc.update(
        """
        INSERT INTO app_user
            (id, status, nickname, nickname_normalized, created_at, updated_at)
        VALUES (?, 'active', ?, ?, ?, ?)
        """,
        accountId,
        nickname,
        nickname,
        now,
        now);
    jdbc.update(
        """
        INSERT INTO account_identity (id, user_id, provider, subject, created_at)
        VALUES (?, ?, 'email', ?, ?)
        """,
        UUID.randomUUID(),
        accountId,
        email,
        now);
    return accountId;
  }
}
