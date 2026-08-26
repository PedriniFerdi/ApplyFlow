package com.applyflow.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.applyflow.exception.RateLimitExceededException;

@Service
public class AuthenticationRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationRateLimiter.class);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Duration window;
    private final int loginLimit;
    private final int registerLimit;
    private final int recoveryLimit;
    private final int resetLimit;
    private final int extractionLimit;

    public AuthenticationRateLimiter(
            JdbcTemplate jdbcTemplate,
            Clock clock,
            @Value("${app.security.rate-limit.window}") Duration window,
            @Value("${app.security.rate-limit.login-limit}") int loginLimit,
            @Value("${app.security.rate-limit.register-limit}") int registerLimit,
            @Value("${app.security.rate-limit.recovery-limit}") int recoveryLimit,
            @Value("${app.security.rate-limit.reset-limit}") int resetLimit,
            @Value("${app.security.rate-limit.job-offer-extraction-limit:20}") int extractionLimit
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.window = window;
        this.loginLimit = loginLimit;
        this.registerLimit = registerLimit;
        this.recoveryLimit = recoveryLimit;
        this.resetLimit = resetLimit;
        this.extractionLimit = extractionLimit;
    }

    public void checkLogin(String ipAddress, String email) {
        check("login", ipAddress, email, loginLimit);
    }

    public void checkRegister(String ipAddress, String email) {
        check("register", ipAddress, email, registerLimit);
    }

    public void checkPasswordRecovery(String ipAddress, String email) {
        check("password-recovery", ipAddress, email, recoveryLimit);
    }

    public void checkVerificationResend(String ipAddress, String email) {
        check("verification-resend", ipAddress, email, recoveryLimit);
    }

    public void checkPasswordReset(String ipAddress, String token) {
        check("password-reset", ipAddress, token, resetLimit);
    }

    public void checkJobOfferExtraction(String ipAddress, Long userId) {
        check("job-offer-extraction", ipAddress, String.valueOf(userId), extractionLimit);
    }

    private void check(String action, String ipAddress, String identity, int limit) {
        consume(action + ":ip:" + hash(normalize(ipAddress)), action, limit);
        if (identity != null && !identity.isBlank()) {
            consume(action + ":identity:" + hash(normalize(identity)), action, limit);
        }
    }

    private void consume(String bucket, String action, int limit) {
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        OffsetDateTime cutoff = now.minus(window);
        Integer attempts = jdbcTemplate.queryForObject("""
                INSERT INTO auth_rate_limits (bucket_key, window_started_at, attempts)
                VALUES (?, ?, 1)
                ON CONFLICT (bucket_key) DO UPDATE SET
                    attempts = CASE WHEN auth_rate_limits.window_started_at <= ? THEN 1 ELSE auth_rate_limits.attempts + 1 END,
                    window_started_at = CASE WHEN auth_rate_limits.window_started_at <= ? THEN EXCLUDED.window_started_at ELSE auth_rate_limits.window_started_at END
                RETURNING attempts
                """, Integer.class, bucket, now, cutoff, cutoff);
        if (attempts != null && attempts > limit) {
            long retryAfterSeconds = Math.max(1, window.toSeconds());
            LOGGER.warn("Application rate limit exceeded: action={}, bucket={}", action, bucket.substring(0, Math.min(bucket.length(), action.length() + 12)));
            throw new RateLimitExceededException(retryAfterSeconds);
        }
    }

    private String normalize(String value) {
        return value == null ? "unknown" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
