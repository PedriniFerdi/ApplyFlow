package com.applyflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("legacy-api-test")
@Import(LegacyApiTestSecurityConfiguration.class)
class AnalyticsApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void ensureTestUser() {
        jdbcTemplate.update("""
                INSERT INTO users (id, full_name, email, password_hash, email_verified_at)
                VALUES (1, 'Legacy API Test', 'legacy-api-test@applyflow.local', '{noop}unused', CURRENT_TIMESTAMP)
                ON CONFLICT DO NOTHING
                """);
    }

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM job_applications");
        jdbcTemplate.update("DELETE FROM companies WHERE name LIKE 'Analytics %'");
    }

    @Test
    void returnsDeterministicEmptyContractsAndRejectsInvalidPeriods() {
        JsonNode summary = get("/api/analytics/summary");
        assertThat(summary.path("totalApplications").asLong()).isZero();
        assertThat(summary.path("appliedApplications").asLong()).isZero();
        assertThat(summary.path("responseRate").decimalValue()).isEqualByComparingTo("0.00");

        JsonNode funnel = get("/api/analytics/funnel");
        assertThat(funnel.path("stages")).hasSize(6);
        assertThat(funnel.path("stages")).allSatisfy(stage ->
                assertThat(stage.path("count").asLong()).isZero());

        assertThat(get("/api/analytics/applications-over-time?period=WEEK").path("buckets")).isEmpty();
        assertThat(get("/api/analytics/sources").path("items")).isEmpty();
        assertThat(get("/api/analytics/technologies").path("items")).isEmpty();

        JsonNode responseTime = get("/api/analytics/response-time");
        assertThat(responseTime.path("sampleSize").asLong()).isZero();
        assertThat(responseTime.path("averageDays").isNull()).isTrue();
        assertThat(responseTime.path("medianDays").isNull()).isTrue();

        assertBadPeriod("/api/analytics/applications-over-time");
        assertBadPeriod("/api/analytics/applications-over-time?period=week");
        assertBadPeriod("/api/analytics/applications-over-time?period=QUARTER");
    }

    @Test
    void calculatesHistoryBasedSummaryAndFirstResponseWithoutDoubleCounting() {
        AnalyticsFixture fixture = createProgressionFixture();

        JsonNode summary = get("/api/analytics/summary");

        assertThat(summary.path("totalApplications").asLong()).isEqualTo(5);
        assertThat(summary.path("appliedApplications").asLong()).isEqualTo(4);
        assertMetric(summary, "response", 3, "75.00");
        assertMetric(summary, "interview", 2, "50.00");
        assertMetric(summary, "offer", 1, "25.00");
        assertMetric(summary, "rejection", 2, "50.00");

        JsonNode responseTime = get("/api/analytics/response-time");
        assertThat(responseTime.path("sampleSize").asLong()).isEqualTo(3);
        assertThat(responseTime.path("averageDays").decimalValue()).isEqualByComparingTo("3.67");
        assertThat(responseTime.path("medianDays").decimalValue()).isEqualByComparingTo("2.00");

        assertThat(fixture.bookmarkedId()).isPositive();
    }

    @Test
    void reportsExactNonInferredFunnelStagesInFixedOrder() {
        createProgressionFixture();

        JsonNode stages = get("/api/analytics/funnel").path("stages");

        assertThat(statuses(stages)).containsExactly(
                "APPLIED", "RESPONSE_RECEIVED", "HR_INTERVIEW",
                "TECHNICAL_INTERVIEW", "FINAL_INTERVIEW", "OFFER");
        assertThat(counts(stages)).containsExactly(3L, 1L, 2L, 0L, 0L, 1L);
    }

    @Test
    void aggregatesSourcesAndTechnologiesOncePerEligibleApplication() {
        AnalyticsFixture fixture = createProgressionFixture();

        JsonNode sources = get("/api/analytics/sources").path("items");
        assertThat(names(sources)).containsExactly("LinkedIn", "Company Website", "Referral");
        JsonNode linkedIn = sources.get(0);
        assertThat(linkedIn.path("applicationCount").asLong()).isEqualTo(2);
        assertMetric(linkedIn, "response", 1, "50.00");
        assertMetric(linkedIn, "interview", 1, "50.00");
        assertMetric(linkedIn, "offer", 0, "0.00");
        assertMetric(linkedIn, "rejection", 2, "100.00");

        JsonNode technologies = get("/api/analytics/technologies").path("items");
        assertThat(names(technologies)).containsExactly("Java", "Spring Boot");
        JsonNode java = technologies.get(0);
        assertThat(java.path("id").asLong()).isEqualTo(fixture.javaId());
        assertThat(java.path("applicationCount").asLong()).isEqualTo(2);
        assertMetric(java, "response", 2, "100.00");
        assertMetric(java, "interview", 2, "100.00");
        assertMetric(java, "rejection", 1, "50.00");

        JsonNode spring = technologies.get(1);
        assertThat(spring.path("id").asLong()).isEqualTo(fixture.springId());
        assertThat(spring.path("applicationCount").asLong()).isEqualTo(2);
        assertMetric(spring, "response", 1, "50.00");
        assertMetric(spring, "rejection", 2, "100.00");
    }

    @Test
    void groupsAppliedDatesByIsoWeekAndCalendarMonthAcrossYearBoundaries() {
        long sourceId = catalogId("job_sources", "LinkedIn");
        insertAppliedApplication(sourceId, LocalDate.of(2025, 12, 29));
        insertAppliedApplication(sourceId, LocalDate.of(2025, 12, 31));
        insertAppliedApplication(sourceId, LocalDate.of(2026, 1, 4));
        insertAppliedApplication(sourceId, LocalDate.of(2026, 1, 5));
        insertAppliedApplication(sourceId, LocalDate.of(2026, 2, 1));

        JsonNode weeks = get("/api/analytics/applications-over-time?period=WEEK");
        assertThat(weeks.path("period").asText()).isEqualTo("WEEK");
        assertBuckets(weeks.path("buckets"),
                List.of("2025-12-29", "2026-01-05", "2026-01-26"),
                List.of(3L, 1L, 1L));

        JsonNode months = get("/api/analytics/applications-over-time?period=MONTH");
        assertThat(months.path("period").asText()).isEqualTo("MONTH");
        assertBuckets(months.path("buckets"),
                List.of("2025-12-01", "2026-01-01", "2026-02-01"),
                List.of(2L, 2L, 1L));
    }

    @Test
    void returnsZeroProgressionForBookmarkOnlyData() {
        long sourceId = catalogId("job_sources", "LinkedIn");
        long technologyId = catalogId("technologies", "Java");
        long applicationId = insertApplication(sourceId, null, "BOOKMARKED", "Analytics Bookmark Only");
        insertHistory(applicationId, "BOOKMARKED", "2026-08-01T09:00:00Z");
        attachTechnology(applicationId, technologyId);

        JsonNode summary = get("/api/analytics/summary");
        assertThat(summary.path("totalApplications").asLong()).isEqualTo(1);
        assertThat(summary.path("appliedApplications").asLong()).isZero();
        assertMetric(summary, "response", 0, "0.00");
        assertMetric(summary, "interview", 0, "0.00");
        assertMetric(summary, "offer", 0, "0.00");
        assertMetric(summary, "rejection", 0, "0.00");
        assertThat(get("/api/analytics/applications-over-time?period=MONTH").path("buckets")).isEmpty();
        assertThat(get("/api/analytics/sources").path("items")).isEmpty();
        assertThat(get("/api/analytics/technologies").path("items")).isEmpty();
    }

    @Test
    void coversRemainingStatusesTiedResponsesAndLeapDayBuckets() {
        long sourceId = catalogId("job_sources", "LinkedIn");

        long technicalId = insertApplication(
                sourceId, LocalDate.of(2024, 2, 28), "FINAL_INTERVIEW", "Analytics Technical");
        insertHistory(technicalId, "APPLIED", "2024-02-28T09:00:00Z");
        insertHistory(technicalId, "TECHNICAL_INTERVIEW", "2024-02-29T10:00:00Z");
        insertHistory(technicalId, "FINAL_INTERVIEW", "2024-02-29T10:00:00Z");

        long finalId = insertApplication(
                sourceId, LocalDate.of(2024, 2, 29), "FINAL_INTERVIEW", "Analytics Final");
        insertHistory(finalId, "APPLIED", "2024-02-29T09:00:00Z");
        insertHistory(finalId, "FINAL_INTERVIEW", "2024-03-01T09:00:00Z");

        long withdrawnId = insertApplication(
                sourceId, LocalDate.of(2024, 3, 1), "WITHDRAWN", "Analytics Withdrawn");
        insertHistory(withdrawnId, "APPLIED", "2024-03-01T09:00:00Z");
        insertHistory(withdrawnId, "WITHDRAWN", "2024-03-02T09:00:00Z");

        JsonNode summary = get("/api/analytics/summary");
        assertThat(summary.path("totalApplications").asLong()).isEqualTo(3);
        assertThat(summary.path("appliedApplications").asLong()).isEqualTo(3);
        assertMetric(summary, "response", 2, "66.67");
        assertMetric(summary, "interview", 2, "66.67");
        assertMetric(summary, "offer", 0, "0.00");
        assertMetric(summary, "rejection", 0, "0.00");

        JsonNode funnel = get("/api/analytics/funnel").path("stages");
        assertThat(counts(funnel)).containsExactly(3L, 0L, 0L, 1L, 2L, 0L);

        JsonNode responseTime = get("/api/analytics/response-time");
        assertThat(responseTime.path("sampleSize").asLong()).isEqualTo(2);
        assertThat(responseTime.path("averageDays").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(responseTime.path("medianDays").decimalValue()).isEqualByComparingTo("1.00");

        JsonNode months = get("/api/analytics/applications-over-time?period=MONTH");
        assertBuckets(months.path("buckets"),
                List.of("2024-02-01", "2024-03-01"),
                List.of(2L, 1L));
    }

    @Test
    void measuresSameDayAndNextDayResponsesAsZeroAndOneCalendarDays() {
        long sourceId = catalogId("job_sources", "LinkedIn");

        long sameDayId = insertApplication(
                sourceId, LocalDate.of(2026, 8, 1), "RESPONSE_RECEIVED", "Analytics Same Day");
        insertHistory(sameDayId, "APPLIED", "2026-08-01T00:15:00Z");
        insertHistory(sameDayId, "RESPONSE_RECEIVED", "2026-08-01T23:59:59Z");

        long nextDayId = insertApplication(
                sourceId, LocalDate.of(2026, 8, 1), "RESPONSE_RECEIVED", "Analytics Next Day");
        insertHistory(nextDayId, "APPLIED", "2026-08-01T23:59:59Z");
        insertHistory(nextDayId, "RESPONSE_RECEIVED", "2026-08-02T00:00:00Z");

        JsonNode responseTime = get("/api/analytics/response-time");
        assertThat(responseTime.path("sampleSize").asLong()).isEqualTo(2);
        assertThat(responseTime.path("averageDays").decimalValue()).isEqualByComparingTo("0.50");
        assertThat(responseTime.path("medianDays").decimalValue()).isEqualByComparingTo("0.50");
    }

    private AnalyticsFixture createProgressionFixture() {
        long linkedInId = catalogId("job_sources", "LinkedIn");
        long referralId = catalogId("job_sources", "Referral");
        long companyWebsiteId = catalogId("job_sources", "Company Website");
        long javaId = catalogId("technologies", "Java");
        long springId = catalogId("technologies", "Spring Boot");

        long bookmarkedId = insertApplication(linkedInId, null, "BOOKMARKED", "Analytics Bookmark");
        insertHistory(bookmarkedId, "BOOKMARKED", "2026-07-31T09:00:00Z");
        attachTechnology(bookmarkedId, javaId);

        long firstId = insertApplication(linkedInId, LocalDate.of(2026, 8, 1), "REJECTED", "Analytics First");
        insertHistory(firstId, "APPLIED", "2026-08-01T09:00:00Z");
        insertHistory(firstId, "RESPONSE_RECEIVED", "2026-08-03T09:00:00Z");
        insertHistory(firstId, "HR_INTERVIEW", "2026-08-05T09:00:00Z");
        insertHistory(firstId, "RESPONSE_RECEIVED", "2026-08-07T09:00:00Z");
        insertHistory(firstId, "REJECTED", "2026-08-08T09:00:00Z");
        attachTechnology(firstId, javaId);
        attachTechnology(firstId, springId);

        long secondId = insertApplication(referralId, LocalDate.of(2026, 8, 2), "BOOKMARKED", "Analytics Second");
        insertHistory(secondId, "HR_INTERVIEW", "2026-08-10T09:00:00Z");
        insertHistory(secondId, "BOOKMARKED", "2026-08-11T09:00:00Z");
        attachTechnology(secondId, javaId);

        long thirdId = insertApplication(linkedInId, LocalDate.of(2026, 8, 3), "REJECTED", "Analytics Third");
        insertHistory(thirdId, "APPLIED", "2026-08-03T09:00:00Z");
        insertHistory(thirdId, "REJECTED", "2026-08-06T09:00:00Z");
        attachTechnology(thirdId, springId);

        long fourthId = insertApplication(
                companyWebsiteId, LocalDate.of(2026, 8, 4), "OFFER", "Analytics Fourth");
        insertHistory(fourthId, "APPLIED", "2026-08-04T09:00:00Z");
        insertHistory(fourthId, "OFFER", "2026-08-05T09:00:00Z");

        return new AnalyticsFixture(bookmarkedId, javaId, springId);
    }

    private long insertAppliedApplication(long sourceId, LocalDate appliedDate) {
        long applicationId = insertApplication(sourceId, appliedDate, "APPLIED", "Analytics Time " + appliedDate);
        insertHistory(applicationId, "APPLIED", appliedDate.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toString());
        return applicationId;
    }

    private long insertApplication(long sourceId, LocalDate appliedDate, String status, String positionTitle) {
        long companyId = jdbcTemplate.queryForObject("""
                INSERT INTO companies (owner_id, name, company_type)
                VALUES (1, ?, 'OTHER')
                RETURNING id
                """, Long.class, "Analytics Company " + positionTitle);
        return jdbcTemplate.queryForObject("""
                INSERT INTO job_applications (
                    owner_id, company_id, position_title, applied_date, status, source_id, work_mode, created_at, updated_at
                )
                VALUES (1, ?, ?, ?, ?, ?, 'REMOTE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, companyId, positionTitle,
                appliedDate, status, sourceId);
    }

    private void insertHistory(long applicationId, String status, String changedAt) {
        jdbcTemplate.update("""
                INSERT INTO application_status_history (application_id, status, changed_at)
                VALUES (?, ?, ?)
                """, applicationId, status,
                OffsetDateTime.ofInstant(Instant.parse(changedAt), ZoneOffset.UTC));
    }

    private void attachTechnology(long applicationId, long technologyId) {
        jdbcTemplate.update("""
                INSERT INTO job_application_technologies (application_id, technology_id)
                VALUES (?, ?)
                """, applicationId, technologyId);
    }

    private long catalogId(String table, String name) {
        if (!table.equals("job_sources") && !table.equals("technologies")) {
            throw new IllegalArgumentException("Unsupported catalog table");
        }
        return jdbcTemplate.queryForObject(
                "SELECT id FROM " + table + " WHERE name = ?", Long.class, name);
    }

    private JsonNode get(String path) {
        ResponseEntity<JsonNode> response = rest.getForEntity(path, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void assertBadPeriod(String path) {
        ResponseEntity<JsonNode> response = rest.getForEntity(path, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asInt()).isEqualTo(400);
        assertThat(response.getBody().toString())
                .doesNotContainIgnoringCase("sql")
                .doesNotContain("Exception");
    }

    private void assertMetric(JsonNode node, String prefix, long count, String rate) {
        assertThat(node.path(prefix + "Count").asLong()).isEqualTo(count);
        assertThat(node.path(prefix + "Rate").decimalValue()).isEqualByComparingTo(rate);
    }

    private void assertBuckets(JsonNode buckets, List<String> starts, List<Long> expectedCounts) {
        assertThat(buckets).hasSize(starts.size());
        for (int index = 0; index < starts.size(); index++) {
            assertThat(buckets.get(index).path("startDate").asText()).isEqualTo(starts.get(index));
            assertThat(buckets.get(index).path("applicationCount").asLong()).isEqualTo(expectedCounts.get(index));
        }
    }

    private List<String> statuses(JsonNode items) {
        return java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .map(item -> item.path("status").asText())
                .toList();
    }

    private List<String> names(JsonNode items) {
        return java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .map(item -> item.path("name").asText())
                .toList();
    }

    private List<Long> counts(JsonNode items) {
        return java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .map(item -> item.path("count").asLong())
                .toList();
    }

    private record AnalyticsFixture(long bookmarkedId, long javaId, long springId) {
    }
}
