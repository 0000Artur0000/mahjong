package ru.dorahub.accounts.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.dorahub.accounts.AccountIdentityChanged;

class AccountIdentities {

  private final JdbcTemplate jdbc;
  private final ApplicationEventPublisher events;

  AccountIdentities(JdbcTemplate jdbc, ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.events = events;
  }

  UUID accountFor(String provider, String subject, Instant now) {
    validate(provider, subject);
    lock("identity:" + provider + ":" + subject);
    var accountIds =
        jdbc.query(
            "SELECT user_id FROM account_identity WHERE provider = ? AND subject = ?",
            (resultSet, rowNumber) -> resultSet.getObject("user_id", UUID.class),
            provider,
            subject);
    if (!accountIds.isEmpty()) {
      return accountIds.getFirst();
    }

    var accountId = UUID.randomUUID();
    var nickname = "player_" + accountId.toString().replace("-", "").substring(0, 12);
    jdbc.update(
        """
        INSERT INTO app_user
            (id, status, nickname, nickname_normalized, created_at, updated_at)
        VALUES (?, 'active', ?, ?, ?, ?)
        """,
        accountId,
        nickname,
        nickname,
        timestamp(now),
        timestamp(now));
    jdbc.update(
        """
        INSERT INTO account_identity (id, user_id, provider, subject, created_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        accountId,
        provider,
        subject,
        timestamp(now));
    return accountId;
  }

  List<Identity> list(UUID accountId) {
    return jdbc.query(
        """
        SELECT id, provider, subject, created_at
        FROM account_identity
        WHERE user_id = ?
        ORDER BY created_at, id
        """,
        (resultSet, rowNumber) ->
            new Identity(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("provider"),
                resultSet.getString("subject"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant()),
        accountId);
  }

  @Transactional
  LinkResult link(UUID accountId, String provider, String subject, Audit audit, Instant now) {
    validate(provider, subject);
    lock("account:" + accountId);
    requireActive(accountId);
    lock("identity:" + provider + ":" + subject);
    var existing =
        jdbc.query(
            """
            SELECT id, user_id, created_at
            FROM account_identity
            WHERE provider = ? AND subject = ?
            """,
            (resultSet, rowNumber) ->
                new ExistingIdentity(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("user_id", UUID.class),
                    resultSet.getObject("created_at", OffsetDateTime.class).toInstant()),
            provider,
            subject);
    if (!existing.isEmpty()) {
      var identity = existing.getFirst();
      if (!identity.accountId().equals(accountId)) {
        throw conflict("Identity belongs to another account");
      }
      return new LinkResult(
          accountId, new Identity(identity.id(), provider, subject, identity.createdAt()), false);
    }

    var identity = new Identity(UUID.randomUUID(), provider, subject, now);
    jdbc.update(
        """
        INSERT INTO account_identity (id, user_id, provider, subject, created_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        identity.id(),
        accountId,
        provider,
        subject,
        timestamp(now));
    audit(accountId, "linked", identity, audit, now);
    events.publishEvent(
        new AccountIdentityChanged(
            accountId, "linked", provider, notificationEmail(accountId, identity), now));
    return new LinkResult(accountId, identity, true);
  }

  @Transactional
  Identity unlink(UUID accountId, UUID identityId, Audit audit, Instant now) {
    lock("account:" + accountId);
    requireActive(accountId);
    var identities =
        jdbc.query(
            """
            SELECT id, provider, subject, created_at
            FROM account_identity
            WHERE id = ? AND user_id = ?
            FOR UPDATE
            """,
            (resultSet, rowNumber) ->
                new Identity(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("provider"),
                    resultSet.getString("subject"),
                    resultSet.getObject("created_at", OffsetDateTime.class).toInstant()),
            identityId,
            accountId);
    if (identities.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Identity was not found");
    }
    var count =
        jdbc.queryForObject(
            "SELECT count(*) FROM account_identity WHERE user_id = ?", Integer.class, accountId);
    if (count == null || count <= 1) {
      throw conflict("The last login identity cannot be removed");
    }

    var identity = identities.getFirst();
    var notificationEmail = notificationEmail(accountId, identity);
    jdbc.update("DELETE FROM account_identity WHERE id = ?", identityId);
    audit(accountId, "unlinked", identity, audit, now);
    events.publishEvent(
        new AccountIdentityChanged(
            accountId, "unlinked", identity.provider(), notificationEmail, now));
    return identity;
  }

  private void audit(UUID accountId, String action, Identity identity, Audit audit, Instant now) {
    jdbc.update(
        """
        INSERT INTO account_identity_audit
            (id, account_id, actor_id, action, identity_id, provider, subject_hash,
             correlation_id, source, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        accountId,
        accountId,
        action,
        identity.id(),
        identity.provider(),
        hash(identity.subject()),
        audit.correlationId(),
        audit.source(),
        timestamp(now));
  }

  private String notificationEmail(UUID accountId, Identity changed) {
    if (changed.provider().equals("email")) {
      return changed.subject();
    }
    return jdbc.query(
        """
        SELECT subject
        FROM account_identity
        WHERE user_id = ? AND provider = 'email'
        ORDER BY created_at
        LIMIT 1
        """,
        resultSet -> resultSet.next() ? resultSet.getString("subject") : null,
        accountId);
  }

  private void requireActive(UUID accountId) {
    var active =
        jdbc.queryForObject(
            "SELECT count(*) FROM app_user WHERE id = ? AND status = 'active'",
            Integer.class,
            accountId);
    if (active == null || active == 0) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is not active");
    }
  }

  private void validate(String provider, String subject) {
    if (provider == null
        || provider.isBlank()
        || provider.length() > 32
        || subject == null
        || subject.isBlank()
        || subject.length() > 320) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid identity");
    }
  }

  private void lock(String value) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", resultSet -> null, value);
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private OffsetDateTime timestamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  record Audit(String correlationId, String source) {}

  record Identity(UUID id, String provider, String subject, Instant createdAt) {}

  record LinkResult(UUID accountId, Identity identity, boolean created) {}

  private record ExistingIdentity(UUID id, UUID accountId, Instant createdAt) {}
}
