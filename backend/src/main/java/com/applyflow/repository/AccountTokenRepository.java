package com.applyflow.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.applyflow.entity.AccountToken;
import com.applyflow.entity.AccountTokenPurpose;

import jakarta.persistence.LockModeType;

public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from AccountToken token where token.tokenHash = :hash and token.user.id = :userId")
    Optional<AccountToken> findByTokenHashAndUserId(
            @Param("hash") String tokenHash,
            @Param("userId") Long userId
    );

    @Query("select token.user.id from AccountToken token where token.tokenHash = :hash")
    Optional<Long> findOwnerIdByTokenHash(@Param("hash") String tokenHash);

    Optional<AccountToken> findFirstByUserIdAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            Long userId,
            AccountTokenPurpose purpose
    );

    long deleteByUserIdAndPurposeAndConsumedAtIsNull(Long userId, AccountTokenPurpose purpose);

    long deleteByUserIdAndConsumedAtIsNull(Long userId);

    long deleteByExpiresAtBefore(Instant instant);
}
