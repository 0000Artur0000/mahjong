package ru.dorahub.accounts.internal;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

class TotpService {

  private static final int SECRET_BYTES = 20;
  private static final int RECOVERY_CODES = 10;
  private static final int MAX_ATTEMPTS = 5;
  private static final Duration LOCK_DURATION = Duration.ofMinutes(5);
  private static final long PERIOD_SECONDS = 30;

  private final JdbcTemplate jdbc;
  private final SecretKeySpec encryptionKey;
  private final SecureRandom random;
  private final Clock clock;

  TotpService(JdbcTemplate jdbc, String encodedKey, SecureRandom random, Clock clock) {
    this.jdbc = jdbc;
    this.encryptionKey = encryptionKey(encodedKey);
    this.random = random;
    this.clock = clock;
  }

  @Transactional
  Enrollment start(UUID accountId, AccountIdentities.Audit audit) {
    lock(accountId);
    requireActive(accountId);
    var enabled =
        jdbc.queryForObject(
            "SELECT count(*) FROM account_totp WHERE account_id = ? AND status = 'enabled'",
            Integer.class,
            accountId);
    if (enabled != null && enabled > 0) {
      throw conflict("TOTP is already enabled");
    }

    var secret = randomBytes(SECRET_BYTES);
    var now = clock.instant();
    jdbc.update(
        """
        INSERT INTO account_totp
            (account_id, encrypted_secret, status, failed_attempts, locked_until,
             last_used_counter, created_at, enabled_at)
        VALUES (?, ?, 'pending', 0, NULL, NULL, ?, NULL)
        ON CONFLICT (account_id) DO UPDATE
        SET encrypted_secret = EXCLUDED.encrypted_secret,
            status = 'pending',
            failed_attempts = 0,
            locked_until = NULL,
            last_used_counter = NULL,
            created_at = EXCLUDED.created_at,
            enabled_at = NULL
        """,
        accountId,
        encrypt(accountId, secret),
        timestamp(now));
    jdbc.update("DELETE FROM account_recovery_code WHERE account_id = ?", accountId);
    audit(accountId, "totp_enrollment_started", audit, now);
    return new Enrollment(provisioningUri(accountId, secret));
  }

  @Transactional(noRollbackFor = ResponseStatusException.class)
  List<String> confirm(UUID accountId, String code, AccountIdentities.Audit audit) {
    var row = load(accountId, "pending");
    var counter = verifyTotp(accountId, row, code);
    var now = clock.instant();
    jdbc.update(
        """
        UPDATE account_totp
        SET status = 'enabled', enabled_at = ?, last_used_counter = ?,
            failed_attempts = 0, locked_until = NULL
        WHERE account_id = ?
        """,
        timestamp(now),
        counter,
        accountId);
    var codes = recoveryCodes();
    for (var recoveryCode : codes) {
      jdbc.update(
          """
          INSERT INTO account_recovery_code (id, account_id, code_hash, created_at)
          VALUES (?, ?, ?, ?)
          """,
          UUID.randomUUID(),
          accountId,
          hash(recoveryCode),
          timestamp(now));
    }
    audit(accountId, "totp_enabled", audit, now);
    return codes;
  }

  @Transactional(noRollbackFor = ResponseStatusException.class)
  Factor verify(UUID accountId, String credential, AccountIdentities.Audit audit) {
    var row = load(accountId, "enabled");
    if (credential.matches("[0-9]{6}")) {
      var counter = verifyTotp(accountId, row, credential);
      jdbc.update(
          """
          UPDATE account_totp
          SET last_used_counter = ?, failed_attempts = 0, locked_until = NULL
          WHERE account_id = ?
          """,
          counter,
          accountId);
      return Factor.TOTP;
    }
    if (credential.matches("[A-Za-z0-9_-]{22}")) {
      var now = clock.instant();
      var updated =
          jdbc.update(
              """
              UPDATE account_recovery_code
              SET used_at = ?
              WHERE account_id = ? AND code_hash = ? AND used_at IS NULL
              """,
              timestamp(now),
              accountId,
              hash(credential));
      if (updated == 1) {
        resetAttempts(accountId);
        audit(accountId, "recovery_used", audit, now);
        return Factor.RECOVERY;
      }
    }
    fail(accountId, row);
    throw invalid();
  }

