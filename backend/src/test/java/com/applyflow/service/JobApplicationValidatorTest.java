package com.applyflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.applyflow.dto.application.ChangeApplicationStatusRequest;
import com.applyflow.dto.application.CreateJobApplicationRequest;
import com.applyflow.dto.catalog.NewCompanyRequest;
import com.applyflow.entity.ApplicationStatus;
import com.applyflow.entity.Company;
import com.applyflow.entity.CompanyType;
import com.applyflow.entity.JobApplication;
import com.applyflow.entity.JobSource;
import com.applyflow.entity.SalaryPeriod;
import com.applyflow.entity.WorkMode;
import com.applyflow.entity.UserAccount;
import com.applyflow.exception.BusinessRuleException;

class JobApplicationValidatorTest {

    private final JobApplicationValidator validator = new JobApplicationValidator(
            Clock.fixed(Instant.parse("2026-08-11T23:59:59.999Z"), ZoneOffset.UTC));

    @Test
    void rejectsBookmarkedApplicationWithAppliedDate() {
        CreateJobApplicationRequest request = validRequest(
                ApplicationStatus.BOOKMARKED, LocalDate.of(2026, 8, 10), null, null, null, List.of(1L));

        assertThatThrownBy(() -> validator.validateCreate(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("BOOKMARKED");
    }

    @Test
    void rejectsDuplicateTechnologyIds() {
        CreateJobApplicationRequest request = validRequest(
                ApplicationStatus.APPLIED, LocalDate.of(2026, 8, 10), null, null, null, List.of(1L, 1L));

        assertThatThrownBy(() -> validator.validateCreate(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("duplicates");
    }

    @Test
    void rejectsSalaryWithoutMetadata() {
        CreateJobApplicationRequest request = validRequest(
                ApplicationStatus.APPLIED, LocalDate.of(2026, 8, 10),
                new BigDecimal("1000"), null, null, List.of());

        assertThatThrownBy(() -> validator.validateCreate(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("currency and salaryPeriod");
    }

    @Test
    void rejectsFutureAppliedDate() {
        CreateJobApplicationRequest request = validRequest(
                ApplicationStatus.APPLIED, LocalDate.of(2026, 8, 12),
                null, null, null, List.of());

        assertThatThrownBy(() -> validator.validateCreate(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("future");
    }

    @Test
    void usesUtcDateAtMidnightBoundary() {
        CreateJobApplicationRequest utcToday = validRequest(
                ApplicationStatus.APPLIED, LocalDate.of(2026, 8, 11),
                null, null, null, List.of());
        CreateJobApplicationRequest utcTomorrow = validRequest(
                ApplicationStatus.APPLIED, LocalDate.of(2026, 8, 12),
                null, null, null, List.of());

        assertThatCode(() -> validator.validateCreate(utcToday)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateCreate(utcTomorrow))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("future");
    }

    @Test
    void rejectsAmbiguousCompanySelectionAndInvalidUrls() {
        CreateJobApplicationRequest bothCompanies = request(
                1L, company("https://example.com"), "https://example.com/jobs/1",
                ApplicationStatus.BOOKMARKED, null, null, null, null, null, List.of());
        CreateJobApplicationRequest noCompany = request(
                null, null, "https://example.com/jobs/1",
                ApplicationStatus.BOOKMARKED, null, null, null, null, null, List.of());
        CreateJobApplicationRequest relativeJobUrl = request(
                null, company("https://example.com"), "/jobs/1",
                ApplicationStatus.BOOKMARKED, null, null, null, null, null, List.of());
        CreateJobApplicationRequest invalidCompanyUrl = request(
                null, company("ftp://example.com"), "https://example.com/jobs/1",
                ApplicationStatus.BOOKMARKED, null, null, null, null, null, List.of());

        assertThatThrownBy(() -> validator.validateCreate(bothCompanies))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Exactly one");
        assertThatThrownBy(() -> validator.validateCreate(noCompany))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Exactly one");
        assertThatThrownBy(() -> validator.validateCreate(relativeJobUrl))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("jobUrl");
        assertThatThrownBy(() -> validator.validateCreate(invalidCompanyUrl))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("newCompany.website");
    }

    @Test
    void enforcesSalaryMatrixAndAcceptsSingleBounds() {
        CreateJobApplicationRequest reversedRange = request(
                null, company("https://example.com"), null,
                ApplicationStatus.APPLIED, LocalDate.of(2026, 8, 10),
                new BigDecimal("2000"), new BigDecimal("1000"), "USD", SalaryPeriod.MONTHLY, List.of());
        CreateJobApplicationRequest metadataWithoutSalary = request(
                null, company("https://example.com"), null,
                ApplicationStatus.APPLIED, LocalDate.of(2026, 8, 10),
                null, null, "USD", SalaryPeriod.MONTHLY, List.of());
        CreateJobApplicationRequest invalidCurrency = request(
                null, company("https://example.com"), null,
                ApplicationStatus.APPLIED, LocalDate.of(2026, 8, 10),
                new BigDecimal("1000"), null, "US", SalaryPeriod.MONTHLY, List.of());
        CreateJobApplicationRequest minimumOnly = request(
                null, company("https://example.com"), null,
                ApplicationStatus.APPLIED, LocalDate.of(2026, 8, 10),
                new BigDecimal("1000"), null, " usd ", SalaryPeriod.YEARLY, List.of());
        CreateJobApplicationRequest maximumOnly = request(
                null, company("https://example.com"), null,
                ApplicationStatus.APPLIED, LocalDate.of(2026, 8, 10),
                null, new BigDecimal("2000"), "EUR", SalaryPeriod.MONTHLY, List.of());

        assertThatThrownBy(() -> validator.validateCreate(reversedRange))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("salaryMax");
        assertThatThrownBy(() -> validator.validateCreate(metadataWithoutSalary))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("absent");
        assertThatThrownBy(() -> validator.validateCreate(invalidCurrency))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("three-letter");
        assertThatCode(() -> validator.validateCreate(minimumOnly)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateCreate(maximumOnly)).doesNotThrowAnyException();
        assertThat(validator.normalizeCurrency(" usd ")).isEqualTo("USD");
    }

    @Test
    void enforcesTheExactDecimalStringContractAtNumericBoundaries() {
        CreateJobApplicationRequest maximum = request(
                null, company("https://example.com"), null,
                ApplicationStatus.APPLIED, LocalDate.of(2026, 8, 10),
                new BigDecimal("99999999999999999.99"), null, "USD", SalaryPeriod.YEARLY, List.of());
        CreateJobApplicationRequest tooLarge = request(
                null, company("https://example.com"), null,
                ApplicationStatus.APPLIED, LocalDate.of(2026, 8, 10),
                new BigDecimal("100000000000000000.00"), null, "USD", SalaryPeriod.YEARLY, List.of());

        assertThatCode(() -> validator.validateCreate(maximum)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateCreate(tooLarge))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("17 integer");
    }

    @Test
    void enforcesAppliedDateAcrossStatusTransitions() {
        LocalDate appliedDate = LocalDate.of(2026, 8, 10);
        JobApplication bookmarked = application(ApplicationStatus.BOOKMARKED, null);
        JobApplication applied = application(ApplicationStatus.APPLIED, appliedDate);

        assertThatThrownBy(() -> validator.validateCreate(request(
                null, company("https://example.com"), null,
                ApplicationStatus.APPLIED, null, null, null, null, null, List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> validator.resolveStatusAppliedDate(
                bookmarked, new ChangeApplicationStatusRequest(ApplicationStatus.APPLIED, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> validator.resolveStatusAppliedDate(
                applied,
                new ChangeApplicationStatusRequest(
                        ApplicationStatus.REJECTED, LocalDate.of(2026, 8, 9))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot replace");

        assertThat(validator.resolveStatusAppliedDate(
                applied, new ChangeApplicationStatusRequest(ApplicationStatus.BOOKMARKED, null)))
                .isEqualTo(appliedDate);
        assertThat(validator.resolveStatusAppliedDate(
                applied,
                new ChangeApplicationStatusRequest(
                        ApplicationStatus.APPLIED, LocalDate.of(2026, 8, 9))))
                .isEqualTo(appliedDate);
    }

    @Test
    void rejectsAppliedDateAfterFirstResponseUtcDate() {
        Instant firstResponseAt = Instant.parse("2026-08-05T23:30:00Z");

        assertThatCode(() -> validator.validateAppliedDateAgainstFirstResponse(
                LocalDate.of(2026, 8, 5), firstResponseAt)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateAppliedDateAgainstFirstResponse(
                LocalDate.of(2026, 8, 6), firstResponseAt))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("first recorded response");
    }

    private CreateJobApplicationRequest validRequest(
            ApplicationStatus status,
            LocalDate appliedDate,
            BigDecimal salaryMin,
            String currency,
            SalaryPeriod salaryPeriod,
            List<Long> technologyIds
    ) {
        return request(
                null, company("https://example.com"), "https://example.com/jobs/1",
                status, appliedDate, salaryMin, null, currency, salaryPeriod, technologyIds);
    }

    private CreateJobApplicationRequest request(
            Long companyId,
            NewCompanyRequest newCompany,
            String jobUrl,
            ApplicationStatus status,
            LocalDate appliedDate,
            BigDecimal salaryMin,
            BigDecimal salaryMax,
            String currency,
            SalaryPeriod salaryPeriod,
            List<Long> technologyIds
    ) {
        return new CreateJobApplicationRequest(
                companyId, newCompany, "Backend Engineer", jobUrl, appliedDate, status, 1L,
                WorkMode.REMOTE, null, decimalString(salaryMin), decimalString(salaryMax), currency, salaryPeriod, null, technologyIds);
    }

    private String decimalString(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private NewCompanyRequest company(String website) {
        return new NewCompanyRequest("ApplyFlow Test", website, CompanyType.OTHER, null);
    }

    private JobApplication application(ApplicationStatus status, LocalDate appliedDate) {
        UserAccount owner = new UserAccount("ApplyFlow Test", "test@applyflow.local", "{noop}unused");
        return new JobApplication(
                owner,
                new Company(owner, "ApplyFlow Test", null, CompanyType.OTHER, null),
                "Backend Engineer",
                null,
                appliedDate,
                status,
                new JobSource("LinkedIn"),
                WorkMode.REMOTE,
                null,
                null,
                null,
                null,
                null,
                null,
                Set.of()
        );
    }
}
