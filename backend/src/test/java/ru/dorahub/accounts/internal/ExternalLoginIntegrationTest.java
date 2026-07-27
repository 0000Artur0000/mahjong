package ru.dorahub.accounts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ExternalLoginIntegrationTest {

  private static final ProviderServer PROVIDER = ProviderServer.start();

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Autowired private WebApplicationContext applicationContext;
  @Autowired private SessionRepositoryFilter<?> sessionFilter;
  @Autowired private FilterChainProxy securityFilter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLoginService emailLoginService;

  private MockMvc mockMvc;

  @DynamicPropertySource
  static void externalLoginProperties(DynamicPropertyRegistry properties) {
    properties.add("dorahub.auth.external.enabled", () -> "true");
    properties.add("dorahub.auth.external.public-base-url", () -> "https://app.example.test");
    properties.add("dorahub.auth.external.telegram.client-id", () -> "telegram-client");
    properties.add("dorahub.auth.external.telegram.client-secret", () -> "telegram-secret");
    properties.add(
        "dorahub.auth.external.telegram.authorization-uri",
        () -> PROVIDER.uri("/telegram/authorize"));
    properties.add(
        "dorahub.auth.external.telegram.token-uri", () -> PROVIDER.uri("/telegram/token"));
    properties.add("dorahub.auth.external.telegram.jwk-set-uri", () -> PROVIDER.uri("/jwks"));
    properties.add("dorahub.auth.external.telegram.issuer", () -> "https://oauth.telegram.test");
    properties.add("dorahub.auth.external.vk.client-id", () -> "vk-client");
    properties.add(
        "dorahub.auth.external.vk.authorization-uri", () -> PROVIDER.uri("/vk/authorize"));
    properties.add("dorahub.auth.external.vk.token-uri", () -> PROVIDER.uri("/vk/token"));
    properties.add("dorahub.auth.external.vk.user-info-uri", () -> PROVIDER.uri("/vk/user"));
  }

  @BeforeEach
  void setUp() {
    PROVIDER.reset();
    mockMvc =
        webAppContextSetup(applicationContext).addFilters(sessionFilter, securityFilter).build();
  }

  @AfterAll
  static void stopProvider() {
    PROVIDER.close();
  }

  @Test
  void validatesProviderCallbacksAndKeepsEmailLoginIndependent() throws Exception {
    var telegramStart = start("/api/v1/auth/telegram/start", null);
    var telegramLocation = location(telegramStart);
    PROVIDER.telegramNonce = query(telegramLocation, "nonce");
    PROVIDER.telegramChallenge = query(telegramLocation, "code_challenge");
    assertThat(query(telegramLocation, "code_challenge_method")).isEqualTo("S256");

    var telegramLogin =
        callback(
                "/api/v1/auth/telegram/callback",
                sessionCookie(telegramStart),
                query(telegramLocation, "state"),
                "telegram-code",
                null)
            .andExpect(status().isOk())
            .andReturn();
    var authenticatedCookie = sessionCookie(telegramLogin);
    assertThat(identity("telegram")).isEqualTo("telegram-user");
    var primaryAccount =
        jdbc.queryForObject(
            "SELECT user_id FROM account_identity WHERE provider = 'telegram'", UUID.class);

    callback(
            "/api/v1/auth/telegram/callback",
            authenticatedCookie,
            query(telegramLocation, "state"),
            "telegram-code",
            null)
        .andExpect(status().isUnprocessableContent());
    assertThat(PROVIDER.telegramTokenCalls).hasValue(1);

    var forgedStart = start("/api/v1/auth/telegram/start", authenticatedCookie);
    var forgedLocation = location(forgedStart);
    PROVIDER.telegramNonce = "wrong-nonce";
    PROVIDER.telegramChallenge = query(forgedLocation, "code_challenge");
    callback(
            "/api/v1/auth/telegram/callback",
            authenticatedCookie,
            query(forgedLocation, "state"),
            "forged-code",
            null)
        .andExpect(status().isUnprocessableContent());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM account_identity", Integer.class))
        .isEqualTo(1);

    var vkStart = start("/api/v1/auth/vk/link/start", authenticatedCookie);
    var vkLocation = location(vkStart);
    PROVIDER.vkChallenge = query(vkLocation, "code_challenge");
    callback(
            "/api/v1/auth/vk/callback",
            authenticatedCookie,
            query(vkLocation, "state"),
            "vk-code",
            "vk-device")
        .andExpect(status().isOk());
    assertThat(identity("vk")).isEqualTo("42");
    assertThat(
            jdbc.queryForObject(
                "SELECT user_id FROM account_identity WHERE provider = 'vk'", UUID.class))
        .isEqualTo(primaryAccount);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);

    var linkedStart = start("/api/v1/auth/vk/link/start", authenticatedCookie);
    var linkedLocation = location(linkedStart);
    PROVIDER.vkChallenge = query(linkedLocation, "code_challenge");
    callback(
            "/api/v1/auth/vk/callback",
            authenticatedCookie,
            query(linkedLocation, "state"),
            "linked-vk-code",
            "vk-device")
        .andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_identity WHERE provider = 'vk'", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_identity_audit WHERE action = 'linked'",
                Integer.class))
        .isEqualTo(1);

    var outageStart = start("/api/v1/auth/telegram/start", authenticatedCookie);
    var outageLocation = location(outageStart);
    PROVIDER.unavailable.set(true);
    callback(
            "/api/v1/auth/telegram/callback",
            authenticatedCookie,
            query(outageLocation, "state"),
            "outage-code",
            null)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("service.unavailable"));

    emailLoginService.requestCode("still-works@example.com");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM email_login_code WHERE email_normalized = 'still-works@example.com'",
                Integer.class))
        .isEqualTo(1);
  }

  private MvcResult start(String path, Cookie cookie) throws Exception {
    var request = get(path);
    if (cookie != null) {
      request.cookie(cookie);
    }
    return mockMvc.perform(request).andExpect(status().isFound()).andReturn();
  }

  private org.springframework.test.web.servlet.ResultActions callback(
      String path, Cookie cookie, String state, String code, String deviceId) throws Exception {
    var request = get(path).cookie(cookie).param("state", state).param("code", code);
    if (deviceId != null) {
      request.param("device_id", deviceId);
    }
    return mockMvc.perform(request);
  }

  private URI location(MvcResult result) {
    return URI.create(Objects.requireNonNull(result.getResponse().getHeader("Location")));
  }

  private Cookie sessionCookie(MvcResult result) {
    var headers = result.getResponse().getHeaders("Set-Cookie");
    return headers.stream()
        .map(value -> value.split(";", 2)[0].split("=", 2))
        .filter(pair -> pair[0].equals("DORAHUB_SESSION") && pair.length == 2 && !pair[1].isBlank())
        .map(pair -> new Cookie(pair[0], pair[1]))
        .reduce((first, last) -> last)
        .orElseThrow(() -> new AssertionError("No session cookie in " + headers));
  }

  private String query(URI uri, String name) {
    return Objects.requireNonNull(
        UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name));
  }

  private String identity(String provider) {
    return jdbc.queryForObject(
        "SELECT subject FROM account_identity WHERE provider = ?", String.class, provider);
  }

  private static final class ProviderServer implements AutoCloseable {

    private final HttpServer server;
    private final RSAKey signingKey;
    private final AtomicBoolean unavailable = new AtomicBoolean();
    private final AtomicInteger telegramTokenCalls = new AtomicInteger();
    private volatile String telegramNonce;
    private volatile String telegramChallenge;
    private volatile String vkChallenge;

    private ProviderServer(HttpServer server, RSAKey signingKey) {
      this.server = server;
      this.signingKey = signingKey;
    }

    static ProviderServer start() {
      try {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var provider =
            new ProviderServer(server, new RSAKeyGenerator(2048).keyID("test").generate());
        server.createContext("/jwks", provider::jwks);
        server.createContext("/telegram/token", provider::telegramToken);
        server.createContext("/vk/token", provider::vkToken);
        server.createContext("/vk/user", provider::vkUser);
        server.start();
        return provider;
      } catch (Exception exception) {
        throw new IllegalStateException(exception);
      }
    }

    String uri(String path) {
      return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    void reset() {
      unavailable.set(false);
      telegramTokenCalls.set(0);
      telegramNonce = null;
      telegramChallenge = null;
      vkChallenge = null;
    }

    private void jwks(HttpExchange exchange) throws IOException {
      respond(exchange, 200, "{\"keys\":[" + signingKey.toPublicJWK() + "]}");
    }

    private void telegramToken(HttpExchange exchange) throws IOException {
      try {
        telegramTokenCalls.incrementAndGet();
        if (unavailable.get()) {
          respond(exchange, 503, "{\"error\":\"unavailable\"}");
          return;
        }
        var form = form(exchange);
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Basic ");
        assertChallenge(form.get("code_verifier"), telegramChallenge);
        var claims =
            new JWTClaimsSet.Builder()
                .issuer("https://oauth.telegram.test")
                .audience("telegram-client")
                .subject("telegram-user")
                .claim("nonce", telegramNonce)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        var jwt =
            new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(signingKey.toPrivateKey()));
        respond(exchange, 200, "{\"id_token\":\"" + jwt.serialize() + "\"}");
      } catch (Exception exception) {
        throw new IOException(exception);
      }
    }

    private void vkToken(HttpExchange exchange) throws IOException {
      var form = form(exchange);
      assertChallenge(form.get("code_verifier"), vkChallenge);
      respond(
          exchange,
          200,
          "{\"access_token\":\"vk-token\",\"state\":\"" + form.get("state") + "\",\"user_id\":42}");
    }

    private void vkUser(HttpExchange exchange) throws IOException {
      var form = form(exchange);
      assertThat(form).containsEntry("access_token", "vk-token");
      respond(exchange, 200, "{\"user\":{\"user_id\":\"42\"}}");
    }

    private void assertChallenge(String verifier, String expected) {
      try {
        var digest =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.UTF_8));
        assertThat(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
            .isEqualTo(expected);
      } catch (java.security.NoSuchAlgorithmException exception) {
        throw new IllegalStateException(exception);
      }
    }

    private Map<String, String> form(HttpExchange exchange) throws IOException {
      return java.util.Arrays.stream(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                  .split("&"))
          .map(value -> value.split("=", 2))
          .collect(
              Collectors.toMap(
                  value -> decode(value[0]), value -> value.length == 1 ? "" : decode(value[1])));
    }

    private String decode(String value) {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
      var bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
