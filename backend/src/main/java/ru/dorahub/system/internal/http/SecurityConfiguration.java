package ru.dorahub.system.internal.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

  @Bean
  CookieSerializer cookieSerializer(
      @Value("${server.servlet.session.cookie.name:DORAHUB_SESSION}") String name,
      @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
    var serializer = new DefaultCookieSerializer();
    serializer.setCookieName(name);
    serializer.setUseHttpOnlyCookie(true);
    serializer.setSameSite("Lax");
    serializer.setUseSecureCookie(secure);
    return serializer;
  }

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  SessionAuthenticationStrategy sessionAuthenticationStrategy() {
    return new ChangeSessionIdAuthenticationStrategy();
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ObjectMapper objectMapper,
      SecurityContextRepository securityContextRepository)
      throws Exception {
    http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .securityContext(
            security ->
                security
                    .requireExplicitSave(true)
                    .securityContextRepository(securityContextRepository))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            writeProblem(
                                request,
                                response,
                                objectMapper,
                                HttpStatus.UNAUTHORIZED,
                                "authentication.required"))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            writeProblem(
                                request,
                                response,
                                objectMapper,
                                HttpStatus.FORBIDDEN,
                                "access.denied")))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/v1/auth/logout")
                    .logoutSuccessHandler(
                        (request, response, authentication) ->
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT)));
    return http.build();
  }

  private void writeProblem(
      HttpServletRequest request,
      HttpServletResponse response,
      ObjectMapper objectMapper,
      HttpStatus status,
      String code)
      throws IOException {
    var correlationId = CorrelationIdFilter.correlationId(request);
    response.setStatus(status.value());
    response.setHeader(CorrelationIdFilter.HEADER, correlationId);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    var problem = ProblemDetail.forStatus(status);
    problem.setType(URI.create("urn:dorahub:problem:" + code));
    problem.setProperty("code", code);
    problem.setProperty("correlationId", correlationId);
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}
