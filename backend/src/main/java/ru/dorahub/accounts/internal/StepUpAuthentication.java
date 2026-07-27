package ru.dorahub.accounts.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class StepUpAuthentication {

  private final JdbcTemplate jdbc;
  private final LoginSession loginSession;

  StepUpAuthentication(JdbcTemplate jdbc, LoginSession loginSession) {
    this.jdbc = jdbc;
    this.loginSession = loginSession;
  }

  UUID require(HttpServletRequest request) {
    var accountId = loginSession.requireRecentAuthentication(request);
    var totpEnabled =
        jdbc.queryForObject(
            "SELECT count(*) FROM account_totp WHERE account_id = ? AND status = 'enabled'",
            Integer.class,
            accountId);
    if (totpEnabled != null && totpEnabled > 0 && !loginSession.hasRecentSecondFactor(request)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Second factor is required");
    }
    return accountId;
  }
}
