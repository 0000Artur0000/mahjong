package ru.dorahub.accounts;

import java.util.Objects;
import java.util.UUID;

public record AccountDeactivated(UUID accountId) {

  public AccountDeactivated {
    Objects.requireNonNull(accountId, "accountId");
  }
}
