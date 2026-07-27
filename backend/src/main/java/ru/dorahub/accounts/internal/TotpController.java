package ru.dorahub.accounts.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/totp")
@ConditionalOnProperty(name = "dorahub.auth.totp.enabled", havingValue = "true")
class TotpController {

  private final TotpService service;
  private final LoginSession loginSession;
  private final StepUpAuthentication stepUp;

  TotpController(TotpService service, LoginSession loginSession, StepUpAuthentication stepUp) {
    this.service = service;
    this.loginSession = loginSession;
    this.stepUp = stepUp;
  }

  @GetMapping
  ResponseEntity<TotpService.Status> status() {
    return noStore(service.status(loginSession.currentAccount()));
  }

  @PostMapping("/enrollment")
  ResponseEntity<TotpService.Enrollment> start(
      HttpServletRequest request, HttpServletResponse response) {
    var accountId = loginSession.requireRecentAuthentication(request);
    return noStore(service.start(accountId, IdentityController.audit(request, response)));
  }

  @PostMapping("/enrollment/confirm")
  ResponseEntity<RecoveryCodesResponse> confirm(
      @Valid @RequestBody TotpCodeRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    var accountId = loginSession.requireRecentAuthentication(request);
    var codes =
        service.confirm(accountId, body.code(), IdentityController.audit(request, response));
    loginSession.markSecondFactor(request);
    return noStore(new RecoveryCodesResponse(codes));
  }

  @PostMapping("/verify")
  ResponseEntity<VerificationResponse> verify(
      @Valid @RequestBody SecurityCodeRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    var accountId = loginSession.requireRecentAuthentication(request);
    var factor =
        service.verify(accountId, body.code(), IdentityController.audit(request, response));
    loginSession.markSecondFactor(request);
    return noStore(new VerificationResponse(factor.name().toLowerCase(java.util.Locale.ROOT)));
  }

  @DeleteMapping
  ResponseEntity<Void> disable(HttpServletRequest request, HttpServletResponse response) {
    var accountId = stepUp.require(request);
    service.disable(accountId, IdentityController.audit(request, response));
    return ResponseEntity.noContent().build();
  }

  private <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  record TotpCodeRequest(@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}

  record SecurityCodeRequest(
      @NotBlank @Pattern(regexp = "([0-9]{6}|[A-Za-z0-9_-]{22})") String code) {}

  record RecoveryCodesResponse(List<String> recoveryCodes) {}

  record VerificationResponse(String factor) {}
}
