package ru.dorahub.system.internal.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskDecorator;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.server.ResponseStatusException;

@ActiveProfiles("test")
@Import(HttpContractTest.ContractController.class)
@SpringBootTest
class HttpContractTest {

  private static final String CORRELATION_ID = "frontend-request_42";

  @Autowired private WebApplicationContext applicationContext;

  @Autowired private CorrelationIdFilter correlationIdFilter;

  @Autowired
  @Qualifier("webMvcObservationFilter")
  private FilterRegistrationBean<ServerHttpObservationFilter> observationFilter;

  @Autowired private MeterRegistry meterRegistry;

  @Autowired private ObservationRegistry observationRegistry;

  @Autowired private Tracer tracer;

  @Autowired private TaskDecorator taskDecorator;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        webAppContextSetup(applicationContext)
            .addFilters(observationFilter.getFilter(), correlationIdFilter)
            .build();
  }

  @ParameterizedTest
  @CsvSource({
    "400, request.invalid",
    "401, authentication.required",
    "403, access.denied",
    "404, resource.not_found",
    "409, state.conflict",
    "413, request.too_large",
    "422, request.unprocessable",
    "429, rate_limit.exceeded",
    "503, service.unavailable"
  })
  void returnsStableProblemShape(int httpStatus, String code) throws Exception {
    mockMvc
        .perform(
            get("/api/v1/contract/status/{status}", httpStatus)
                .header("X-Correlation-Id", CORRELATION_ID))
        .andExpect(status().is(httpStatus))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string("X-Correlation-Id", CORRELATION_ID))
        .andExpect(jsonPath("$.type").isNotEmpty())
        .andExpect(jsonPath("$.title").isNotEmpty())
        .andExpect(jsonPath("$.status").value(httpStatus))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
  }

  @Test
  void reportsValidationFieldsWithoutEchoingRejectedValues() throws Exception {
    var rejectedValue = "do-not-echo";

    var result =
        mockMvc
            .perform(
                post("/api/v1/contract/validation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + rejectedValue + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("request.invalid"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
            .andExpect(jsonPath("$.fieldErrors[0].code").value("Size"))
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).doesNotContain(rejectedValue);
  }

  @Test
  void replacesUnsafeCorrelationId() throws Exception {
    mockMvc
        .perform(get("/api/v1/contract/status/400").header("X-Correlation-Id", "unsafe id!"))
        .andExpect(status().isBadRequest())
        .andExpect(
            header()
                .string("X-Correlation-Id", org.hamcrest.Matchers.matchesPattern("[a-f0-9-]{36}")))
        .andExpect(
            jsonPath("$.correlationId")
                .value(org.hamcrest.Matchers.matchesPattern("[a-f0-9-]{36}")));
  }

  @Test
  void replacesUnsafeClientSource() throws Exception {
    mockMvc
        .perform(get("/api/v1/contract/context").header("X-Client-Source", "person@example.test"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("unknown"));
  }

  @Test
  void returnsProblemForUnknownApiRoute() throws Exception {
    mockMvc
        .perform(get("/api/v1/not-present"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("resource.not_found"))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
  }

  @Test
  void hidesUnexpectedExceptionDetails() throws Exception {
    var sensitiveMessage = "do-not-expose";

    var result =
        mockMvc
            .perform(get("/api/v1/contract/unexpected").queryParam("message", sensitiveMessage))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("internal.error"))
            .andExpect(jsonPath("$.correlationId").isNotEmpty())
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).doesNotContain(sensitiveMessage);
  }

  @Test
  void recordsSafeRequestContextLogTraceAndMetric() throws Exception {
    var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);

    try {
      mockMvc
          .perform(
              get("/api/v1/contract/context")
                  .with(actor("person@example.test"))
                  .header("X-Correlation-Id", CORRELATION_ID)
                  .header("X-Client-Source", "web")
                  .header("Accept-Language", "ru-RU")
                  .queryParam("secret", "must-not-be-logged"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
          .andExpect(jsonPath("$.actor").value("person@example.test"))
          .andExpect(jsonPath("$.source").value("web"))
          .andExpect(jsonPath("$.locale").value("ru-RU"))
          .andExpect(jsonPath("$.serverTime").isNotEmpty());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    var accessLog =
        appender.list.stream()
            .filter(event -> event.getFormattedMessage().equals("HTTP request completed"))
            .findFirst()
            .orElseThrow();
    assertThat(accessLog.getMDCPropertyMap())
        .containsEntry("correlationId", CORRELATION_ID)
        .containsEntry("actor", "authenticated")
        .containsEntry("source", "web")
        .containsEntry("locale", "ru-RU")
        .containsKeys("traceId", "spanId")
        .doesNotContainValue("person@example.test");
    assertThat(accessLog.getKeyValuePairs().toString())
        .contains("operation=\"GET /api/v1/contract/context\"")
        .doesNotContain("must-not-be-logged", "person@example.test");
    var timer =
        meterRegistry.find("http.server.requests").tag("uri", "/api/v1/contract/context").timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isPositive();
  }

  @Test
  void propagatesTraceAndLoggingContextToBackgroundTask() throws Exception {
    var traceId = new AtomicReference<String>();
    var correlationId = new AtomicReference<String>();
    var observation = Observation.start("background-parent", observationRegistry);
    var scope = observation.openScope();
    Runnable decorated;
    String expectedTraceId;
    try {
      MDC.put("correlationId", CORRELATION_ID);
      expectedTraceId = tracer.currentSpan().context().traceId();
      decorated =
          taskDecorator.decorate(
              () -> {
                traceId.set(tracer.currentSpan().context().traceId());
                correlationId.set(MDC.get("correlationId"));
              });
    } finally {
      MDC.remove("correlationId");
      scope.close();
      observation.stop();
    }

    var thread = Thread.ofPlatform().start(decorated);
    thread.join();

    assertThat(traceId).hasValue(expectedTraceId);
    assertThat(correlationId).hasValue(CORRELATION_ID);
  }

  private RequestPostProcessor actor(String name) {
    return request -> {
      request.setUserPrincipal(() -> name);
      return request;
    };
  }

  @RestController
  @RequestMapping("/api/v1/contract")
  static class ContractController {

    @GetMapping("/status/{status}")
    void status(@PathVariable int status) {
      throw new ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(status));
    }

    @GetMapping("/unexpected")
    void unexpected(String message) {
      throw new IllegalStateException(message);
    }

    @GetMapping("/context")
    RequestContext context(HttpServletRequest request) {
      return RequestContext.from(request);
    }

    @PostMapping("/validation")
    void validation(@Valid @RequestBody ValidationRequest request) {}
  }

  record ValidationRequest(@Size(max = 2) String name) {}
}
