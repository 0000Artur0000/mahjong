package ru.dorahub.accounts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountIdentityChanged(
    UUID accountId, String action, String provider, String notificationEmail, Instant occurredAt) {

  public AccountIdentityChanged {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  @Override
  public String toString() {
    return "AccountIdentityChanged[redacted]";
  }
}
