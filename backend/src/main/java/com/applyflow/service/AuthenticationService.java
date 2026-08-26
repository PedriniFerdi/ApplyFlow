package com.applyflow.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.applyflow.dto.auth.ChangePasswordRequest;
import com.applyflow.dto.auth.CurrentUserResponse;
import com.applyflow.dto.auth.RegisterRequest;
import com.applyflow.dto.auth.ResetPasswordRequest;
import com.applyflow.entity.AccountToken;
import com.applyflow.entity.AccountTokenPurpose;
import com.applyflow.entity.UserAccount;
import com.applyflow.exception.BusinessRuleException;
import com.applyflow.exception.ResourceNotFoundException;
import com.applyflow.repository.AccountTokenRepository;
import com.applyflow.repository.UserAccountRepository;
import com.applyflow.validation.RequestLimits;

@Service
public class AuthenticationService {

    public static final String GENERIC_REGISTRATION_MESSAGE =
            "If the account can be created or updated, an email will arrive with the next step.";
    public static final String GENERIC_RECOVERY_MESSAGE =
            "If an eligible account exists, an email will arrive with the next step.";

    private final UserAccountRepository userRepository;
    private final AccountTokenRepository tokenRepository;
    private final AccountTokenService tokenService;
    private final UserSessionService sessionService;
    private final PasswordEncoder passwordEncoder;
    private final AccountEmailOutboxService outboxService;
    private final Clock clock;
    private final Duration verificationTtl;
    private final Duration passwordTokenTtl;

    public AuthenticationService(
            UserAccountRepository userRepository,
            AccountTokenRepository tokenRepository,
            AccountTokenService tokenService,
            UserSessionService sessionService,
            PasswordEncoder passwordEncoder,
            AccountEmailOutboxService outboxService,
            Clock clock,
            @Value("${app.auth.verification-ttl}") Duration verificationTtl,
            @Value("${app.auth.password-token-ttl}") Duration passwordTokenTtl
    ) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.tokenService = tokenService;
        this.sessionService = sessionService;
        this.passwordEncoder = passwordEncoder;
        this.outboxService = outboxService;
        this.clock = clock;
        this.verificationTtl = verificationTtl;
        this.passwordTokenTtl = passwordTokenTtl;
    }

    @Transactional
    public void register(RegisterRequest request) {
        validatePasswords(request.password(), request.passwordConfirmation());
        String email = normalizeEmail(request.email());
        String fullName = normalizeFullName(request.fullName());
        UserAccount existing = userRepository.findByEmailForUpdate(email).orElse(null);
        if (existing == null) {
            UserAccount created = userRepository.saveAndFlush(
                    new UserAccount(fullName, email, passwordEncoder.encode(request.password())));
            issue(created, AccountTokenPurpose.EMAIL_VERIFICATION, verificationTtl);
            return;
        }
        if (!existing.isEmailVerified()) {
            issue(existing, AccountTokenPurpose.EMAIL_VERIFICATION, verificationTtl);
        } else if (!existing.hasPassword()) {
            issue(existing, AccountTokenPurpose.PASSWORD_SETUP, passwordTokenTtl);
        } else {
            issue(existing, AccountTokenPurpose.PASSWORD_RESET, passwordTokenTtl);
        }
    }

    @Transactional
    public void resendVerification(String rawEmail) {
        userRepository.findByEmailForUpdate(normalizeEmail(rawEmail))
                .filter(user -> !user.isEmailVerified())
                .ifPresent(user -> issue(user, AccountTokenPurpose.EMAIL_VERIFICATION, verificationTtl));
    }

    @Transactional
    public void confirmEmail(String rawToken) {
        AccountToken token = tokenService.consume(rawToken, AccountTokenPurpose.EMAIL_VERIFICATION);
        token.getUser().verifyEmail(clock.instant());
    }

    @Transactional
    public void requestPasswordHelp(String rawEmail) {
        userRepository.findByEmailForUpdate(normalizeEmail(rawEmail))
                .filter(UserAccount::isEmailVerified)
                .ifPresent(user -> issue(
                        user,
                        user.hasPassword() ? AccountTokenPurpose.PASSWORD_RESET : AccountTokenPurpose.PASSWORD_SETUP,
                        passwordTokenTtl));
    }

    @Transactional
    public void requestPasswordSetup(Long userId) {
        UserAccount user = requireUserForUpdate(userId);
        if (user.hasPassword()) {
            throw new BusinessRuleException("A password is already configured for this account");
        }
        issue(user, AccountTokenPurpose.PASSWORD_SETUP, passwordTokenTtl);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        validatePasswords(request.password(), request.passwordConfirmation());
        AccountToken token = tokenService.consume(
                request.token(), EnumSet.of(AccountTokenPurpose.PASSWORD_RESET, AccountTokenPurpose.PASSWORD_SETUP));
        UserAccount user = token.getUser();
        user.changePassword(passwordEncoder.encode(request.password()));
        user.verifyEmail(clock.instant());
        tokenRepository.deleteByUserIdAndConsumedAtIsNull(user.getId());
        sessionService.invalidateAll(user.getEmail());
    }

    @Transactional
    public void changePassword(
            Long userId,
            ChangePasswordRequest request,
            String currentSessionId
    ) {
        validatePasswords(request.password(), request.passwordConfirmation());
        validateBcryptLength(request.currentPassword());
        UserAccount user = requireUserForUpdate(userId);
        if (!user.hasPassword() || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        user.changePassword(passwordEncoder.encode(request.password()));
        tokenService.revokeAll(user.getId());
        sessionService.invalidateOther(user.getEmail(), currentSessionId);
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser(Long userId) {
        return CurrentUserResponse.from(requireUser(userId));
    }

    private void issue(UserAccount user, AccountTokenPurpose purpose, Duration ttl) {
        tokenService.issue(user, purpose, ttl).ifPresent(outboxService::enqueue);
    }

    private UserAccount requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private UserAccount requireUserForUpdate(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private String normalizeFullName(String fullName) {
        String normalized = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty() || RequestLimits.exceedsCodePoints(normalized, RequestLimits.FULL_NAME)) {
            throw new BusinessRuleException("Full name is invalid");
        }
        return normalized;
    }

    private void validatePasswords(String password, String confirmation) {
        if (!password.equals(confirmation)) {
            throw new BusinessRuleException("Password confirmation does not match");
        }
        int characterLength = password.codePointCount(0, password.length());
        if (characterLength < 12) {
            throw new BusinessRuleException("Password must be at least 12 characters and at most 72 UTF-8 bytes");
        }
        validateBcryptLength(password);
    }

    private void validateBcryptLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > RequestLimits.PASSWORD) {
            throw new BusinessRuleException("Password must be at least 12 characters and at most 72 UTF-8 bytes");
        }
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
