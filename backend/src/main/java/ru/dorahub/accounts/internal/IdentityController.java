package ru.dorahub.accounts.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/auth/identities")
class IdentityController {

  private static final java.util.regex.Pattern SAFE_SOURCE =
      java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,32}");

  private final AccountIdentities identities;
  private final EmailLoginService emailLoginService;
  private final LoginSession loginSession;
  private final StepUpAuthentication stepUp;
  private final Clock clock;

  IdentityController(
      AccountIdentities identities,
      EmailLoginService emailLoginService,
      LoginSession loginSession,
      StepUpAuthentication stepUp,
      Clock clock) {
    this.identities = identities;
    this.emailLoginService = emailLoginService;
    this.loginSession = loginSession;
    this.stepUp = stepUp;
    this.clock = clock;
  }

  @GetMapping
  IdentitiesResponse list() {
    var accountId = loginSession.currentAccount();
    return new IdentitiesResponse(
        accountId, identities.list(accountId).stream().map(IdentityResponse::from).toList());
  }

  @PostMapping("/email")
  LinkResponse linkEmail(
      @Valid @RequestBody LinkEmailRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    var accountId = stepUp.require(request);
    return LinkResponse.from(
        emailLoginService.link(accountId, body.email(), body.code(), audit(request, response)));
  }

  @DeleteMapping("/{identityId}")
  ResponseEntity<Void> unlink(
      @PathVariable UUID identityId, HttpServletRequest request, HttpServletResponse response) {
    var accountId = stepUp.require(request);
    identities.unlink(accountId, identityId, audit(request, response), clock.instant());
    return ResponseEntity.noContent().build();
  }

  static AccountIdentities.Audit audit(HttpServletRequest request, HttpServletResponse response) {
    var correlationId =
        Objects.requireNonNullElse(response.getHeader("X-Correlation-Id"), "unknown");
    var suppliedSource = request.getHeader("X-Client-Source");
    var source =
        suppliedSource != null && SAFE_SOURCE.matcher(suppliedSource).matches()
            ? suppliedSource
            : "unknown";
    return new AccountIdentities.Audit(correlationId, source);
  }

  record LinkEmailRequest(
      @Email @NotBlank @Size(max = 320) String email,
      @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}

  record IdentitiesResponse(UUID primaryAccountId, List<IdentityResponse> identities) {}

  record LinkResponse(
      UUID primaryAccountId, boolean accountMerged, boolean created, IdentityResponse identity) {

    static LinkResponse from(AccountIdentities.LinkResult result) {
      return new LinkResponse(
          result.accountId(), false, result.created(), IdentityResponse.from(result.identity()));
    }
  }

  record IdentityResponse(UUID id, String provider, String subject, Instant createdAt) {

    static IdentityResponse from(AccountIdentities.Identity identity) {
      return new IdentityResponse(
          identity.id(), identity.provider(), identity.subject(), identity.createdAt());
    }
  }
}
