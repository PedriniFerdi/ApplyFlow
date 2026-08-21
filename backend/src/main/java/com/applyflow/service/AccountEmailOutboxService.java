package com.applyflow.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.applyflow.entity.AccountEmailOutbox;
import com.applyflow.entity.AccountToken;
import com.applyflow.repository.AccountEmailOutboxRepository;

@Service
public class AccountEmailOutboxService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountEmailOutboxService.class);
    private final AccountEmailOutboxRepository outboxRepository;
    private final AccountEmailSender emailSender;
    private final OutboxTokenCipher cipher;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final java.time.Duration claimLease;

    public AccountEmailOutboxService(AccountEmailOutboxRepository outboxRepository, AccountEmailSender emailSender,
            OutboxTokenCipher cipher, Clock clock, TransactionTemplate transactions,
            @org.springframework.beans.factory.annotation.Value("${app.mail.outbox.claim-lease:PT5M}") java.time.Duration claimLease) {
        this.outboxRepository = outboxRepository;
        this.emailSender = emailSender;
        this.cipher = cipher;
        this.clock = clock;
        this.transactions = transactions;
        this.claimLease = claimLease;
    }

    @Transactional
    public void enqueue(AccountTokenService.IssuedAccountToken issued) {
        Instant now = clock.instant();
        AccountEmailOutbox item = new AccountEmailOutbox(issued.token(), issued.token().getUser().getEmail(),
                issued.token().getUser().getFullName(), issued.token().getPurpose(), "pending", now);
        outboxRepository.saveAndFlush(item);
        // The UUID binds AES-GCM ciphertext to this exact durable message record.
        item.setEncryptedToken(cipher.encrypt(item.getId(), issued.rawToken()));
    }

    public boolean deliverNext() {
        ClaimResult result = transactions.execute(status -> claimNext());
        if (result == null) {
            return false;
        }
        if (result.claim() == null) {
            return true;
        }
        DeliveryClaim claim = result.claim();
        try {
            emailSender.sendAccountLink(claim.email(), claim.fullName(), claim.purpose(),
                    cipher.decrypt(claim.id(), claim.encryptedToken()), claim.id());
            transactions.executeWithoutResult(status -> completeSuccess(claim));
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> completeFailure(claim, exception));
            LOGGER.warn("Account email delivery will retry: messageId={}, purpose={}", claim.id(), claim.purpose());
        }
        return true;
    }

    private ClaimResult claimNext() {
        Instant now = clock.instant();
        var candidates = outboxRepository.findNextReadyForDelivery(
                now, now.minus(claimLease), PageRequest.of(0, 1));
        if (candidates.isEmpty()) {
            return null;
        }
        AccountEmailOutbox item = candidates.getFirst();
        if (!item.getToken().isUsableAt(now)) {
            item.discard(now);
            return new ClaimResult(null);
        }
        UUID claimToken = UUID.randomUUID();
        item.claim(claimToken, now);
        return new ClaimResult(new DeliveryClaim(item.getId(), claimToken, item.getEmail(), item.getFullName(),
                item.getPurpose(), item.getEncryptedToken()));
    }

    private void completeSuccess(DeliveryClaim claim) {
        outboxRepository.findByIdForUpdate(claim.id())
                .filter(item -> item.isClaimedBy(claim.claimToken()))
                .ifPresent(item -> item.markSent(clock.instant()));
    }

    private void completeFailure(DeliveryClaim claim, RuntimeException exception) {
        outboxRepository.findByIdForUpdate(claim.id())
                .filter(item -> item.isClaimedBy(claim.claimToken()))
                .ifPresent(item -> item.defer(clock.instant(), exception.getClass().getSimpleName()));
    }

    private record ClaimResult(DeliveryClaim claim) {
    }

    private record DeliveryClaim(UUID id, UUID claimToken, String email, String fullName,
            com.applyflow.entity.AccountTokenPurpose purpose, String encryptedToken) {
    }
}