  @Transactional
  void disable(UUID accountId, AccountIdentities.Audit audit) {
    lock(accountId);
    var removed =
        jdbc.update(
            "DELETE FROM account_totp WHERE account_id = ? AND status = 'enabled'", accountId);
    if (removed == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TOTP is not enabled");
    }
    jdbc.update("DELETE FROM account_recovery_code WHERE account_id = ?", accountId);
    audit(accountId, "totp_disabled", audit, clock.instant());
  }

  Status status(UUID accountId) {
    return jdbc.query(
        """
        SELECT status,
               (SELECT count(*) FROM account_recovery_code r
                WHERE r.account_id = t.account_id AND r.used_at IS NULL) AS recovery_codes
        FROM account_totp t
        WHERE account_id = ?
        """,
        resultSet ->
            resultSet.next()
                ? new Status(resultSet.getString("status"), resultSet.getInt("recovery_codes"))
                : new Status("disabled", 0),
        accountId);
  }

  private TotpRow load(UUID accountId, String status) {
    var rows =
        jdbc.query(
            """
            SELECT encrypted_secret, failed_attempts, locked_until, last_used_counter
            FROM account_totp
            WHERE account_id = ? AND status = ?
            FOR UPDATE
            """,
            (resultSet, rowNumber) ->
                new TotpRow(
                    resultSet.getBytes("encrypted_secret"),
                    resultSet.getInt("failed_attempts"),
                    resultSet.getObject("locked_until", OffsetDateTime.class),
                    resultSet.getObject("last_used_counter", Long.class)),
            accountId,
            status);
    if (rows.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TOTP is not available");
    }
    var row = rows.getFirst();
    if (row.lockedUntil() != null && row.lockedUntil().toInstant().isAfter(clock.instant())) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "TOTP attempts are locked");
    }
    if (row.lockedUntil() != null) {
      resetAttempts(accountId);
      return new TotpRow(row.encryptedSecret(), 0, null, row.lastUsedCounter());
    }
    return row;
  }

  private long verifyTotp(UUID accountId, TotpRow row, String code) {
    if (code == null || !code.matches("[0-9]{6}")) {
      fail(accountId, row);
      throw invalid();
    }
    var secret = decrypt(accountId, row.encryptedSecret());
    var current = clock.instant().getEpochSecond() / PERIOD_SECONDS;
    for (var counter : new long[] {current, current - 1, current + 1}) {
      if ((row.lastUsedCounter() == null || counter > row.lastUsedCounter())
          && MessageDigest.isEqual(
              hotp(secret, counter, 6).getBytes(StandardCharsets.US_ASCII),
              code.getBytes(StandardCharsets.US_ASCII))) {
        return counter;
      }
    }
    fail(accountId, row);
    throw invalid();
  }

  private void fail(UUID accountId, TotpRow row) {
    var attempts = Math.min(MAX_ATTEMPTS, row.failedAttempts() + 1);
    var lockedUntil =
        attempts == MAX_ATTEMPTS ? timestamp(clock.instant().plus(LOCK_DURATION)) : null;
    jdbc.update(
        "UPDATE account_totp SET failed_attempts = ?, locked_until = ? WHERE account_id = ?",
        attempts,
        lockedUntil,
        accountId);
  }

  private void resetAttempts(UUID accountId) {
    jdbc.update(
        "UPDATE account_totp SET failed_attempts = 0, locked_until = NULL WHERE account_id = ?",
        accountId);
  }

  private void audit(UUID accountId, String action, AccountIdentities.Audit audit, Instant now) {
    jdbc.update(
        """
        INSERT INTO account_security_audit
            (id, account_id, action, correlation_id, source, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        accountId,
        action,
        audit.correlationId(),
        audit.source(),
        timestamp(now));
  }

  private void lock(UUID accountId) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> null,
        "account:" + accountId);
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

  private byte[] encrypt(UUID accountId, byte[] secret) {
    try {
      var nonce = randomBytes(12);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(accountId.toString().getBytes(StandardCharsets.US_ASCII));
      var ciphertext = cipher.doFinal(secret);
      return ByteBuffer.allocate(1 + nonce.length + ciphertext.length)
          .put((byte) 1)
          .put(nonce)
          .put(ciphertext)
          .array();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Cannot encrypt TOTP secret", exception);
    }
  }

  private byte[] decrypt(UUID accountId, byte[] encrypted) {
    if (encrypted == null || encrypted.length < 30 || encrypted[0] != 1) {
      throw new IllegalStateException("Unsupported TOTP secret");
    }
    try {
      var nonce = java.util.Arrays.copyOfRange(encrypted, 1, 13);
      var ciphertext = java.util.Arrays.copyOfRange(encrypted, 13, encrypted.length);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(accountId.toString().getBytes(StandardCharsets.US_ASCII));
      return cipher.doFinal(ciphertext);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Cannot decrypt TOTP secret", exception);
    }
  }

  private SecretKeySpec encryptionKey(String encoded) {
    try {
      var key = Base64.getDecoder().decode(encoded);
      if (key.length != 32) {
        throw new IllegalArgumentException("TOTP encryption key must contain 32 bytes");
      }
      return new SecretKeySpec(key, "AES");
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Invalid TOTP encryption key", exception);
    }
  }

  private List<String> recoveryCodes() {
    var codes = new ArrayList<String>(RECOVERY_CODES);
    for (var index = 0; index < RECOVERY_CODES; index++) {
      codes.add(Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(16)));
    }
    return List.copyOf(codes);
  }

  private String provisioningUri(UUID accountId, byte[] secret) {
    var label = URLEncoder.encode("Dorahub:" + accountId, StandardCharsets.UTF_8);
    return "otpauth://totp/%s?secret=%s&issuer=Dorahub&algorithm=SHA1&digits=6&period=30"
        .formatted(label, base32(secret));
  }

  static String hotp(byte[] secret, long counter, int digits) {
    try {
      var mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      var hmac = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
      var offset = hmac[hmac.length - 1] & 0x0f;
      var binary =
          ((hmac[offset] & 0x7f) << 24)
              | ((hmac[offset + 1] & 0xff) << 16)
              | ((hmac[offset + 2] & 0xff) << 8)
              | (hmac[offset + 3] & 0xff);
      return ("%0" + digits + "d").formatted(binary % (int) Math.pow(10, digits));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  static String base32(byte[] value) {
    var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    var output = new StringBuilder((value.length * 8 + 4) / 5);
    var buffer = 0;
    var bits = 0;
    for (var item : value) {
      buffer = (buffer << 8) | (item & 0xff);
      bits += 8;
      while (bits >= 5) {
        output.append(alphabet.charAt((buffer >> (bits -= 5)) & 31));
      }
    }
    if (bits > 0) {
      output.append(alphabet.charAt((buffer << (5 - bits)) & 31));
    }
    return output.toString();
  }

  private byte[] randomBytes(int size) {
    var value = new byte[size];
    random.nextBytes(value);
    return value;
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

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid security code");
  }

  enum Factor {
    TOTP,
    RECOVERY
  }

  record Enrollment(String provisioningUri) {}

  record Status(String state, int recoveryCodesRemaining) {}

  private record TotpRow(
      byte[] encryptedSecret,
      int failedAttempts,
      OffsetDateTime lockedUntil,
      Long lastUsedCounter) {}
}
