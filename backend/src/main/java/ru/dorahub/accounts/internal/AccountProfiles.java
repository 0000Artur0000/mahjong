package ru.dorahub.accounts.internal;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ru.dorahub.system.BackgroundJob;

class AccountProfiles {

  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AccountProfiles.class);
  private static final Pattern NICKNAME = Pattern.compile("[\\p{L}\\p{N}_-]{3,32}");

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Clock clock;

  AccountProfiles(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.clock = clock;
  }

  PublicProfile publicProfile(UUID accountId) {
    return jdbc
        .query(
            """
            SELECT id, nickname, CASE WHEN show_city THEN city END AS city
            FROM app_user
            WHERE id = ? AND status = 'active'
            """,
            (resultSet, rowNumber) ->
                new PublicProfile(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("nickname"),
                    resultSet.getString("city")),
            accountId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found"));
  }

  PrivateProfile privateProfile(UUID accountId) {
    return jdbc
        .query(
            """
            SELECT id, status, role, nickname, city, avatar_media_id, show_city, show_clubs
            FROM app_user
            WHERE id = ? AND status <> 'anonymized'
            """,
            (resultSet, rowNumber) ->
                new PrivateProfile(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("nickname"),
                    resultSet.getString("city"),
                    resultSet.getObject("avatar_media_id", UUID.class),
                    resultSet.getString("status"),
                    resultSet.getString("role"),
                    new Privacy(
                        resultSet.getBoolean("show_city"), resultSet.getBoolean("show_clubs"))),
            accountId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found"));
  }

  @Transactional
  PrivateProfile update(
      UUID accountId, String nickname, String city, UUID avatarMediaId, Privacy privacy) {
    var displayName = normalizeDisplayName(nickname);
    var normalizedName = displayName.toLowerCase(Locale.ROOT);
    var normalizedCity = city == null || city.isBlank() ? null : city.strip();
    if (normalizedCity != null && normalizedCity.length() > 128) {
      throw invalidProfile();
    }
    try {
      var updated =
          jdbc.update(
              """
              UPDATE app_user
              SET nickname = ?, nickname_normalized = ?, city = ?, avatar_media_id = ?,
                  show_city = ?, show_clubs = ?, updated_at = ?
              WHERE id = ? AND status = 'active'
              """,
              displayName,
              normalizedName,
              normalizedCity,
              avatarMediaId,
              privacy.showCity(),
              privacy.showClubs(),
              timestamp(clock.instant()),
              accountId);
      if (updated == 0) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Account is not active");
      }
    } catch (DuplicateKeyException exception) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Nickname is already in use");
    }
    return privateProfile(accountId);
  }

  @Transactional
  void deactivate(UUID accountId) {
    var updated =
        jdbc.update(
            """
            UPDATE app_user
            SET status = 'deactivated', updated_at = ?
            WHERE id = ? AND status = 'active'
            """,
            timestamp(clock.instant()),
            accountId);
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Account is not active");
    }
    deleteSessions(accountId);
  }

  Deletion requestDeletion(UUID accountId) {
    return transactions.execute(
        ignored -> {
          var status =
              jdbc.query(
                  "SELECT status FROM app_user WHERE id = ? FOR UPDATE",
                  resultSet -> resultSet.next() ? resultSet.getString("status") : null,
                  accountId);
          if (status == null || status.equals("anonymized")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
          }
          var existing =
              jdbc.query(
                  "SELECT id, status FROM account_cleanup_job WHERE account_id = ?",
                  (resultSet, rowNumber) ->
                      new Deletion(
                          resultSet.getObject("id", UUID.class), resultSet.getString("status")),
                  accountId);
          if (!existing.isEmpty()) {
            return existing.getFirst();
          }
          var job = new Deletion(UUID.randomUUID(), "pending");
          jdbc.update(
              """
              UPDATE app_user
              SET status = 'deactivated', updated_at = ?
              WHERE id = ?
              """,
              timestamp(clock.instant()),
              accountId);
          jdbc.update(
              """
              INSERT INTO account_cleanup_job (id, account_id, status, created_at)
              VALUES (?, ?, 'pending', ?)
              """,
              job.jobId(),
              accountId,
              timestamp(clock.instant()));
          deleteSessions(accountId);
          return job;
        });
  }

  @Scheduled(
      initialDelayString = "${dorahub.accounts.cleanup-delay:PT10M}",
      fixedDelayString = "${dorahub.accounts.cleanup-delay:PT10M}")
  void scheduledCleanup() {
    BackgroundJob.run("accounts.cleanup", this::processNext);
  }

  boolean processNext() {
    var jobId =
        transactions.execute(
            ignored -> {
              var jobs =
                  jdbc.query(
                      """
                      SELECT id
                      FROM account_cleanup_job
                      WHERE status = 'pending'
                         OR (status = 'running' AND started_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes')
                      ORDER BY created_at
                      FOR UPDATE SKIP LOCKED
                      LIMIT 1
                      """,
                      (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
              if (jobs.isEmpty()) {
                return null;
              }
              var id = jobs.getFirst();
              jdbc.update(
                  """
                  UPDATE account_cleanup_job
                  SET status = 'running', started_at = ?, completed_at = NULL
                  WHERE id = ?
                  """,
                  timestamp(clock.instant()),
                  id);
              return id;
            });
    if (jobId == null) {
      return false;
    }
    try {
      transactions.executeWithoutResult(ignored -> clean(jobId));
    } catch (RuntimeException exception) {
      LOG.error("Account cleanup failed for job {}", jobId, exception);
      transactions.executeWithoutResult(
          ignored ->
              jdbc.update(
                  """
                  UPDATE account_cleanup_job
                  SET status = 'failed', report = '{"error":"cleanup.failed"}'::jsonb,
                      completed_at = ?
                  WHERE id = ?
                  """,
                  timestamp(clock.instant()),
                  jobId));
    }
    return true;
  }

  private void clean(UUID jobId) {
    var accountId =
        jdbc.queryForObject(
            "SELECT account_id FROM account_cleanup_job WHERE id = ? AND status = 'running'",
            UUID.class,
            jobId);
    var loginCodes =
        jdbc.update(
            """
            DELETE FROM email_login_code
            WHERE email_normalized IN (
                SELECT subject FROM account_identity WHERE user_id = ? AND provider = 'email'
            )
            """,
            accountId);
    var externalAttempts =
        jdbc.update("DELETE FROM external_login_attempt WHERE link_account_id = ?", accountId);
    var recoveryCodes =
        jdbc.update("DELETE FROM account_recovery_code WHERE account_id = ?", accountId);
    var totp = jdbc.update("DELETE FROM account_totp WHERE account_id = ?", accountId);
    var identities = jdbc.update("DELETE FROM account_identity WHERE user_id = ?", accountId);
    var idempotency = jdbc.update("DELETE FROM idempotency_record WHERE actor_id = ?", accountId);
    var sessions = deleteSessions(accountId);
    jdbc.update("DELETE FROM account_nickname_history WHERE account_id = ?", accountId);
    var anonymousNickname = "deleted_" + accountId.toString().replace("-", "");
    jdbc.update(
        """
        UPDATE app_user
        SET status = 'anonymized', nickname = ?, nickname_normalized = ?,
            city = NULL, avatar_media_id = NULL, show_city = FALSE, show_clubs = FALSE,
            updated_at = ?
        WHERE id = ?
        """,
        anonymousNickname,
        anonymousNickname,
        timestamp(clock.instant()),
        accountId);
    jdbc.update(
        """
        UPDATE account_cleanup_job
        SET status = 'completed',
            report = jsonb_build_object(
                'identities', ?, 'loginCodes', ?, 'externalLoginAttempts', ?,
                'recoveryCodes', ?, 'totp', ?, 'idempotencyRecords', ?, 'sessions', ?
            ),
            completed_at = ?
        WHERE id = ?
        """,
        identities,
        loginCodes,
        externalAttempts,
        recoveryCodes,
        totp,
        idempotency,
        sessions,
        timestamp(clock.instant()),
        jobId);
  }

  private int deleteSessions(UUID accountId) {
    return jdbc.update("DELETE FROM spring_session WHERE principal_name = ?", accountId.toString());
  }

  private String normalizeDisplayName(String nickname) {
    if (nickname == null) {
      throw invalidProfile();
    }
    var normalized = Normalizer.normalize(nickname.strip(), Normalizer.Form.NFKC);
    if (!NICKNAME.matcher(normalized).matches()) {
      throw invalidProfile();
    }
    return normalized;
  }

  private ResponseStatusException invalidProfile() {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid profile");
  }

  private OffsetDateTime timestamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  record Privacy(boolean showCity, boolean showClubs) {}

  record PublicProfile(UUID accountId, String nickname, String city) {}

  /** {@code role} нужен экрану: без него неоткуда узнать, показывать ли откат раздачи. */
  record PrivateProfile(
      UUID accountId,
      String nickname,
      String city,
      UUID avatarMediaId,
      String status,
      String role,
      Privacy privacy) {}

  record Deletion(UUID jobId, String status) {}
}
