package ru.dorahub.accounts.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.server.ResponseStatusException;

class LoginSession {

  private static final String AUTHENTICATED_AT = LoginSession.class.getName() + ".authenticatedAt";
  private static final String SECOND_FACTOR_AT = LoginSession.class.getName() + ".secondFactorAt";
  private static final Duration RECENT_AUTHENTICATION = Duration.ofMinutes(5);

  private final SecurityContextRepository securityContexts;
  private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
  private final Clock clock;

  LoginSession(
      SecurityContextRepository securityContexts,
      SessionAuthenticationStrategy sessionAuthenticationStrategy,
      Clock clock) {
    this.securityContexts = securityContexts;
    this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    this.clock = clock;
  }

  void authenticate(UUID accountId, HttpServletRequest request, HttpServletResponse response) {
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            accountId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
    request.getSession().setAttribute(AUTHENTICATED_AT, clock.instant());
    request.getSession().removeAttribute(SECOND_FACTOR_AT);
    var holder = SecurityContextHolder.getContextHolderStrategy();
    var context = holder.createEmptyContext();
    context.setAuthentication(authentication);
    holder.setContext(context);
    securityContexts.saveContext(context, request, response);
  }

  void markSecondFactor(HttpServletRequest request) {
    currentAccount();
    request.getSession().setAttribute(SECOND_FACTOR_AT, clock.instant());
  }

  boolean hasRecentSecondFactor(HttpServletRequest request) {
    var session = request.getSession(false);
    var verifiedAt = session == null ? null : session.getAttribute(SECOND_FACTOR_AT);
    return verifiedAt instanceof Instant instant
        && !instant.plus(RECENT_AUTHENTICATION).isBefore(clock.instant());
  }

  UUID requireRecentAuthentication(HttpServletRequest request) {
    var accountId = currentAccount();
    var session = request.getSession(false);
    var authenticatedAt = session == null ? null : session.getAttribute(AUTHENTICATED_AT);
    if (!(authenticatedAt instanceof Instant instant)
        || instant.plus(RECENT_AUTHENTICATION).isBefore(clock.instant())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Recent authentication is required");
    }
    return accountId;
  }

  UUID currentAccount() {
    var authentication =
        SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
    try {
      if (authentication != null && authentication.isAuthenticated()) {
        return UUID.fromString(authentication.getName());
      }
    } catch (IllegalArgumentException ignored) {
      // Non-account principals are not valid application sessions.
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
  }
}
