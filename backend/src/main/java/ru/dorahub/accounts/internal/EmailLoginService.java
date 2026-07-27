package ru.dorahub.accounts.internal;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.dorahub.accounts.EmailLoginCodeIssued;

class EmailLoginService {

  private static final Duration CODE_TTL = Duration.ofMinutes(10);
  private static final Duration RESEND_INTERVAL = Duration.ofMinutes(1);
  private static final int MAX_ATTEMPTS = 5;

  private final JdbcTemplate jdbc;
  private final AccountIdentities identities;
  private final PasswordEncoder passwordEncoder;
  private final SecureRandom random;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  EmailLoginService(
      JdbcTemplate jdbc,
      AccountIdentities identities,
      PasswordEncoder passwordEncoder,
      SecureRandom random,
      Clock clock,
      ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.identities = identities;
    this.passwordEncoder = passwordEncoder;
    this.random = random;
    this.clock = clock;
    this.events = events;
  }

  @Transactional
  void requestCode(String rawEmail) {
    var email = normalize(rawEmail);
    lock(email);
    var now = clock.instant();
    var lastCreatedAt = latestCreatedAt(email);
    if (lastCreatedAt != null && lastCreatedAt.plus(RESEND_INTERVAL).isAfter(now)) {
      return;
    }

    jdbc.update(
        "UPDATE email_login_code SET consumed_at = ? WHERE email_normalized = ? AND consumed_at IS NULL",
        timestamp(now),
        email);
    var code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    var expiresAt = now.plus(CODE_TTL);
    jdbc.update(
        """
        INSERT INTO email_login_code
            (id, email_normalized, code_hash, attempts_remaining, expires_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        email,
        passwordEncoder.encode(code),
        MAX_ATTEMPTS,
        timestamp(expiresAt),
        timestamp(now));
    events.publishEvent(new EmailLoginCodeIssued(email, code, expiresAt));
  }

  @Transactional(noRollbackFor = ResponseStatusException.class)
  UUID verify(String rawEmail, String code) {
    var email = verifyCode(rawEmail, code);
    return identities.accountFor("email", email, clock.instant());
  }

  @Transactional(noRollbackFor = ResponseStatusException.class)
  AccountIdentities.LinkResult link(
      UUID accountId, String rawEmail, String code, AccountIdentities.Audit audit) {
    var email = verifyCode(rawEmail, code);
    return identities.link(accountId, "email", email, audit, clock.instant());
  }

  private String verifyCode(String rawEmail, String code) {
    var email = normalize(rawEmail);
    lock(email);
    var storedCodes =
        jdbc.query(
            """
            SELECT id, code_hash, attempts_remaining, expires_at
            FROM email_login_code
            WHERE email_normalized = ? AND consumed_at IS NULL
            FOR UPDATE
            """,
            (resultSet, rowNumber) ->
                new StoredCode(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("code_hash"),
                    resultSet.getInt("attempts_remaining"),
                    resultSet.getObject("expires_at", OffsetDateTime.class).toInstant()),
            email);
    if (storedCodes.isEmpty()) {
      throw invalidCode();
    }

    var stored = storedCodes.getFirst();
    var now = clock.instant();
    if (!stored.expiresAt().isAfter(now)) {
      consume(stored.id(), now, stored.attemptsRemaining());
      throw invalidCode();
    }
    if (!passwordEncoder.matches(code, stored.hash())) {
      var remaining = stored.attemptsRemaining() - 1;
      if (remaining == 0) {
        consume(stored.id(), now, 0);
      } else {
        jdbc.update(
            "UPDATE email_login_code SET attempts_remaining = ? WHERE id = ?",
            remaining,
            stored.id());
      }
      throw invalidCode();
    }

    consume(stored.id(), now, stored.attemptsRemaining());
    return email;
  }

  private void consume(UUID id, Instant now, int attemptsRemaining) {
    jdbc.update(
        "UPDATE email_login_code SET consumed_at = ?, attempts_remaining = ? WHERE id = ?",
        timestamp(now),
        attemptsRemaining,
        id);
  }

  private Instant latestCreatedAt(String email) {
    return jdbc.query(
        "SELECT created_at FROM email_login_code WHERE email_normalized = ? ORDER BY created_at DESC LIMIT 1",
        resultSet ->
            resultSet.next()
                ? resultSet.getObject("created_at", OffsetDateTime.class).toInstant()
                : null,
        email);
  }

  private void lock(String email) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", resultSet -> null, email);
  }

  private String normalize(String email) {
    return Normalizer.normalize(email, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
  }

  private OffsetDateTime timestamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private ResponseStatusException invalidCode() {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid or expired code");
  }

  private record StoredCode(UUID id, String hash, int attemptsRemaining, Instant expiresAt) {}
}
