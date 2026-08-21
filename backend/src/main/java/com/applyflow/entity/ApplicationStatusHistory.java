package com.applyflow.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "application_status_history",
        indexes = {
                @Index(name = "ix_status_history_application_changed_at", columnList = "application_id, changed_at"),
                @Index(name = "ix_status_history_status_application", columnList = "status, application_id")
        }
)
public class ApplicationStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private JobApplication application;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ApplicationStatus status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "changed_at", nullable = false,
            columnDefinition = "timestamp(3) with time zone")
    private Instant changedAt;

    protected ApplicationStatusHistory() {
    }

    public ApplicationStatusHistory(JobApplication application, ApplicationStatus status, Instant changedAt) {
        this.application = application;
        this.status = status;
        this.changedAt = changedAt;
    }

    public Long getId() {
        return id;
    }

    public JobApplication getApplication() {
        return application;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
