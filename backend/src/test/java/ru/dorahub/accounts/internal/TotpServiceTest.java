package ru.dorahub.accounts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

  @Test
  void matchesRfc4226VectorsAndBase32Encoding() {
    var secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    assertThat(TotpService.hotp(secret, 0, 6)).isEqualTo("755224");
    assertThat(TotpService.hotp(secret, 1, 6)).isEqualTo("287082");
    assertThat(TotpService.hotp(secret, 1, 8)).isEqualTo("94287082");
    assertThat(TotpService.hotp(secret, 9, 6)).isEqualTo("520489");
    assertThat(TotpService.base32(secret)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
  }
}
