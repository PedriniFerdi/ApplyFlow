package com.applyflow.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.applyflow.security.AuthenticatedUser;
import com.applyflow.security.TrustedClientIpResolver;
import com.applyflow.service.AccountExportService;
import com.applyflow.service.AuthenticationRateLimiter;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/account")
public class AccountLifecycleController {

    private final AccountExportService exports;
    private final AuthenticationRateLimiter rateLimiter;
    private final TrustedClientIpResolver clientIp;

    public AccountLifecycleController(AccountExportService exports, AuthenticationRateLimiter rateLimiter, TrustedClientIpResolver clientIp) {
        this.exports = exports;
        this.rateLimiter = rateLimiter;
        this.clientIp = clientIp;
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
