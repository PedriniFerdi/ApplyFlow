package com.applyflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

final class AccountLockTestSupport {

    private AccountLockTestSupport() {
    }

    static <T> T whileUserLocked(JdbcTemplate jdbc, TransactionTemplate transactions, Long userId,
            Callable<T> operation, Runnable whileBlocked) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<T> result = transactions.execute(status -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
                AtomicInteger workerPid = new AtomicInteger();
                Future<T> worker = executor.submit(() -> transactions.execute(inner -> {
                    workerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    try {
                        return operation.call();
                    } catch (Exception exception) {
                        throw new IllegalStateException("Concurrent operation failed", exception);
                    }
                }));
                boolean waiting = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!worker.isDone() && System.nanoTime() < deadline) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                            WHERE pid = ? AND wait_event_type = 'Lock' AND query LIKE '%users%')
                            """, Boolean.class, workerPid.get()));
                    if (waiting) {
                        break;
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                }
                assertThat(waiting).as("operation must wait on the account before dependent rows").isTrue();
                whileBlocked.run();
                return worker;
            });
            return result.get(10, TimeUnit.SECONDS);
        }
    }
}
