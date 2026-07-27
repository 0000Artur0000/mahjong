package ru.dorahub.accounts.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!test")
@RestController
@RequestMapping("/api/v1/auth")
class EmailLoginController {

  private final EmailLoginService service;
  private final LoginSession loginSession;

  EmailLoginController(EmailLoginService service, LoginSession loginSession) {
    this.service = service;
    this.loginSession = loginSession;
  }

  @GetMapping("/csrf")
  CsrfTokenResponse csrf(CsrfToken token) {
    return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
  }

  @PostMapping("/email/code")
  ResponseEntity<Void> requestCode(@Valid @RequestBody EmailRequest request) {
    service.requestCode(request.email());
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/email/verify")
  AccountResponse verify(
      @Valid @RequestBody VerifyRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    var accountId = service.verify(body.email(), body.code());
    loginSession.authenticate(accountId, request, response);
    return new AccountResponse(accountId);
  }

  record CsrfTokenResponse(String headerName, String token) {}

  record EmailRequest(@Email @NotBlank @Size(max = 320) String email) {}

  record VerifyRequest(
      @Email @NotBlank @Size(max = 320) String email,
      @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}

  record AccountResponse(UUID accountId) {}
}
