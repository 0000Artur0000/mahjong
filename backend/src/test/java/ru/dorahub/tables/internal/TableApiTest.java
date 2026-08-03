package ru.dorahub.tables.internal;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** HTTP-контракт стола: формы ответов, коды ошибок и защита от повторного подтверждения. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class TableApiTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  private static final String SANSHOKU_HAND =
      """
      {"tiles":["1m","2m","3m","4m","5m","6m","1p","2p","3p","1s","2s","3s","9s","9s"],
       "winningTile":"9s","tsumo":false,"winnerSeat":1,"discarderSeat":2%s}
      """;

  @Autowired private WebApplicationContext context;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

  private MockMvc mvc;
  private UUID creator;
  private UUID tableId;

  @BeforeEach
  void createStartedTable() throws Exception {
    mvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(
                org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                    .springSecurity())
            .build();
    creator = UUID.randomUUID();
    String created =
        mvc.perform(
                json(post("/api/v1/tables"), "{\"rulesetKey\":\"rrc-ru\",\"format\":\"HANCHAN\"}")
                    .with(user(creator.toString())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("lobby"))
            .andExpect(jsonPath("$.rulesetId").value("rrc-ru@1.0"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    tableId = UUID.fromString(created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));

    for (int player = 0; player < 3; player++) {
      mvc.perform(
              post("/api/v1/tables/{id}/players", tableId)
                  .with(user(UUID.randomUUID().toString()))
                  .with(csrf()))
          .andExpect(status().isOk());
    }
    mvc.perform(
            post("/api/v1/tables/{id}/start", tableId).with(user(creator.toString())).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("active"))
        .andExpect(jsonPath("$.roundWind").value("east"))
        .andExpect(jsonPath("$.scores", contains(30000, 30000, 30000, 30000)));
  }

  @Test
  void previewReturnsBreakdownWithoutChangingTheTable() throws Exception {
    long version = version();

    mvc.perform(
            json(post("/api/v1/tables/{id}/hands/preview", tableId), hand(""))
                .with(user(creator.toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.han").value(2))
        .andExpect(jsonPath("$.fu").value(40))
        .andExpect(jsonPath("$.yaku", contains("Sanshoku")))
        .andExpect(jsonPath("$.seatDelta", contains(0, 2600, -2600, 0)));

    mvc.perform(get("/api/v1/tables/{id}", tableId))
        .andExpect(jsonPath("$.version").value(version))
        .andExpect(jsonPath("$.scores", contains(30000, 30000, 30000, 30000)));
  }

  @Test
  void confirmAppliesHandAndAdvancesTheGame() throws Exception {
    mvc.perform(
            json(
                    post("/api/v1/tables/{id}/hands", tableId),
                    hand(",\"expectedVersion\":" + version()))
                .with(user(creator.toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seatDelta", contains(0, 2600, -2600, 0)));

    mvc.perform(get("/api/v1/tables/{id}", tableId))
        .andExpect(jsonPath("$.scores", contains(30000, 32600, 27400, 30000)))
        .andExpect(jsonPath("$.dealerSeat").value(1))
        .andExpect(jsonPath("$.handsPlayed").value(1));
  }

  /** Повторная отправка того же подтверждения — 409, а не второе начисление. */
  @Test
  void repeatedConfirmConflicts() throws Exception {
    String body = hand(",\"expectedVersion\":" + version());
    mvc.perform(
            json(post("/api/v1/tables/{id}/hands", tableId), body).with(user(creator.toString())))
        .andExpect(status().isOk());

    mvc.perform(
            json(post("/api/v1/tables/{id}/hands", tableId), body).with(user(creator.toString())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("state.conflict"));

    mvc.perform(get("/api/v1/tables/{id}", tableId))
        .andExpect(jsonPath("$.scores", contains(30000, 32600, 27400, 30000)));
  }

  @Test
  void requiresAuthenticationForCommands() throws Exception {
    mvc.perform(json(post("/api/v1/tables/{id}/hands", tableId), hand(",\"expectedVersion\":1")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("authentication.required"));
  }

  /** Действовать за столом может только его участник. */
  @Test
  void rejectsCommandFromOutsider() throws Exception {
    mvc.perform(
            post("/api/v1/tables/{id}/finish", tableId)
                .with(user(UUID.randomUUID().toString()))
                .with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("state.conflict"));
  }

  /** Откат — право модератора: игроку за столом отвечают 403, а не 409. */
  @Test
  void rejectsRevertFromAPlayer() throws Exception {
    mvc.perform(
            json(
                    post("/api/v1/tables/{id}/revert", tableId),
                    "{\"toVersion\":6,\"reason\":\"хочу переиграть\"}")
                .with(user(creator.toString())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("access.denied"));
  }

  /** Ветер без имени ничего не говорит: за столом надо понимать, кто где сидит. */
  @Test
  void showsNicknamesOfTheParticipants() throws Exception {
    jdbc.update(
        """
        INSERT INTO app_user (id, status, nickname, nickname_normalized)
        VALUES (?, 'active', ?, ?)
        """,
        creator,
        "Ёсимура",
        "ёсимура-" + creator);

    mvc.perform(get("/api/v1/tables/{id}", tableId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nicknames['%s']".formatted(creator)).value("Ёсимура"));
  }

  /** Пилотные метрики — внутренняя картина продукта, а не публичная статистика. */
  @Test
  void servesPilotStatsToModeratorsOnly() throws Exception {
    mvc.perform(get("/api/v1/tables/stats").with(user(creator.toString())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("access.denied"));

    UUID moderator = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO app_user (id, status, nickname, nickname_normalized, role)
        VALUES (?, 'active', ?, ?, 'moderator')
        """,
        moderator,
        "mod-" + moderator,
        "mod-" + moderator);

    mvc.perform(get("/api/v1/tables/stats").with(user(moderator.toString())))
        .andExpect(status().isOk())
        // Стол из этого теста уже начат, значит идёт.
        .andExpect(jsonPath("$.active").isNumber())
        .andExpect(jsonPath("$.completionRate").isNumber());
  }

  @Test
  void reportsUnknownTableAsNotFound() throws Exception {
    mvc.perform(get("/api/v1/tables/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("resource.not_found"));
  }

  @Test
  void reportsInvalidHandAsUnprocessable() throws Exception {
    mvc.perform(
            json(
                    post("/api/v1/tables/{id}/hands/preview", tableId),
                    """
                    {"tiles":["1m","2m"],"winningTile":"1m","tsumo":false,
                     "winnerSeat":1,"discarderSeat":2}
                    """)
                .with(user(creator.toString())))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("request.unprocessable"));
  }

  @Test
  void validatesRequestBody() throws Exception {
    mvc.perform(
            json(post("/api/v1/tables"), "{\"rulesetKey\":\"\",\"format\":\"HANCHAN\"}")
                .with(user(creator.toString())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("request.invalid"));
  }

  @Test
  void servesEventFeedFromGivenSequence() throws Exception {
    mvc.perform(get("/api/v1/tables/{id}/events", tableId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(6)))
        .andExpect(jsonPath("$[0].type").value("TABLE_CREATED"))
        .andExpect(jsonPath("$[5].type").value("GAME_STARTED"));

    mvc.perform(get("/api/v1/tables/{id}/events", tableId).param("since", "5"))
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].sequence").value(6));
  }

  @Test
  void recordsDrawsAndReturnsStandingsWhenFinished() throws Exception {
    mvc.perform(
            json(
                    post("/api/v1/tables/{id}/draws", tableId),
                    "{\"type\":\"EXHAUSTIVE\",\"tenpaiSeats\":[0,1]}")
                .with(user(creator.toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scores", contains(31500, 31500, 28500, 28500)))
        .andExpect(jsonPath("$.honba").value(1))
        .andExpect(jsonPath("$.standings").doesNotExist());

    mvc.perform(
            post("/api/v1/tables/{id}/finish", tableId).with(user(creator.toString())).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("finished"))
        .andExpect(jsonPath("$.standings", hasSize(4)))
        .andExpect(jsonPath("$.standings[0].place").value(1));
  }

  @Test
  void declaresRiichi() throws Exception {
    mvc.perform(
            json(post("/api/v1/tables/{id}/riichi", tableId), "{\"seat\":2}")
                .with(user(creator.toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.riichiSticks").value(1))
        .andExpect(jsonPath("$.scores", contains(30000, 30000, 29000, 30000)));
  }

  private long version() throws Exception {
    String body =
        mvc.perform(get("/api/v1/tables/{id}", tableId))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(body.replaceAll(".*\"version\":(\\d+).*", "$1"));
  }

  private static String hand(String extra) {
    return SANSHOKU_HAND.formatted(extra);
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body).with(csrf());
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
    return org.springframework.security.test.web.servlet.request
        .SecurityMockMvcRequestPostProcessors.csrf();
  }
}
