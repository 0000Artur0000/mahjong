package ru.dorahub.accounts.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "dorahub.auth.external.enabled", havingValue = "true")
class ExternalLoginController {

  private final ExternalLoginService service;
  private final LoginSession loginSession;
  private final StepUpAuthentication stepUp;

  ExternalLoginController(
      ExternalLoginService service, LoginSession loginSession, StepUpAuthentication stepUp) {
    this.service = service;
    this.loginSession = loginSession;
    this.stepUp = stepUp;
  }

  @GetMapping("/telegram/start")
  ResponseEntity<Void> startTelegram(HttpServletRequest request) {
    return redirect(
        service.start(ExternalLoginService.Provider.TELEGRAM, sessionId(request, true)));
  }

  @GetMapping("/vk/start")
  ResponseEntity<Void> startVk(HttpServletRequest request) {
    return redirect(service.start(ExternalLoginService.Provider.VK, sessionId(request, true)));
  }

  @GetMapping("/telegram/link/start")
  ResponseEntity<Void> linkTelegram(HttpServletRequest request, HttpServletResponse response) {
    var accountId = stepUp.require(request);
    return redirect(
        service.startLink(
            ExternalLoginService.Provider.TELEGRAM,
            sessionId(request, false),
            accountId,
            IdentityController.audit(request, response)));
  }

  @GetMapping("/vk/link/start")
  ResponseEntity<Void> linkVk(HttpServletRequest request, HttpServletResponse response) {
    var accountId = stepUp.require(request);
    return redirect(
        service.startLink(
            ExternalLoginService.Provider.VK,
            sessionId(request, false),
            accountId,
            IdentityController.audit(request, response)));
  }

  @GetMapping("/telegram/callback")
  AccountResponse telegramCallback(
      @RequestParam @Size(max = 256) String state,
      @RequestParam(required = false) @Size(max = 2048) String code,
      @RequestParam(required = false) @Size(max = 128) String error,
      HttpServletRequest request,
      HttpServletResponse response) {
    var sessionId = sessionId(request, false);
    if (error != null) {
      service.cancel(ExternalLoginService.Provider.TELEGRAM, state, sessionId);
    }
    var completion = service.completeTelegram(state, required(code), sessionId);
    authenticateLogin(completion, request, response);
    return new AccountResponse(completion.accountId());
  }

  @GetMapping("/vk/callback")
  AccountResponse vkCallback(
      @RequestParam @Size(max = 256) String state,
      @RequestParam(required = false) @Size(max = 2048) String code,
      @RequestParam(name = "device_id", required = false) @Size(max = 256) String deviceId,
      @RequestParam(required = false) @Size(max = 128) String error,
      HttpServletRequest request,
      HttpServletResponse response) {
    var sessionId = sessionId(request, false);
    if (error != null) {
      service.cancel(ExternalLoginService.Provider.VK, state, sessionId);
    }
    var completion = service.completeVk(state, required(code), required(deviceId), sessionId);
    authenticateLogin(completion, request, response);
    return new AccountResponse(completion.accountId());
  }

  private void authenticateLogin(
      ExternalLoginService.Completion completion,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (!completion.linked()) {
      loginSession.authenticate(completion.accountId(), request, response);
    }
  }

  private ResponseEntity<Void> redirect(java.net.URI location) {
    return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
  }

  private String sessionId(HttpServletRequest request, boolean create) {
    var session = request.getSession(create);
    if (session == null) {
      throw invalid();
    }
    return session.getId();
  }

  private String required(String value) {
    if (value == null || value.isBlank()) {
      throw invalid();
    }
    return value;
  }

  private ResponseStatusException invalid() {
    return new ResponseStatusException(
        HttpStatus.UNPROCESSABLE_CONTENT, "Invalid or expired external login");
  }

  record AccountResponse(UUID accountId) {}
}
