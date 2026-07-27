package ru.dorahub.accounts.internal;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration(proxyBeanMethods = false)
class AccountsConfiguration {

  @Bean
  SecureRandom secureRandom() {
    return new SecureRandom();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  @Profile("!test")
  AccountIdentities accountIdentities(JdbcTemplate jdbc, ApplicationEventPublisher events) {
    return new AccountIdentities(jdbc, events);
  }

  @Bean
  @Profile("!test")
  EmailLoginService emailLoginService(
      JdbcTemplate jdbc,
      AccountIdentities identities,
      PasswordEncoder passwordEncoder,
      SecureRandom random,
      Clock clock,
      ApplicationEventPublisher events) {
    return new EmailLoginService(jdbc, identities, passwordEncoder, random, clock, events);
  }

  @Bean
  @Profile("!test")
  LoginSession loginSession(
      SecurityContextRepository securityContexts,
      SessionAuthenticationStrategy sessionAuthenticationStrategy,
      Clock clock) {
    return new LoginSession(securityContexts, sessionAuthenticationStrategy, clock);
  }

  @Bean
  @Profile("!test")
  StepUpAuthentication stepUpAuthentication(JdbcTemplate jdbc, LoginSession loginSession) {
    return new StepUpAuthentication(jdbc, loginSession);
  }
}
