package com.applyflow.service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.applyflow.dto.application.ChangeApplicationStatusRequest;
import com.applyflow.dto.application.CreateJobApplicationRequest;
import com.applyflow.dto.application.UpdateJobApplicationRequest;
import com.applyflow.dto.catalog.NewCompanyRequest;
import com.applyflow.entity.ApplicationStatus;
import com.applyflow.entity.JobApplication;
import com.applyflow.entity.SalaryPeriod;
import com.applyflow.exception.BusinessRuleException;

@Component
public class JobApplicationValidator {

    private final Clock clock;

    public JobApplicationValidator(Clock clock) {
        this.clock = clock;
    }

    public void validateCreate(CreateJobApplicationRequest request) {
        validateCompanySelection(request.companyId(), request.newCompany());
        validateWebsite(request.newCompany());
        validateUrl(request.jobUrl(), "jobUrl");
        validateTechnologyIds(request.technologyIds());
        validateSalary(request.salaryMin(), request.salaryMax(), request.currency(), request.salaryPeriod());
        validateCreateDate(request.status(), request.appliedDate());
    }

    public void validateUpdate(UpdateJobApplicationRequest request, JobApplication current) {
        validateCompanySelection(request.companyId(), request.newCompany());
        validateWebsite(request.newCompany());
        validateUrl(request.jobUrl(), "jobUrl");
        validateTechnologyIds(request.technologyIds());
        validateSalary(request.salaryMin(), request.salaryMax(), request.currency(), request.salaryPeriod());
        validateNotFuture(request.appliedDate());

        if (current.getAppliedDate() != null && request.appliedDate() == null) {
            throw new BusinessRuleException("appliedDate cannot be removed after the application was applied");
        }
        if (current.getStatus() == ApplicationStatus.BOOKMARKED
                && current.getAppliedDate() == null
                && request.appliedDate() != null) {
            throw new BusinessRuleException("A bookmarked application cannot receive appliedDate through PUT");
        }
    }

    public void validateAppliedDateAgainstFirstResponse(LocalDate appliedDate, Instant firstResponseAt) {
        if (appliedDate != null
                && firstResponseAt != null
                && appliedDate.isAfter(firstResponseAt.atZone(ZoneOffset.UTC).toLocalDate())) {
            throw new BusinessRuleException("appliedDate cannot be after the first recorded response");
        }
    }

    public LocalDate resolveStatusAppliedDate(JobApplication current, ChangeApplicationStatusRequest request) {
        if (current.getStatus() == request.status()) {
            return current.getAppliedDate();
        }

        LocalDate existingDate = current.getAppliedDate();
        LocalDate requestedDate = request.appliedDate();
        validateNotFuture(requestedDate);

        if (existingDate != null) {
            if (requestedDate != null && !existingDate.equals(requestedDate)) {
                throw new BusinessRuleException("PATCH status cannot replace an existing appliedDate");
            }
            return existingDate;
        }

        if (request.status() == ApplicationStatus.BOOKMARKED) {
            if (requestedDate != null) {
                throw new BusinessRuleException("BOOKMARKED cannot have appliedDate before applying");
            }
            return null;
        }

        if (requestedDate == null) {
            throw new BusinessRuleException("appliedDate is required when moving to an applied status");
        }
        return requestedDate;
    }

    public String normalizeRequired(String value) {
        return value == null ? null : value.trim();
    }

    public String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public String normalizeCurrency(String currency) {
        String normalized = normalizeOptional(currency);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private void validateCompanySelection(Long companyId, NewCompanyRequest newCompany) {
        if ((companyId == null) == (newCompany == null)) {
            throw new BusinessRuleException("Exactly one of companyId or newCompany must be provided");
        }
    }

    private void validateWebsite(NewCompanyRequest company) {
        if (company != null) {
            validateUrl(company.website(), "newCompany.website");
        }
    }

    private void validateTechnologyIds(List<Long> technologyIds) {
        if (technologyIds != null && new HashSet<>(technologyIds).size() != technologyIds.size()) {
            throw new BusinessRuleException("technologyIds must not contain duplicates");
        }
    }

    private void validateCreateDate(ApplicationStatus status, LocalDate appliedDate) {
        validateNotFuture(appliedDate);
        if (status == ApplicationStatus.BOOKMARKED && appliedDate != null) {
            throw new BusinessRuleException("BOOKMARKED must be created without appliedDate");
        }
        if (status != ApplicationStatus.BOOKMARKED && appliedDate == null) {
            throw new BusinessRuleException("appliedDate is required for an applied status");
        }
    }

    private void validateNotFuture(LocalDate appliedDate) {
        if (appliedDate != null && appliedDate.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleException("appliedDate cannot be in the future");
        }
    }

    public SalaryAmounts validateSalary(
            String salaryMin,
            String salaryMax,
            String currency,
            SalaryPeriod salaryPeriod
    ) {
        BigDecimal minimum = parseSalary(salaryMin, "salaryMin");
        BigDecimal maximum = parseSalary(salaryMax, "salaryMax");
        boolean hasSalary = minimum != null || maximum != null;
        boolean hasMetadata = normalizeOptional(currency) != null || salaryPeriod != null;

        if (minimum != null && maximum != null && maximum.compareTo(minimum) < 0) {
            throw new BusinessRuleException("salaryMax must be greater than or equal to salaryMin");
        }
        if (hasSalary && (normalizeOptional(currency) == null || salaryPeriod == null)) {
            throw new BusinessRuleException("currency and salaryPeriod are required when salary is provided");
        }
        if (!hasSalary && hasMetadata) {
            throw new BusinessRuleException("currency and salaryPeriod must be absent when salary is absent");
        }
        String normalizedCurrency = normalizeCurrency(currency);
        if (normalizedCurrency != null && !normalizedCurrency.matches("[A-Z]{3}")) {
            throw new BusinessRuleException("currency must be a three-letter code");
        }
        return new SalaryAmounts(minimum, maximum);
    }

    private BigDecimal parseSalary(String rawAmount, String field) {
        String value = normalizeOptional(rawAmount);
        if (value == null) {
            return null;
        }
        if (!value.matches("(?:0|[1-9][0-9]{0,16})(?:\\.[0-9]{1,2})?")) {
            throw new BusinessRuleException(field + " must be a non-negative decimal string with at most 17 integer and 2 fractional digits");
        }
        return new BigDecimal(value);
    }

    public record SalaryAmounts(BigDecimal minimum, BigDecimal maximum) {
    }

    private void validateUrl(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return;
        }
        try {
            URI uri = new URI(normalized);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new BusinessRuleException(field + " must be an absolute HTTP or HTTPS URL");
            }
        } catch (URISyntaxException exception) {
            throw new BusinessRuleException(field + " must be a valid URL");
        }
    }
}
