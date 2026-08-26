package com.applyflow.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RetentionCleanupRepository {

    private final JdbcTemplate jdbcTemplate;

    public RetentionCleanupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int deleteInactiveTokens(Instant cutoff, int batchSize) {
        OffsetDateTime cutoffUtc = cutoff.atOffset(ZoneOffset.UTC);
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT token.id
                    FROM account_tokens token
                    WHERE (token.expires_at < ? OR token.consumed_at < ?)
                      AND NOT EXISTS (
                          SELECT 1 FROM account_email_outbox outbox
                          WHERE outbox.account_token_id = token.id
                      )
                    ORDER BY LEAST(token.expires_at, COALESCE(token.consumed_at, token.expires_at)), token.id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM account_tokens token
                USING candidates
                WHERE token.id = candidates.id
                """, cutoffUtc, cutoffUtc, batchSize);
    }

    public int deleteExpiredSessions(Instant cutoff, int batchSize) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT stored_session.primary_id
                    FROM spring_session stored_session
                    WHERE stored_session.expiry_time < ?
                    ORDER BY stored_session.expiry_time, stored_session.primary_id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM spring_session stored_session
                USING candidates
                WHERE stored_session.primary_id = candidates.primary_id
                """, cutoff.toEpochMilli(), batchSize);
    }

    public int deleteStaleRateLimits(Instant cutoff, int batchSize) {
        OffsetDateTime cutoffUtc = cutoff.atOffset(ZoneOffset.UTC);
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT rate_limit.bucket_key
                    FROM auth_rate_limits rate_limit
                    WHERE rate_limit.window_started_at < ?
                    ORDER BY rate_limit.window_started_at, rate_limit.bucket_key
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM auth_rate_limits rate_limit
                USING candidates
                WHERE rate_limit.bucket_key = candidates.bucket_key
                """, cutoffUtc, batchSize);
    }

    public int deleteTerminalOutboxRows(Instant cutoff, int batchSize) {
        OffsetDateTime cutoffUtc = cutoff.atOffset(ZoneOffset.UTC);
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT outbox.id
                    FROM account_email_outbox outbox
                    WHERE (outbox.sent_at < ? OR outbox.discarded_at < ?)
                      AND outbox.claim_token IS NULL
                    ORDER BY COALESCE(outbox.sent_at, outbox.discarded_at), outbox.id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM account_email_outbox outbox
                USING candidates
                WHERE outbox.id = candidates.id
                """, cutoffUtc, cutoffUtc, batchSize);
    }
}
