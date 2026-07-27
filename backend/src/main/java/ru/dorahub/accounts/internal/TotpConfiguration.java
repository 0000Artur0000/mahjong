package ru.dorahub.accounts.internal;

import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "dorahub.auth.totp.enabled", havingValue = "true")
@EnableConfigurationProperties(TotpConfiguration.Properties.class)
class TotpConfiguration {

  @Bean
  TotpService totpService(
      JdbcTemplate jdbc, Properties properties, java.security.SecureRandom random, Clock clock) {
    return new TotpService(jdbc, properties.encryptionKey(), random, clock);
  }

  @Validated
  @ConfigurationProperties("dorahub.auth.totp")
  record Properties(@NotBlank String encryptionKey) {

    @Override
    public String toString() {
      return "TotpProperties[redacted]";
    }
  }
}
