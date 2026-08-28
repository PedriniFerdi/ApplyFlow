package com.applyflow.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.applyflow.entity.AccountToken;
import com.applyflow.entity.AccountTokenPurpose;
import com.applyflow.entity.UserAccount;
import com.applyflow.exception.BusinessRuleException;
import com.applyflow.repository.AccountTokenRepository;
import com.applyflow.repository.UserAccountRepository;

@Service
public class AccountTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final AccountTokenRepository tokenRepository;
    private final UserAccountRepository userRepository;
    private final Clock clock;
    private final Duration cooldown;

    public AccountTokenService(
            AccountTokenRepository tokenRepository,
            UserAccountRepository userRepository,
            Clock clock,
            @Value("${app.auth.token-cooldown}") Duration cooldown
    ) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.clock = clock;
        this.cooldown = cooldown;
    }

    public Optional<IssuedAccountToken> issue(UserAccount user, AccountTokenPurpose purpose, Duration lifetime) {
        Instant now = clock.instant();
        Optional<AccountToken> latest = tokenRepository
                .findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId(), purpose);
        if (latest.isPresent() && latest.get().getCreatedAt().isAfter(now.minus(cooldown))) {
            return Optional.empty();
        }
        tokenRepository.deleteByUserIdAndPurposeAndConsumedAtIsNull(user.getId(), purpose);
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String rawToken = URL_ENCODER.encodeToString(bytes);
        AccountToken token = tokenRepository.saveAndFlush(new AccountToken(user, purpose, hash(rawToken), now.plus(lifetime), now));
        return Optional.of(new IssuedAccountToken(token, rawToken));
    }

    public AccountToken consume(String rawToken, AccountTokenPurpose purpose) {
        return consume(rawToken, EnumSet.of(purpose));
    }

    public AccountToken consume(String rawToken, Set<AccountTokenPurpose> allowedPurposes) {
        Long ownerId = tokenRepository.findOwnerIdByTokenHash(hash(rawToken)).orElseThrow(this::invalidToken);
        return consumeForUser(rawToken, allowedPurposes, ownerId);
    }

    public AccountToken consumeForUser(String rawToken, AccountTokenPurpose purpose, Long userId) {
        return consumeForUser(rawToken, EnumSet.of(purpose), userId);
    }

    private AccountToken consumeForUser(String rawToken, Set<AccountTokenPurpose> allowedPurposes, Long userId) {
        // All account mutations lock the user before tokens or owned records.
        userRepository.findByIdForUpdate(userId).orElseThrow(this::invalidToken);
        Instant now = clock.instant();
        AccountToken token = tokenRepository.findByTokenHashAndUserId(hash(rawToken), userId)
                .orElseThrow(this::invalidToken);
        if (!allowedPurposes.contains(token.getPurpose()) || !token.isUsableAt(now)) {
            throw invalidToken();
        }
        token.consume(now);
        return token;
    }

    public void revokeAll(Long userId) {
        tokenRepository.deleteByUserIdAndConsumedAtIsNull(userId);
    }

    private BusinessRuleException invalidToken() {
        return new BusinessRuleException("The account link is invalid or has expired");
    }

    private String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record IssuedAccountToken(AccountToken token, String rawToken) {
    }
}
