package ru.dorahub.system.internal.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("dorahub.integrations")
record ProductionIntegrationsProperties(
    @Valid @NotNull Oidc oidc,
    @Valid @NotNull Smtp smtp,
    @Valid @NotNull S3 s3,
    @Valid @NotNull Vision vision) {

  @Override
  public String toString() {
    return "ProductionIntegrationsProperties[redacted]";
  }

  record Oidc(
      @NotBlank String telegramClientId,
      @NotBlank String telegramClientSecret,
      @NotBlank String vkClientId,
      @NotBlank String vkClientSecret) {

    @Override
    public String toString() {
      return "Oidc[redacted]";
    }
  }

  record Smtp(
      @NotBlank String host,
      @Min(1) @Max(65535) int port,
      @NotBlank String username,
      @NotBlank String password,
      @Email @NotBlank String from) {

    @Override
    public String toString() {
      return "Smtp[redacted]";
    }
  }

  record S3(
      @NotNull URI endpoint,
      @NotBlank String region,
      @NotBlank String bucket,
      @NotBlank String accessKey,
      @NotBlank String secretKey) {

    @Override
    public String toString() {
      return "S3[redacted]";
    }
  }

  record Vision(@NotNull URI baseUrl, @NotBlank String apiKey) {

    @Override
    public String toString() {
      return "Vision[redacted]";
    }
  }
}
