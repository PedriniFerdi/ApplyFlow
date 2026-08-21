package com.applyflow.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.applyflow.entity.ApplicationStatus;
import com.applyflow.entity.ApplicationStatusHistory;

public interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistory, Long> {

    List<ApplicationStatusHistory> findAllByApplicationIdOrderByChangedAtAscIdAsc(Long applicationId);

    boolean existsByApplicationIdAndStatus(Long applicationId, ApplicationStatus status);

    Optional<ApplicationStatusHistory> findFirstByApplicationIdAndStatusInOrderByChangedAtAscIdAsc(
            Long applicationId,
            Collection<ApplicationStatus> statuses
    );
}
