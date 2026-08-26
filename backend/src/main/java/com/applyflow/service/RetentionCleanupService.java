package com.applyflow.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.applyflow.repository.RetentionCleanupRepository;

@Service
public class RetentionCleanupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetentionCleanupService.class);

    private final RetentionCleanupRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration tokenRetention;
    private final Duration sessionRetention;
    private final Duration rateLimitRetention;
    private final Duration outboxRetention;

    public RetentionCleanupService(
            RetentionCleanupRepository repository,
            TransactionTemplate transactions,
            Clock clock,
            @Value("${app.retention.batch-size:500}") int batchSize,
            @Value("${app.retention.tokens:PT24H}") Duration tokenRetention,
            @Value("${app.retention.sessions:PT0S}") Duration sessionRetention,
            @Value("${app.retention.rate-limits:P1D}") Duration rateLimitRetention,
            @Value("${app.retention.outbox:P7D}") Duration outboxRetention,
            @Value("${app.security.rate-limit.window}") Duration rateLimitWindow
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("app.retention.batch-size must be positive");
        }
        if (tokenRetention.isNegative() || sessionRetention.isNegative()
                || rateLimitRetention.isNegative() || outboxRetention.isNegative()) {
            throw new IllegalArgumentException("app.retention durations must not be negative");
        }
        if (rateLimitRetention.compareTo(rateLimitWindow) < 0) {
            throw new IllegalArgumentException("app.retention.rate-limits must cover the active rate-limit window");
        }
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.tokenRetention = tokenRetention;
        this.sessionRetention = sessionRetention;
        this.rateLimitRetention = rateLimitRetention;
        this.outboxRetention = outboxRetention;
    }

    @Scheduled(cron = "${app.retention.cleanup-cron:0 30 * * * *}")
    public CleanupResult cleanupOnce() {
        long startedAt = System.nanoTime();
        Instant now = clock.instant();
        try {
            CleanupResult result = transactions.execute(status -> deleteOneBatchPerCategory(now));
            long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            LOGGER.info("retention_cleanup status=success tokens={} sessions={} rate_limits={} outbox={} duration_ms={}",
                    result.tokens(), result.sessions(), result.rateLimits(), result.outbox(), durationMs);
            return result;
        } catch (RuntimeException exception) {
            long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            LOGGER.error("retention_cleanup status=failed rolled_back=true committed_counts=0 duration_ms={}",
                    durationMs, exception);
            throw exception;
        }
    }

    private CleanupResult deleteOneBatchPerCategory(Instant now) {
        // Outbox rows go first so the token FK cascade never decides their retention.
        int outbox = repository.deleteTerminalOutboxRows(now.minus(outboxRetention), batchSize);
        int tokens = repository.deleteInactiveTokens(now.minus(tokenRetention), batchSize);
        int sessions = repository.deleteExpiredSessions(now.minus(sessionRetention), batchSize);
        int rateLimits = repository.deleteStaleRateLimits(now.minus(rateLimitRetention), batchSize);
        return new CleanupResult(tokens, sessions, rateLimits, outbox);
    }

    public record CleanupResult(int tokens, int sessions, int rateLimits, int outbox) {
    }
}
