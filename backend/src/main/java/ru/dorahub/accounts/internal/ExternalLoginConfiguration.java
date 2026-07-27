package ru.dorahub.accounts.internal;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "dorahub.auth.external.enabled", havingValue = "true")
@EnableConfigurationProperties(ExternalLoginProperties.class)
class ExternalLoginConfiguration {

  @Bean
  ExternalLoginService externalLoginService(
      JdbcTemplate jdbc,
      AccountIdentities identities,
      TransactionTemplate transactions,
      RestClient.Builder restClientBuilder,
      ExternalLoginProperties properties,
      Clock clock,
      java.security.SecureRandom random) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(3));
    requestFactory.setReadTimeout(Duration.ofSeconds(5));
    var jwtDecoder =
        NimbusJwtDecoder.withJwkSetUri(properties.telegram().jwkSetUri().toString())
            .restOperations(new RestTemplate(requestFactory))
            .build();
    jwtDecoder.setJwtValidator(
        JwtValidators.createDefaultWithIssuer(properties.telegram().issuer().toString()));
    return new ExternalLoginService(
        jdbc,
        identities,
        transactions,
        restClientBuilder.requestFactory(requestFactory).build(),
        jwtDecoder,
        properties,
        clock,
        random);
  }
}
