package com.applyflow.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.applyflow.security.AuthenticatedUser;
import com.applyflow.dto.auth.ConfirmDeletionRequest;
import com.applyflow.dto.auth.GenericMessageResponse;
import com.applyflow.security.TrustedClientIpResolver;
import com.applyflow.service.AccountDeletionProofService;
import com.applyflow.service.AccountDeletionService;
import com.applyflow.service.AccountExportService;
import com.applyflow.service.AuthenticationRateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/account")
public class AccountLifecycleController {

    private final AccountExportService exports;
    private final AuthenticationRateLimiter rateLimiter;
    private final TrustedClientIpResolver clientIp;
    private final AccountDeletionProofService proofs;
    private final AccountDeletionService deletion;

    public AccountLifecycleController(AccountExportService exports, AuthenticationRateLimiter rateLimiter,
            TrustedClientIpResolver clientIp, AccountDeletionProofService proofs, AccountDeletionService deletion) {
        this.exports = exports;
        this.rateLimiter = rateLimiter;
        this.clientIp = clientIp;
        this.proofs = proofs;
        this.deletion = deletion;
    }

    @PostMapping("/deletion/request")
    public ResponseEntity<GenericMessageResponse> requestDeletion(@AuthenticationPrincipal AuthenticatedUser principal,
            HttpServletRequest request) {
        rateLimiter.checkAccountDeletion(clientIp.resolve(request), principal.userId(), false);
        proofs.request(principal.userId());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new GenericMessageResponse(
                "If eligible, a confirmation email will arrive. Requesting a code does not delete your account."));
    }

    @PostMapping("/deletion/confirm")
    public ResponseEntity<Void> confirmDeletion(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ConfirmDeletionRequest confirmation, HttpServletRequest request, HttpServletResponse response) {
        rateLimiter.checkAccountDeletion(clientIp.resolve(request), principal.userId(), true);
        deletion.delete(principal.userId(), confirmation.token());
        // The proxied service has committed before any servlet session/security state is invalidated.
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler("APPLYFLOW_SESSION").logout(request, response, authentication);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest request) {
        Long ownerId = principal.userId();
        rateLimiter.checkAccountExport(clientIp.resolve(request), ownerId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header("Content-Disposition", "attachment; filename=\"applyflow-account-export.json\"")
                .body(output -> exports.write(ownerId, output));
    }
}
