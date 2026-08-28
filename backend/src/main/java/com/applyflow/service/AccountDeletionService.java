package com.applyflow.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.applyflow.exception.ResourceNotFoundException;
import com.applyflow.repository.UserAccountRepository;

import jakarta.persistence.EntityManager;

@Service
public class AccountDeletionService {

    private final UserAccountRepository users;
    private final AccountDeletionProofService proofs;
    private final AuthenticationRateLimiter rateLimiter;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;

    public AccountDeletionService(UserAccountRepository users, AccountDeletionProofService proofs,
            AuthenticationRateLimiter rateLimiter, JdbcTemplate jdbc, EntityManager entities) {
        this.users = users;
        this.proofs = proofs;
        this.rateLimiter = rateLimiter;
        this.jdbc = jdbc;
        this.entities = entities;
    }

    @Transactional(timeout = 30)
    public void delete(Long authenticatedUserId, String token) {
        var user = users.findByIdForUpdate(authenticatedUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        proofs.consume(authenticatedUserId, token);
        // Flush the consumed token before cascades remove it; never flush a deleted managed token later.
        entities.flush();
        jdbc.update("DELETE FROM job_applications WHERE owner_id = ?", authenticatedUserId);
        jdbc.update("DELETE FROM companies WHERE owner_id = ?", authenticatedUserId);
        jdbc.update("DELETE FROM technologies WHERE owner_id = ?", authenticatedUserId);
        // Use this transaction, not Spring Session's independently committed repository operations.
        jdbc.update("DELETE FROM spring_session WHERE principal_name = ?", user.getEmail());
        rateLimiter.clearAccount(authenticatedUserId, user.getEmail());
        jdbc.update("DELETE FROM users WHERE id = ?", authenticatedUserId);
        entities.clear();
    }
}
