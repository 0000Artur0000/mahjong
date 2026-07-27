package ru.dorahub.accounts.internal;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

class ExternalLoginService {

  private static final Duration ATTEMPT_TTL = Duration.ofMinutes(10);

  private final JdbcTemplate jdbc;
  private final AccountIdentities identities;
  private final TransactionTemplate transactions;
  private final RestClient rest;
  private final JwtDecoder telegramJwtDecoder;
  private final ExternalLoginProperties properties;
  private final Clock clock;
  private final SecureRandom random;

  ExternalLoginService(
      JdbcTemplate jdbc,
      AccountIdentities identities,
      TransactionTemplate transactions,
      RestClient rest,
      JwtDecoder telegramJwtDecoder,
      ExternalLoginProperties properties,
      Clock clock,
      SecureRandom random) {
    this.jdbc = jdbc;
    this.identities = identities;
    this.transactions = transactions;
    this.rest = rest;
    this.telegramJwtDecoder = telegramJwtDecoder;
    this.properties = properties;
    this.clock = clock;
    this.random = random;
  }

  URI start(Provider provider, String sessionId) {
    return start(provider, sessionId, null, null);
  }

  URI startLink(
      Provider provider, String sessionId, UUID accountId, AccountIdentities.Audit audit) {
    return start(provider, sessionId, accountId, audit);
  }

