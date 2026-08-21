package com.applyflow.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "job_applications",
        indexes = {
                @Index(name = "ix_job_applications_owner_status_applied_date", columnList = "owner_id, status, applied_date"),
                @Index(name = "ix_job_applications_owner_source_id", columnList = "owner_id, source_id"),
                @Index(name = "ix_job_applications_owner_company_id", columnList = "owner_id, company_id"),
                @Index(name = "ix_job_applications_owner_created_at", columnList = "owner_id, created_at")
        }
)
public class JobApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserAccount owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "position_title", nullable = false, length = 180)
    private String positionTitle;

    @Column(name = "job_url", length = 1000)
    private String jobUrl;

    @Column(name = "applied_date")
    private LocalDate appliedDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    // Intentionally denormalized; status changes must append history in the same transaction.
    private ApplicationStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private JobSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_mode", nullable = false, length = 16)
    private WorkMode workMode;

    @Column(length = 160)
    private String location;

    @Column(name = "salary_min", precision = 19, scale = 2)
    private BigDecimal salaryMin;

    @Column(name = "salary_max", precision = 19, scale = 2)
    private BigDecimal salaryMax;

    @Column(length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "salary_period", length = 16)
    private SalaryPeriod salaryPeriod;

    @Column(columnDefinition = "text")
    private String notes;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "job_application_technologies",
            joinColumns = @JoinColumn(name = "application_id"),
            inverseJoinColumns = @JoinColumn(name = "technology_id")
    )
    @OrderBy("name ASC")
    private Set<Technology> technologies = new LinkedHashSet<>();

    @CreationTimestamp
    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "timestamp(3) with time zone")
    private Instant createdAt;

    @UpdateTimestamp
    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "updated_at", nullable = false,
            columnDefinition = "timestamp(3) with time zone")
    private Instant updatedAt;

    protected JobApplication() {
    }

    public JobApplication(
            UserAccount owner,
            Company company,
            String positionTitle,
            String jobUrl,
            LocalDate appliedDate,
            ApplicationStatus status,
            JobSource source,
            WorkMode workMode,
            String location,
            BigDecimal salaryMin,
            BigDecimal salaryMax,
            String currency,
            SalaryPeriod salaryPeriod,
            String notes,
            Set<Technology> technologies
    ) {
        this.owner = owner;
        this.company = company;
        this.positionTitle = positionTitle;
        this.jobUrl = jobUrl;
        this.appliedDate = appliedDate;
        this.status = status;
        this.source = source;
        this.workMode = workMode;
        this.location = location;
        this.salaryMin = salaryMin;
        this.salaryMax = salaryMax;
        this.currency = currency;
        this.salaryPeriod = salaryPeriod;
        this.notes = notes;
        this.technologies.addAll(technologies);
    }

    public Long getId() {
        return id;
    }

    public UserAccount getOwner() {
        return owner;
    }

    public Company getCompany() {
        return company;
    }

    public String getPositionTitle() {
        return positionTitle;
    }

    public String getJobUrl() {
        return jobUrl;
    }

    public LocalDate getAppliedDate() {
        return appliedDate;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public JobSource getSource() {
        return source;
    }

    public WorkMode getWorkMode() {
        return workMode;
    }

    public String getLocation() {
        return location;
    }

    public BigDecimal getSalaryMin() {
        return salaryMin;
    }

    public BigDecimal getSalaryMax() {
        return salaryMax;
    }

    public String getCurrency() {
        return currency;
    }

    public SalaryPeriod getSalaryPeriod() {
        return salaryPeriod;
    }

    public String getNotes() {
        return notes;
    }

    public Set<Technology> getTechnologies() {
        return Set.copyOf(technologies);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void changeStatus(ApplicationStatus status, LocalDate appliedDate) {
        this.status = status;
        this.appliedDate = appliedDate;
    }

    public void updateDetails(
            Company company,
            String positionTitle,
            String jobUrl,
            LocalDate appliedDate,
            JobSource source,
            WorkMode workMode,
            String location,
            BigDecimal salaryMin,
            BigDecimal salaryMax,
            String currency,
            SalaryPeriod salaryPeriod,
            String notes,
            Set<Technology> technologies
    ) {
        this.company = company;
        this.positionTitle = positionTitle;
        this.jobUrl = jobUrl;
        this.appliedDate = appliedDate;
        this.source = source;
        this.workMode = workMode;
        this.location = location;
        this.salaryMin = salaryMin;
        this.salaryMax = salaryMax;
        this.currency = currency;
        this.salaryPeriod = salaryPeriod;
        this.notes = notes;
        replaceTechnologies(technologies);
    }

    public void replaceTechnologies(Set<Technology> technologies) {
        this.technologies.clear();
        this.technologies.addAll(technologies);
    }
}
