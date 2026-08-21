package com.applyflow.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.applyflow.entity.AccountToken;
import com.applyflow.entity.AccountTokenPurpose;

import jakarta.persistence.LockModeType;

public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountToken> findByTokenHash(String tokenHash);

    Optional<AccountToken> findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            Long userId,
            AccountTokenPurpose purpose
    );

    long deleteByUserIdAndPurposeAndConsumedAtIsNull(Long userId, AccountTokenPurpose purpose);

    long deleteByUserIdAndConsumedAtIsNull(Long userId);

    long deleteByExpiresAtBefore(Instant instant);
}
