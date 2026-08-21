package com.applyflow.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "account_email_outbox")
public class AccountEmailOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_token_id", nullable = false)
    private AccountToken token;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountTokenPurpose purpose;

    @Column(name = "encrypted_token", nullable = false, columnDefinition = "text")
    private String encryptedToken;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "next_attempt_at", nullable = false, columnDefinition = "timestamp(3) with time zone")
    private Instant nextAttemptAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "sent_at", columnDefinition = "timestamp(3) with time zone")
    private Instant sentAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "discarded_at", columnDefinition = "timestamp(3) with time zone")
    private Instant discardedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "claim_token")
    private UUID claimToken;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "claimed_at", columnDefinition = "timestamp(3) with time zone")
    private Instant claimedAt;

    protected AccountEmailOutbox() {
    }

    public AccountEmailOutbox(AccountToken token, String email, String fullName, AccountTokenPurpose purpose,
            String encryptedToken, Instant createdAt) {
        this.token = token;
        this.email = email;
        this.fullName = fullName;
        this.purpose = purpose;
        this.encryptedToken = encryptedToken;
        this.nextAttemptAt = createdAt;
    }

    public UUID getId() { return id; }
    public AccountToken getToken() { return token; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public AccountTokenPurpose getPurpose() { return purpose; }
    public String getEncryptedToken() { return encryptedToken; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getSentAt() { return sentAt; }
    public UUID getClaimToken() { return claimToken; }

    public void setEncryptedToken(String encryptedToken) {
        this.encryptedToken = encryptedToken;
    }

    public void markSent(Instant now) {
        sentAt = now;
        lastError = null;
        clearClaim();
    }

    public void defer(Instant now, String error) {
        attemptCount++;
        long delaySeconds = Math.min(3600, 1L << Math.min(attemptCount, 10));
        nextAttemptAt = now.plusSeconds(delaySeconds);
        lastError = error == null ? "Unknown delivery failure" : error.substring(0, Math.min(error.length(), 500));
        clearClaim();
    }

    public void discard(Instant now) {
        discardedAt = now;
        lastError = "The account link was consumed, revoked, or expired before delivery.";
        clearClaim();
    }

    public void claim(UUID token, Instant now) {
        claimToken = token;
        claimedAt = now;
    }

    public boolean isClaimedBy(UUID token) {
        return token.equals(claimToken);
    }

    private void clearClaim() {
        claimToken = null;
        claimedAt = null;
    }
}
