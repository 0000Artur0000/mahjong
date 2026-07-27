package ru.dorahub.system.internal.http;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

record RequestContext(
    String correlationId, String actor, String source, Locale locale, Instant serverTime) {

  static final String ATTRIBUTE = RequestContext.class.getName();

  static RequestContext from(HttpServletRequest request) {
    return (RequestContext) Objects.requireNonNull(request.getAttribute(ATTRIBUTE));
  }
}
