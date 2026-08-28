package com.applyflow.service;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.applyflow.entity.AccountTokenPurpose;
import com.applyflow.exception.BusinessRuleException;
import com.applyflow.exception.ResourceNotFoundException;
import com.applyflow.repository.UserAccountRepository;

@Service
public class AccountDeletionProofService {

    private final UserAccountRepository users;
    private final AccountTokenService tokens;
    private final AccountEmailOutboxService outbox;
    private final Duration lifetime;

    public AccountDeletionProofService(UserAccountRepository users, AccountTokenService tokens,
            AccountEmailOutboxService outbox, @Value("${app.auth.deletion-token-ttl}") Duration lifetime) {
        this.users = users;
        this.tokens = tokens;
        this.outbox = outbox;
        this.lifetime = lifetime;
    }

    @Transactional
    public void request(Long authenticatedUserId) {
        var user = users.findByIdForUpdate(authenticatedUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        if (!user.isEmailVerified()) {
            throw new BusinessRuleException("A verified account email is required");
        }
        tokens.issue(user, AccountTokenPurpose.ACCOUNT_DELETION, lifetime).ifPresent(outbox::enqueue);
    }

    // The caller must consume the proof and perform its protected action in the same transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Long authenticatedUserId, String code) {
        tokens.consumeForUser(code, AccountTokenPurpose.ACCOUNT_DELETION, authenticatedUserId);
    }
}
