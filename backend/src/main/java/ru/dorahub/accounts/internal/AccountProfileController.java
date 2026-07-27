package ru.dorahub.accounts.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
class AccountProfileController {

  private final AccountProfiles profiles;
  private final LoginSession loginSession;
  private final StepUpAuthentication stepUp;

  AccountProfileController(
      AccountProfiles profiles, LoginSession loginSession, StepUpAuthentication stepUp) {
    this.profiles = profiles;
    this.loginSession = loginSession;
    this.stepUp = stepUp;
  }

  @GetMapping("/api/v1/profiles/{accountId}")
  AccountProfiles.PublicProfile publicProfile(@PathVariable UUID accountId) {
    return profiles.publicProfile(accountId);
  }

  @GetMapping("/api/v1/account/profile")
  AccountProfiles.PrivateProfile privateProfile() {
    return profiles.privateProfile(loginSession.currentAccount());
  }

  @PutMapping("/api/v1/account/profile")
  AccountProfiles.PrivateProfile update(@Valid @RequestBody UpdateProfileRequest body) {
    return profiles.update(
        loginSession.currentAccount(),
        body.nickname(),
        body.city(),
        body.avatarMediaId(),
        new AccountProfiles.Privacy(body.privacy().showCity(), body.privacy().showClubs()));
  }

  @PostMapping("/api/v1/account/deactivation")
  ResponseEntity<Void> deactivate(HttpServletRequest request) {
    profiles.deactivate(stepUp.require(request));
    invalidate(request);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/api/v1/account")
  ResponseEntity<AccountProfiles.Deletion> delete(HttpServletRequest request) {
    var deletion = profiles.requestDeletion(stepUp.require(request));
    invalidate(request);
    return ResponseEntity.accepted().body(deletion);
  }

  private void invalidate(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
  }

  record UpdateProfileRequest(
      @NotBlank @Size(max = 32) String nickname,
      @Size(max = 128) String city,
      UUID avatarMediaId,
      @NotNull @Valid PrivacyRequest privacy) {}

  record PrivacyRequest(boolean showCity, boolean showClubs) {}
}
