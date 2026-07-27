package ru.dorahub.accounts.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("dorahub.auth.external")
record ExternalLoginProperties(
    @NotNull URI publicBaseUrl, @Valid @NotNull Telegram telegram, @Valid @NotNull Vk vk) {

  record Telegram(
      @NotBlank String clientId,
      @NotBlank String clientSecret,
      @NotNull URI authorizationUri,
      @NotNull URI tokenUri,
      @NotNull URI jwkSetUri,
      @NotNull URI issuer) {

    @Override
    public String toString() {
      return "Telegram[redacted]";
    }
  }

  record Vk(
      @NotBlank String clientId,
      @NotNull URI authorizationUri,
      @NotNull URI tokenUri,
      @NotNull URI userInfoUri) {}
}
