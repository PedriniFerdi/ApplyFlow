package com.applyflow.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.applyflow.entity.JobApplication;

import jakarta.persistence.LockModeType;

public interface JobApplicationRepository
        extends JpaRepository<JobApplication, Long>, JpaSpecificationExecutor<JobApplication> {

    @EntityGraph(attributePaths = {"company", "source", "technologies"})
    @Query("""
            select application from JobApplication application
            where application.id = :id and application.owner.id = :ownerId
            """)
    Optional<JobApplication> findDetailedByIdAndOwnerId(
            @Param("id") Long id,
            @Param("ownerId") Long ownerId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select application from JobApplication application
            where application.id = :id and application.owner.id = :ownerId
            """)
    Optional<JobApplication> findDetailedForUpdateByIdAndOwnerId(
            @Param("id") Long id,
            @Param("ownerId") Long ownerId
    );
}
