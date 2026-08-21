package com.applyflow.entity;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "google_subject", length = 255)
    private String googleSubject;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "email_verified_at", columnDefinition = "timestamp(3) with time zone")
    private Instant emailVerifiedAt;

    @CreationTimestamp
    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "timestamp(3) with time zone")
    private Instant createdAt;

    @UpdateTimestamp
    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "updated_at", nullable = false,
            columnDefinition = "timestamp(3) with time zone")
    private Instant updatedAt;

    protected UserAccount() {
    }

    public UserAccount(String fullName, String email, String passwordHash) {
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public static UserAccount fromGoogle(String fullName, String email, String googleSubject, Instant verifiedAt) {
        UserAccount account = new UserAccount(fullName, email, null);
        account.googleSubject = googleSubject;
        account.emailVerifiedAt = verifiedAt;
        return account;
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getGoogleSubject() {
        return googleSubject;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public boolean hasGoogle() {
        return googleSubject != null;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public void verifyEmail(Instant verifiedAt) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = verifiedAt;
        }
    }

    public void attachGoogle(String subject, Instant verifiedAt) {
        googleSubject = subject;
        verifyEmail(verifiedAt);
    }

    public void claimPendingRegistrationWithGoogle(String subject, Instant verifiedAt) {
        passwordHash = null;
        googleSubject = subject;
        emailVerifiedAt = verifiedAt;
    }

    public void changePassword(String encodedPassword) {
        passwordHash = encodedPassword;
    }
}