  private URI start(
      Provider provider, String sessionId, UUID linkAccountId, AccountIdentities.Audit audit) {
    var state = randomToken(32);
    var verifier = randomToken(48);
    var nonce = provider == Provider.TELEGRAM ? randomToken(32) : null;
    var now = clock.instant();
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "DELETE FROM external_login_attempt WHERE expires_at < ?",
              timestamp(now.minus(Duration.ofDays(1))));
          jdbc.update(
              """
                INSERT INTO external_login_attempt
                    (id, provider, state_hash, session_id_hash, code_verifier, nonce_hash,
                     expires_at, created_at, link_account_id, audit_correlation_id, audit_source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
              UUID.randomUUID(),
              provider.id,
              hash(state),
              hash(sessionId),
              verifier,
              nonce == null ? null : hash(nonce),
              timestamp(now.plus(ATTEMPT_TTL)),
              timestamp(now),
              linkAccountId,
              audit == null ? null : audit.correlationId(),
              audit == null ? null : audit.source());
        });
    return authorizationUri(provider, state, verifier, nonce);
  }

  Completion completeTelegram(String state, String code, String sessionId) {
    var attempt = consume(Provider.TELEGRAM, state, sessionId);
    try {
      var form = new LinkedMultiValueMap<String, String>();
      form.add("grant_type", "authorization_code");
      form.add("code", code);
      form.add("redirect_uri", callbackUri(Provider.TELEGRAM).toString());
      form.add("client_id", properties.telegram().clientId());
      form.add("code_verifier", attempt.codeVerifier());
      var token =
          rest.post()
              .uri(properties.telegram().tokenUri())
              .headers(
                  headers ->
                      headers.setBasicAuth(
                          properties.telegram().clientId(),
                          properties.telegram().clientSecret(),
                          StandardCharsets.UTF_8))
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(TelegramToken.class);
      if (token == null || token.id_token() == null) {
        throw invalid();
      }
      var jwt = telegramJwtDecoder.decode(token.id_token());
      validateTelegram(jwt, attempt.nonceHash());
      return completeIdentity(Provider.TELEGRAM, jwt.getSubject(), attempt);
    } catch (JwtException exception) {
      throw invalid();
    } catch (RestClientException exception) {
      throw providerError(exception);
    }
  }

  Completion completeVk(String state, String code, String deviceId, String sessionId) {
    var attempt = consume(Provider.VK, state, sessionId);
    try {
      var form = new LinkedMultiValueMap<String, String>();
      form.add("grant_type", "authorization_code");
      form.add("code", code);
      form.add("redirect_uri", callbackUri(Provider.VK).toString());
      form.add("client_id", properties.vk().clientId());
      form.add("code_verifier", attempt.codeVerifier());
      form.add("state", state);
      form.add("device_id", deviceId);
      var token =
          rest.post()
              .uri(properties.vk().tokenUri())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(VkToken.class);
      if (token == null
          || token.access_token() == null
          || token.state() == null
          || !constantTimeEquals(state, token.state())) {
        throw invalid();
      }

      var userForm = new LinkedMultiValueMap<String, String>();
      userForm.add("access_token", token.access_token());
      userForm.add("client_id", properties.vk().clientId());
      var info =
          rest.post()
              .uri(properties.vk().userInfoUri())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(userForm)
              .retrieve()
              .body(VkUserInfo.class);
      if (info == null
          || info.user() == null
          || info.user().user_id() == null
          || !info.user().user_id().equals(Long.toString(token.user_id()))) {
        throw invalid();
      }
      return completeIdentity(Provider.VK, info.user().user_id(), attempt);
    } catch (RestClientException exception) {
      throw providerError(exception);
    }
  }

  void cancel(Provider provider, String state, String sessionId) {
    consume(provider, state, sessionId);
    throw invalid();
  }

  private Attempt consume(Provider provider, String state, String sessionId) {
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              var attempts =
                  jdbc.query(
                      """
                      UPDATE external_login_attempt
                      SET consumed_at = ?
                      WHERE provider = ?
                        AND state_hash = ?
                        AND session_id_hash = ?
                        AND consumed_at IS NULL
                        AND expires_at > ?
                      RETURNING code_verifier, nonce_hash, link_account_id,
                                audit_correlation_id, audit_source
                      """,
                      (resultSet, rowNumber) ->
                          new Attempt(
                              resultSet.getString("code_verifier"),
                              resultSet.getString("nonce_hash"),
                              resultSet.getObject("link_account_id", UUID.class),
                              resultSet.getString("audit_correlation_id"),
                              resultSet.getString("audit_source")),
                      timestamp(clock.instant()),
                      provider.id,
                      hash(state),
                      hash(sessionId),
                      timestamp(clock.instant()));
              if (attempts.isEmpty()) {
                throw invalid();
              }
              return attempts.getFirst();
            }));
  }

  private URI authorizationUri(Provider provider, String state, String verifier, String nonce) {
    var challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest(verifier));
    var callback = callbackUri(provider);
    if (provider == Provider.TELEGRAM) {
      return UriComponentsBuilder.fromUri(properties.telegram().authorizationUri())
          .queryParam("client_id", properties.telegram().clientId())
          .queryParam("redirect_uri", callback)
          .queryParam("response_type", "code")
          .queryParam("scope", "openid profile")
          .queryParam("state", state)
          .queryParam("nonce", nonce)
          .queryParam("code_challenge", challenge)
          .queryParam("code_challenge_method", "S256")
          .build()
          .encode()
          .toUri();
    }
    return UriComponentsBuilder.fromUri(properties.vk().authorizationUri())
        .queryParam("client_id", properties.vk().clientId())
        .queryParam("redirect_uri", callback)
        .queryParam("response_type", "code")
        .queryParam("scope", "vkid.personal_info")
        .queryParam("state", state)
        .queryParam("code_challenge", challenge)
        .queryParam("code_challenge_method", "S256")
        .build()
        .encode()
        .toUri();
  }

  private URI callbackUri(Provider provider) {
    return properties.publicBaseUrl().resolve("/api/v1/auth/" + provider.id + "/callback");
  }

  private void validateTelegram(Jwt jwt, String nonceHash) {
    var now = clock.instant();
    var nonce = jwt.getClaimAsString("nonce");
    if (jwt.getSubject() == null
        || jwt.getSubject().isBlank()
        || jwt.getExpiresAt() == null
        || !jwt.getExpiresAt().isAfter(now)
        || !jwt.getAudience().contains(properties.telegram().clientId())
        || nonce == null
        || !constantTimeEquals(nonceHash, hash(nonce))) {
      throw invalid();
    }
  }

  private UUID accountFor(Provider provider, String subject) {
    return Objects.requireNonNull(
        transactions.execute(
            status -> identities.accountFor(provider.id, subject, clock.instant())));
  }

  private Completion completeIdentity(Provider provider, String subject, Attempt attempt) {
    if (attempt.linkAccountId() == null) {
      return new Completion(accountFor(provider, subject), false);
    }
    var audit = new AccountIdentities.Audit(attempt.correlationId(), attempt.source());
    var result =
        Objects.requireNonNull(
            transactions.execute(
                status ->
                    identities.link(
                        attempt.linkAccountId(), provider.id, subject, audit, clock.instant())));
    return new Completion(result.accountId(), true);
  }

  private ResponseStatusException providerError(RestClientException exception) {
    if (exception instanceof RestClientResponseException response
        && !response.getStatusCode().is5xxServerError()) {
      return invalid();
    }
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "External login provider unavailable");
  }

  private ResponseStatusException invalid() {
    return new ResponseStatusException(
        HttpStatus.UNPROCESSABLE_CONTENT, "Invalid or expired external login");
  }

  private boolean constantTimeEquals(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private String randomToken(int bytes) {
    var value = new byte[bytes];
    random.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private String hash(String value) {
    return HexFormat.of().formatHex(digest(value));
  }

  private byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private OffsetDateTime timestamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  enum Provider {
    TELEGRAM("telegram"),
    VK("vk");

    private final String id;

    Provider(String id) {
      this.id = id;
    }
  }

  record Completion(UUID accountId, boolean linked) {}

  private record Attempt(
      String codeVerifier,
      String nonceHash,
      UUID linkAccountId,
      String correlationId,
      String source) {}

  private record TelegramToken(String id_token) {}

  private record VkToken(String access_token, String state, long user_id) {}

  private record VkUserInfo(VkUser user) {}

  private record VkUser(String user_id) {}
}
