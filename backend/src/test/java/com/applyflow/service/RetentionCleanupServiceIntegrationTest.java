package com.applyflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = {
        "app.retention.cleanup-cron=-",
        "app.retention.batch-size=2",
        "app.retention.tokens=PT24H",
        "app.retention.sessions=PT0S",
        "app.retention.rate-limits=P1D",
        "app.retention.outbox=P7D",
        "app.mail.outbox.scheduling-enabled=false"
})
class RetentionCleanupServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RetentionCleanupService cleanupService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM account_email_outbox");
        jdbcTemplate.update("DELETE FROM account_tokens");
        jdbcTemplate.update("DELETE FROM auth_rate_limits");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void cleansOneBatchPerCategoryAndPreservesActiveOrClaimedRecords() {
        long userId = insertUser("retention@example.com");
        for (int index = 0; index < 3; index++) {
            Duration expiry = Duration.ofDays(index == 1 ? 1 : -10);
            Duration consumed = index == 1 ? Duration.ofDays(-10) : null;
            long tokenId = insertToken(userId, "eligible-" + index, expiry, consumed);
            insertOutbox(tokenId, Duration.ofDays(-8), false);
            insertSession("expired-" + index, Instant.now().minusSeconds(60).toEpochMilli());
            insertRateLimit("stale-" + index, "2 days ago");
        }

        long activeToken = insertToken(userId, "active", Duration.ofDays(1), null);
        long pendingToken = insertToken(userId, "pending", Duration.ofDays(-10), null);
        insertOutbox(pendingToken, null, false);
        long claimedToken = insertToken(userId, "claimed", Duration.ofDays(-10), null);
        insertOutbox(claimedToken, Duration.ofDays(-8), true);
        insertSession("active", Instant.now().plusSeconds(60).toEpochMilli());
        insertRateLimit("active", "1 minute ago");

        assertThat(cleanupService.cleanupOnce())
                .isEqualTo(new RetentionCleanupService.CleanupResult(2, 2, 2, 2));
        assertThat(cleanupService.cleanupOnce())
                .isEqualTo(new RetentionCleanupService.CleanupResult(1, 1, 1, 1));
        assertThat(cleanupService.cleanupOnce())
                .isEqualTo(new RetentionCleanupService.CleanupResult(0, 0, 0, 0));

        assertThat(count("account_tokens")).isEqualTo(3);
        assertThat(count("account_tokens WHERE id = " + activeToken)).isOne();
        assertThat(count("account_email_outbox")).isEqualTo(2);
        assertThat(count("account_email_outbox WHERE claim_token IS NOT NULL")).isOne();
        assertThat(count("spring_session")).isOne();
        assertThat(count("auth_rate_limits")).isOne();
    }

    @Test
    void concurrentExecutorsRespectTheSharedBatchBoundWithoutDoubleDeleting() throws Exception {
        for (int index = 0; index < 8; index++) {
            insertRateLimit("concurrent-" + index, "2 days ago");
        }
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(
                    executor.submit(() -> cleanupAfter(start)),
                    executor.submit(() -> cleanupAfter(start)));
            start.countDown();
            int deleted = 0;
            for (var task : tasks) {
                var result = task.get(10, TimeUnit.SECONDS);
                assertThat(result.rateLimits()).isBetween(0, 2);
                deleted += result.rateLimits();
            }
            assertThat(deleted).isEqualTo(4);
        }
        assertThat(count("auth_rate_limits")).isEqualTo(4);
    }

    private RetentionCleanupService.CleanupResult cleanupAfter(CountDownLatch start) throws InterruptedException {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return cleanupService.cleanupOnce();
    }

    private long insertUser(String email) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (full_name, email) VALUES ('Retention Test', ?) RETURNING id
                """, Long.class, email);
    }

    private long insertToken(long userId, String hash, Duration expiryOffset, Duration consumedOffset) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return jdbcTemplate.queryForObject("""
                INSERT INTO account_tokens (user_id, purpose, token_hash, expires_at, created_at, consumed_at)
                VALUES (?, 'PASSWORD_RESET', ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                RETURNING id
                """, Long.class, userId, String.format("%064x", hash.hashCode() & 0xffffffffL),
                now.plus(expiryOffset), now.minusDays(11), consumedOffset == null ? null : now.plus(consumedOffset));
    }

    private void insertOutbox(long tokenId, Duration terminalOffset, boolean claimed) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO account_email_outbox
                    (id, account_token_id, email, full_name, purpose, encrypted_token, next_attempt_at,
                     sent_at, claim_token, claimed_at)
                VALUES (?, ?, 'retention@example.com', 'Retention Test', 'PASSWORD_RESET', 'ciphertext',
                        ?::timestamptz, ?::timestamptz, ?::uuid, ?::timestamptz)
                """, UUID.randomUUID(), tokenId, now,
                terminalOffset == null ? null : now.plus(terminalOffset),
                claimed ? UUID.randomUUID() : null, claimed ? now : null);
    }

    private void insertSession(String id, long expiryTime) {
        jdbcTemplate.update("""
                INSERT INTO spring_session
                    (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time)
                VALUES (?, ?, ?, ?, 3600, ?)
                """, id, "session-" + id, expiryTime - 3_600_000, expiryTime - 3_600_000, expiryTime);
    }

    private void insertRateLimit(String key, String offset) {
        jdbcTemplate.update("""
                INSERT INTO auth_rate_limits (bucket_key, window_started_at, attempts)
                VALUES (?, CURRENT_TIMESTAMP + ?::interval, 1)
                """, key, offset);
    }

    private int count(String fromClause) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + fromClause, Integer.class);
    }
}
