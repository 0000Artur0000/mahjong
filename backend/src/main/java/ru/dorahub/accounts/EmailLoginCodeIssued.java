package ru.dorahub.accounts;

import java.time.Instant;
import java.util.Objects;

public record EmailLoginCodeIssued(String email, String code, Instant expiresAt) {

  public EmailLoginCodeIssued {
    Objects.requireNonNull(email, "email");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

  @Override
  public String toString() {
    return "EmailLoginCodeIssued[redacted]";
  }
}
