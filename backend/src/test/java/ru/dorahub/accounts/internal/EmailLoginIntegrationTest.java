package ru.dorahub.accounts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.dorahub.accounts.AccountIdentityChanged;
import ru.dorahub.accounts.EmailLoginCodeIssued;

@RecordApplicationEvents
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class EmailLoginIntegrationTest {

  private static final String EMAIL = "Player@Example.COM";

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Autowired private WebApplicationContext applicationContext;
  @Autowired private SessionRepositoryFilter<?> sessionFilter;
  @Autowired private FilterChainProxy securityFilter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationEvents events;
  @Autowired private AccountIdentities identities;
  @Autowired private TransactionTemplate transactions;
  @Autowired private Clock clock;

  private MockMvc mockMvc;

  @DynamicPropertySource
  static void totpProperties(DynamicPropertyRegistry properties) {
    properties.add("dorahub.auth.totp.enabled", () -> "true");
    properties.add(
        "dorahub.auth.totp.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
  }

  @BeforeEach
  void setUp() {
    mockMvc =
        webAppContextSetup(applicationContext).addFilters(sessionFilter, securityFilter).build();
  }

  @Test
  void protectsOneTimeCodesAndCreatesOneRotatedSession() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/identities"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("authentication.required"));

    var session = startSession();
    assertThat(session.setCookie()).contains("HttpOnly", "SameSite=Lax");

    mockMvc
        .perform(
            post("/api/v1/auth/email/code")
                .cookie(session.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailBody()))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("access.denied"));

    requestCode(session);
    var firstCode = issuedCodes().getFirst();
    requestCode(session);
    assertThat(issuedCodes()).hasSize(1);

    verify(session, wrong(firstCode.code())).andExpect(status().isUnprocessableContent());
    var login = verify(session, firstCode.code()).andExpect(status().isOk()).andReturn();
    var accountId = jsonValue(login.getResponse().getContentAsString(), "accountId");
    var rotatedCookie = sessionCookie(login);

    assertThat(rotatedCookie.getValue()).isNotEqualTo(session.cookie().getValue());
    var authenticated = session.withCookie(rotatedCookie);
    verify(authenticated, firstCode.code()).andExpect(status().isUnprocessableContent());

    assertThat(
            jdbc.queryForObject(
                "SELECT subject FROM account_identity WHERE provider = 'email'", String.class))
        .isEqualTo("player@example.com");
    assertThat(
            jdbc.queryForObject(
                "SELECT code_hash FROM email_login_code WHERE email_normalized = 'player@example.com'",
                String.class))
        .doesNotContain(firstCode.code());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class))
        .isEqualTo(1);

    jdbc.update(
        """
        UPDATE email_login_code
        SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes'
        WHERE email_normalized = 'player@example.com'
        """);
    requestCode(authenticated);
    var expiredCode = issuedCodes().get(1);
    jdbc.update(
        """
        UPDATE email_login_code
        SET created_at = CURRENT_TIMESTAMP - INTERVAL '20 minutes',
            expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
        WHERE consumed_at IS NULL
        """);
    verify(authenticated, expiredCode.code()).andExpect(status().isUnprocessableContent());

    requestCode(authenticated);
    var bruteForcedCode = issuedCodes().get(2);
    for (var attempt = 0; attempt < 5; attempt++) {
      verify(authenticated, wrong(bruteForcedCode.code()))
          .andExpect(status().isUnprocessableContent());
    }
    verify(authenticated, bruteForcedCode.code()).andExpect(status().isUnprocessableContent());

    jdbc.update(
        """
        UPDATE email_login_code
        SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes'
        WHERE email_normalized = 'player@example.com'
        """);
    requestCode(authenticated);
    var replacedCode = issuedCodes().get(3);
    jdbc.update(
        """
        UPDATE email_login_code
        SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes'
        WHERE consumed_at IS NULL
        """);
    requestCode(authenticated);
    var replacementCode = issuedCodes().get(4);

    verify(authenticated, replacedCode.code()).andExpect(status().isUnprocessableContent());
    var secondLogin =
        verify(authenticated, replacementCode.code()).andExpect(status().isOk()).andReturn();
    assertThat(jsonValue(secondLogin.getResponse().getContentAsString(), "accountId"))
        .isEqualTo(accountId);
    authenticated = authenticated.withCookie(sessionCookie(secondLogin));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM email_login_code WHERE consumed_at IS NULL", Integer.class))
        .isZero();

    requestCode(authenticated, "linked@example.com");
    var linkCode = issuedCodes().getLast();
    mockMvc
        .perform(
            post("/api/v1/auth/identities/email")
                .cookie(authenticated.cookie())
                .header(authenticated.csrfHeader(), authenticated.csrfToken())
                .header("X-Correlation-Id", "identity-link")
                .header("X-Client-Source", "web")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"linked@example.com","code":"%s"}
                    """
                        .formatted(linkCode.code())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.primaryAccountId").value(accountId))
        .andExpect(jsonPath("$.accountMerged").value(false))
        .andExpect(jsonPath("$.created").value(true));

    var linkedIdentity =
        jdbc.queryForObject(
            "SELECT id FROM account_identity WHERE provider = 'email' AND subject = 'linked@example.com'",
            UUID.class);
    mockMvc
        .perform(get("/api/v1/auth/identities").cookie(authenticated.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.primaryAccountId").value(accountId))
        .andExpect(jsonPath("$.identities.length()").value(2));

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/v1/auth/identities/{identityId}", linkedIdentity)
                .cookie(authenticated.cookie())
                .header(authenticated.csrfHeader(), authenticated.csrfToken())
                .header("X-Correlation-Id", "identity-unlink"))
        .andExpect(status().isNoContent());
    var lastIdentity =
        jdbc.queryForObject(
            "SELECT id FROM account_identity WHERE user_id = ?",
            UUID.class,
            UUID.fromString(accountId));
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/v1/auth/identities/{identityId}", lastIdentity)
                .cookie(authenticated.cookie())
                .header(authenticated.csrfHeader(), authenticated.csrfToken()))
        .andExpect(status().isConflict());

    assertThat(
            jdbc.queryForList(
                """
                SELECT action, correlation_id
                FROM account_identity_audit
                WHERE account_id = ?
                ORDER BY created_at
                """,
                UUID.fromString(accountId)))
        .extracting(row -> row.get("action"))
        .containsExactly("linked", "unlinked");
    assertThat(events.stream(AccountIdentityChanged.class).toList()).hasSize(2);

    authenticated = verifyTotpLifecycle(authenticated, lastIdentity);

    var otherAccount =
        transactions.execute(
            status -> identities.accountFor("email", "other-owner@example.com", clock.instant()));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                identities.link(
                    otherAccount,
                    "email",
                    "player@example.com",
                    new AccountIdentities.Audit("conflict", "test"),
                    clock.instant()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("409 CONFLICT");

    assertConcurrentLinkHasOneOwner();
  }

  private Session verifyTotpLifecycle(Session session, UUID lastIdentity) throws Exception {
    var enrollment =
        mockMvc
            .perform(
                post("/api/v1/auth/totp/enrollment")
                    .cookie(session.cookie())
                    .header(session.csrfHeader(), session.csrfToken()))
            .andExpect(status().isOk())
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                    .string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andReturn();
    var provisioningUri =
        jsonValue(enrollment.getResponse().getContentAsString(), "provisioningUri");
    var secret =
        decodeBase32(
            UriComponentsBuilder.fromUri(URI.create(provisioningUri))
                .build()
                .getQueryParams()
                .getFirst("secret"));
    var validCode = TotpService.hotp(secret, clock.instant().getEpochSecond() / 30, 6);
    var invalidCode = validCode.equals("000000") ? "000001" : "000000";
    for (var attempt = 0; attempt < 5; attempt++) {
      confirmTotp(session, invalidCode).andExpect(status().isUnprocessableContent());
    }
    confirmTotp(session, validCode).andExpect(status().isTooManyRequests());
    jdbc.update(
        """
        UPDATE account_totp
        SET locked_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
        """);

    var confirmed = confirmTotp(session, validCode).andExpect(status().isOk()).andReturn();
    var recoveryCode =
        firstArrayValue(confirmed.getResponse().getContentAsString(), "recoveryCodes");
    assertThat(recoveryCode).hasSize(22);
    assertThat(jdbc.queryForObject("SELECT encrypted_secret FROM account_totp", byte[].class))
        .hasSizeGreaterThan(secret.length)
        .isNotEqualTo(secret);
    assertThat(
            jdbc.queryForObject(
                "SELECT code_hash FROM account_recovery_code LIMIT 1", String.class))
        .doesNotContain(recoveryCode);
    mockMvc
        .perform(get("/api/v1/auth/totp").cookie(session.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("enabled"))
        .andExpect(jsonPath("$.recoveryCodesRemaining").value(10));

    jdbc.update(
        """
        UPDATE email_login_code
        SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes'
        WHERE email_normalized = 'player@example.com'
        """);
    requestCode(session);
    var reauthenticationCode = issuedCodes().getLast();
    var login = verify(session, reauthenticationCode.code()).andExpect(status().isOk()).andReturn();
    session = session.withCookie(sessionCookie(login));

    deleteIdentity(session, lastIdentity).andExpect(status().isForbidden());
    verifySecondFactor(session, recoveryCode).andExpect(status().isOk());
    verifySecondFactor(session, recoveryCode).andExpect(status().isUnprocessableContent());
    deleteIdentity(session, lastIdentity).andExpect(status().isConflict());

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/v1/auth/totp")
                .cookie(session.cookie())
                .header(session.csrfHeader(), session.csrfToken()))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/v1/auth/totp").cookie(session.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("disabled"));
    assertThat(
            jdbc
                .queryForList("SELECT action FROM account_security_audit ORDER BY created_at")
                .stream()
                .map(row -> row.get("action")))
        .containsExactly(
            "totp_enrollment_started", "totp_enabled", "recovery_used", "totp_disabled");
    return session;
  }

  private org.springframework.test.web.servlet.ResultActions confirmTotp(
      Session session, String code) throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/totp/enrollment/confirm")
            .cookie(session.cookie())
            .header(session.csrfHeader(), session.csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + code + "\"}"));
  }

  private org.springframework.test.web.servlet.ResultActions verifySecondFactor(
      Session session, String code) throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/totp/verify")
            .cookie(session.cookie())
            .header(session.csrfHeader(), session.csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + code + "\"}"));
  }

  private org.springframework.test.web.servlet.ResultActions deleteIdentity(
      Session session, UUID identityId) throws Exception {
    return mockMvc.perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/v1/auth/identities/{identityId}", identityId)
            .cookie(session.cookie())
            .header(session.csrfHeader(), session.csrfToken()));
  }

  private Session startSession() throws Exception {
    var result = mockMvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
    var body = result.getResponse().getContentAsString();
    return new Session(
        sessionCookie(result),
        jsonValue(body, "headerName"),
        jsonValue(body, "token"),
        result.getResponse().getHeader("Set-Cookie"));
  }

  private Cookie sessionCookie(org.springframework.test.web.servlet.MvcResult result) {
    var headers = result.getResponse().getHeaders("Set-Cookie");
    return headers.stream()
        .map(value -> value.split(";", 2)[0].split("=", 2))
        .filter(pair -> pair[0].equals("DORAHUB_SESSION") && pair.length == 2 && !pair[1].isBlank())
        .map(pair -> new Cookie(pair[0], pair[1]))
        .reduce((first, last) -> last)
        .orElseThrow(() -> new AssertionError("No session cookie in " + headers));
  }

  private void requestCode(Session session) throws Exception {
    requestCode(session, EMAIL);
  }

  private void requestCode(Session session, String email) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/email/code")
                .cookie(session.cookie())
                .header(session.csrfHeader(), session.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"))
        .andExpect(status().isAccepted());
  }

  private void assertConcurrentLinkHasOneOwner() throws Exception {
    var first =
        transactions.execute(
            status -> identities.accountFor("email", "race-one@example.com", clock.instant()));
    var second =
        transactions.execute(
            status -> identities.accountFor("email", "race-two@example.com", clock.instant()));
    try (var executor = Executors.newFixedThreadPool(2)) {
      var results =
          executor.invokeAll(
              java.util.List.<java.util.concurrent.Callable<Integer>>of(
                  () -> linkStatus(first, "race-subject"),
                  () -> linkStatus(second, "race-subject")));
      var statuses = new HashSet<Integer>();
      for (var result : results) {
        statuses.add(result.get());
      }
      assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_identity WHERE provider = 'vk' AND subject = 'race-subject'",
                Integer.class))
        .isEqualTo(1);
  }

  private int linkStatus(UUID accountId, String subject) {
    try {
      identities.link(
          accountId,
          "vk",
          subject,
          new AccountIdentities.Audit("race", "test"),
          Instant.now(clock));
      return 200;
    } catch (org.springframework.web.server.ResponseStatusException exception) {
      return exception.getStatusCode().value();
    }
  }

  private org.springframework.test.web.servlet.ResultActions verify(Session session, String code)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/email/verify")
            .cookie(session.cookie())
            .header(session.csrfHeader(), session.csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + EMAIL + "\",\"code\":\"" + code + "\"}"));
  }

  private java.util.List<EmailLoginCodeIssued> issuedCodes() {
    return events.stream(EmailLoginCodeIssued.class).toList();
  }

  private String emailBody() {
    return "{\"email\":\"" + EMAIL + "\"}";
  }

  private String wrong(String code) {
    return code.equals("000000") ? "000001" : "000000";
  }

  private String jsonValue(String json, String name) {
    var matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(json);
    assertThat(matcher.find()).as("JSON field %s in %s", name, json).isTrue();
    return matcher.group(1);
  }

  private String firstArrayValue(String json, String name) {
    var matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\\[\"([^\"]+)\"").matcher(json);
    assertThat(matcher.find()).as("JSON array %s in %s", name, json).isTrue();
    return matcher.group(1);
  }

  private byte[] decodeBase32(String encoded) {
    var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    var output = new java.io.ByteArrayOutputStream();
    var buffer = 0;
    var bits = 0;
    for (var character : encoded.toCharArray()) {
      buffer = (buffer << 5) | alphabet.indexOf(character);
      bits += 5;
      if (bits >= 8) {
        output.write((buffer >> (bits -= 8)) & 0xff);
      }
    }
    return output.toByteArray();
  }

  private record Session(Cookie cookie, String csrfHeader, String csrfToken, String setCookie) {
    private Session withCookie(Cookie replacement) {
      return new Session(replacement, csrfHeader, csrfToken, setCookie);
    }
  }
}
