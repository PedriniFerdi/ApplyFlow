package com.applyflow.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.applyflow.entity.AccountEmailOutbox;

import jakarta.persistence.LockModeType;

public interface AccountEmailOutboxRepository extends JpaRepository<AccountEmailOutbox, java.util.UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select item from AccountEmailOutbox item
            where item.sentAt is null and item.discardedAt is null and item.nextAttemptAt <= :now
              and (item.claimedAt is null or item.claimedAt <= :claimExpiredAt)
            order by item.nextAttemptAt asc
            """)
    List<AccountEmailOutbox> findNextReadyForDelivery(
            @Param("now") Instant now,
            @Param("claimExpiredAt") Instant claimExpiredAt,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from AccountEmailOutbox item where item.id = :id")
    Optional<AccountEmailOutbox> findByIdForUpdate(@Param("id") UUID id);
}
