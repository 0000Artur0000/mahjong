package ru.dorahub.system.internal.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
final class CorrelationIdFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);
  private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
  private static final Pattern VALID_SOURCE = Pattern.compile("[A-Za-z0-9._-]{1,32}");

  static final String ATTRIBUTE = CorrelationIdFilter.class.getName();
  static final String HEADER = "X-Correlation-Id";
  static final String SOURCE_HEADER = "X-Client-Source";

  private final Clock clock;

  CorrelationIdFilter(Clock clock) {
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    var correlationId = correlationId(request);
    var context = requestContext(request, correlationId);

    request.setAttribute(ATTRIBUTE, correlationId);
    request.setAttribute(RequestContext.ATTRIBUTE, context);
    response.setHeader(HEADER, correlationId);
    var startNanos = System.nanoTime();

    var previousMdc = MDC.getCopyOfContextMap();
    MDC.put("correlationId", correlationId);
    MDC.put("actor", safeActor(context));
    MDC.put("source", context.source());
    MDC.put("locale", context.locale().toLanguageTag());
    try {
      try {
        filterChain.doFilter(request, response);
      } finally {
        LOG.atInfo()
            .addKeyValue("event.name", "http.request")
            .addKeyValue("operation", operationName(request))
            .addKeyValue("http.response.status_code", response.getStatus())
            .addKeyValue("duration_ms", (System.nanoTime() - startNanos) / 1_000_000)
            .log("HTTP request completed");
      }
    } finally {
      if (previousMdc == null) {
        MDC.clear();
      } else {
        MDC.setContextMap(previousMdc);
      }
    }
  }

  private RequestContext requestContext(HttpServletRequest request, String correlationId) {
    var principal = request.getUserPrincipal();
    var actor =
        principal == null
            ? "anonymous"
            : Objects.requireNonNullElse(principal.getName(), "authenticated");
    var suppliedSource = request.getHeader(SOURCE_HEADER);
    var source =
        suppliedSource != null && VALID_SOURCE.matcher(suppliedSource).matches()
            ? suppliedSource
            : "unknown";
    return new RequestContext(correlationId, actor, source, request.getLocale(), clock.instant());
  }

  static String correlationId(HttpServletRequest request) {
    var suppliedId = request.getHeader(HEADER);
    return suppliedId != null && VALID_ID.matcher(suppliedId).matches()
        ? suppliedId
        : UUID.randomUUID().toString();
  }

  private String safeActor(RequestContext context) {
    return context.actor().equals("anonymous") ? "anonymous" : "authenticated";
  }

  private String operationName(HttpServletRequest request) {
    var route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    return request.getMethod() + " " + (route instanceof String pattern ? pattern : "unmatched");
  }
}
