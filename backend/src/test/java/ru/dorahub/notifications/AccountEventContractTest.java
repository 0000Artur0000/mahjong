package ru.dorahub.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.dorahub.accounts.AccountDeactivated;
import ru.dorahub.accounts.AccountIdentityChanged;

class AccountEventContractTest {

  @Test
  void consumesAccountsPublicEvent() {
    var accountId = UUID.randomUUID();

    assertThat(new AccountDeactivated(accountId).accountId()).isEqualTo(accountId);
  }

  @Test
  void rejectsEventWithoutAccount() {
    assertThatNullPointerException().isThrownBy(() -> new AccountDeactivated(null));
  }

  @Test
  void consumesIdentitySecurityEventWithoutLeakingRecipient() {
    var event =
        new AccountIdentityChanged(
            UUID.randomUUID(), "linked", "email", "owner@example.com", java.time.Instant.now());

    assertThat(event.toString()).doesNotContain("owner@example.com");
  }
}
