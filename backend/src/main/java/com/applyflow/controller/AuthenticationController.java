package com.applyflow.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.applyflow.dto.auth.ChangePasswordRequest;
import com.applyflow.dto.auth.CsrfResponse;
import com.applyflow.dto.auth.CurrentUserResponse;
import com.applyflow.dto.auth.EmailRequest;
import com.applyflow.dto.auth.GenericMessageResponse;
import com.applyflow.dto.auth.RegisterRequest;
import com.applyflow.dto.auth.ResetPasswordRequest;
import com.applyflow.dto.auth.TokenRequest;
import com.applyflow.security.AuthenticatedUser;
import com.applyflow.security.TrustedClientIpResolver;
import com.applyflow.service.AuthenticationService;
import com.applyflow.service.AuthenticationRateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {

    private final AuthenticationService authenticationService;
    private final AuthenticationRateLimiter rateLimiter;
    private final TrustedClientIpResolver clientIpResolver;

    public AuthenticationController(AuthenticationService authenticationService, AuthenticationRateLimiter rateLimiter,
            TrustedClientIpResolver clientIpResolver) {
        this.authenticationService = authenticationService;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getToken(), token.getHeaderName());
    }

    @PostMapping("/register")
    public ResponseEntity<GenericMessageResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        rateLimiter.checkRegister(clientIpResolver.resolve(servletRequest), AuthenticationService.normalizeEmail(request.email()));
        authenticationService.register(request);
        return ResponseEntity.accepted().body(
                new GenericMessageResponse(AuthenticationService.GENERIC_REGISTRATION_MESSAGE));
    }

    @PostMapping("/email-verification/resend")
    public ResponseEntity<GenericMessageResponse> resend(@Valid @RequestBody EmailRequest request, HttpServletRequest servletRequest) {
        rateLimiter.checkVerificationResend(clientIpResolver.resolve(servletRequest), AuthenticationService.normalizeEmail(request.email()));
        authenticationService.resendVerification(request.email());
        return ResponseEntity.accepted().body(
                new GenericMessageResponse(AuthenticationService.GENERIC_RECOVERY_MESSAGE));
    }

    @PostMapping("/email-verification/confirm")
    public ResponseEntity<Void> confirmEmail(@Valid @RequestBody TokenRequest request) {
        authenticationService.confirmEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return authenticationService.currentUser(principal.userId());
    }

    @PostMapping("/password/forgot")
    public ResponseEntity<GenericMessageResponse> forgotPassword(@Valid @RequestBody EmailRequest request, HttpServletRequest servletRequest) {
        rateLimiter.checkPasswordRecovery(clientIpResolver.resolve(servletRequest), AuthenticationService.normalizeEmail(request.email()));
        authenticationService.requestPasswordHelp(request.email());
        return ResponseEntity.accepted().body(
                new GenericMessageResponse(AuthenticationService.GENERIC_RECOVERY_MESSAGE));
    }

    @PostMapping("/password/setup")
    public ResponseEntity<GenericMessageResponse> setupPassword(
            @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        authenticationService.requestPasswordSetup(principal.userId());
        return ResponseEntity.accepted().body(
                new GenericMessageResponse(AuthenticationService.GENERIC_RECOVERY_MESSAGE));
    }

    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest servletRequest) {
        rateLimiter.checkPasswordReset(clientIpResolver.resolve(servletRequest), request.token());
        authenticationService.resetPassword(request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest servletRequest
    ) {
        String currentSessionId = servletRequest.getSession(false) == null
                ? null
                : servletRequest.getSession(false).getId();
        authenticationService.changePassword(principal.userId(), request, currentSessionId);
        if (servletRequest.getSession(false) != null) {
            servletRequest.changeSessionId();
        }
        return ResponseEntity.noContent().build();
    }
}
