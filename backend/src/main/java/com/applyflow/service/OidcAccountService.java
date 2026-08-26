package com.applyflow.service;

import java.time.Clock;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.applyflow.entity.UserAccount;
import com.applyflow.repository.AccountTokenRepository;
import com.applyflow.repository.UserAccountRepository;
import com.applyflow.validation.RequestLimits;

@Service
public class OidcAccountService {

    private final UserAccountRepository userRepository;
    private final AccountTokenRepository tokenRepository;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OidcAccountService(
            UserAccountRepository userRepository,
            AccountTokenRepository tokenRepository,
            TransactionTemplate transactions,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.transactions = transactions;
        this.clock = clock;
    }

    public UserAccount reconcile(String subject, String rawEmail, boolean emailVerified, String rawName) {
        if (subject == null || subject.isBlank() || !emailVerified) {
            throw oauthError("unverified_email");
        }
        if (RequestLimits.exceedsCodePoints(subject, RequestLimits.PROVIDER_USER_ID)
                || RequestLimits.exceedsCodePoints(rawEmail, RequestLimits.EMAIL)
                || RequestLimits.exceedsCodePoints(rawName, RequestLimits.FULL_NAME)) {
            throw oauthError("invalid_claims");
        }
        String email = AuthenticationService.normalizeEmail(rawEmail);
        if (email.isBlank() || RequestLimits.exceedsCodePoints(email, RequestLimits.EMAIL)) {
            throw oauthError("missing_email");
        }
        try {
            return transactions.execute(status -> reconcileTransaction(subject, email, rawName));
        } catch (DataIntegrityViolationException exception) {
            return userRepository.findByGoogleSubject(subject)
                    .or(() -> userRepository.findByEmailIgnoreCase(email)
                            .filter(user -> subject.equals(user.getGoogleSubject())))
                    .orElseThrow(() -> oauthError("identity_conflict"));
        }
    }

    private UserAccount reconcileTransaction(String subject, String email, String rawName) {
        UserAccount bySubject = userRepository.findByGoogleSubject(subject).orElse(null);
        if (bySubject != null) {
            return bySubject;
        }
        UserAccount byEmail = userRepository.findByEmailForUpdate(email).orElse(null);
        if (byEmail == null) {
            int separator = email.indexOf('@');
            String emailName = separator > 0 ? email.substring(0, separator) : email;
            String name = rawName == null || rawName.isBlank() ? emailName : rawName.trim();
            if (RequestLimits.exceedsCodePoints(name, RequestLimits.FULL_NAME)) {
                throw oauthError("invalid_claims");
            }
            return userRepository.saveAndFlush(UserAccount.fromGoogle(name, email, subject, clock.instant()));
        }
        if (byEmail.getGoogleSubject() != null && !subject.equals(byEmail.getGoogleSubject())) {
            throw oauthError("identity_conflict");
        }
        if (byEmail.isEmailVerified()) {
            byEmail.attachGoogle(subject, clock.instant());
        } else {
            byEmail.claimPendingRegistrationWithGoogle(subject, clock.instant());
            tokenRepository.deleteByUserIdAndConsumedAtIsNull(byEmail.getId());
        }
        userRepository.flush();
        return byEmail;
    }

    private OAuth2AuthenticationException oauthError(String code) {
        return new OAuth2AuthenticationException(new OAuth2Error(code), "Google authentication could not be completed");
    }
}
