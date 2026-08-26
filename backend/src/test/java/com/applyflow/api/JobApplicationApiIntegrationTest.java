package com.applyflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.applyflow.entity.ApplicationStatus;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("legacy-api-test")
@Import({JobApplicationApiIntegrationTest.ClockTestConfiguration.class, LegacyApiTestSecurityConfiguration.class})
class JobApplicationApiIntegrationTest {

    private static final Instant DEFAULT_TEST_INSTANT = Instant.parse("2026-08-11T12:00:00Z");

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
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock testClock;

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
        jdbcTemplate.update("DELETE FROM companies");
        jdbcTemplate.update("DELETE FROM technologies WHERE lower(name) LIKE lower('TG006 %')");
        testClock.setInstant(DEFAULT_TEST_INSTANT);
    }

    @Test
    void createsExpectedPostgreSqlSchemaContract() {
        Integer identityCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND column_name = 'id'
                  AND is_identity = 'YES'
                  AND table_name IN (
                      'companies', 'job_sources', 'technologies',
                      'job_applications', 'application_status_history'
                  )
                """, Integer.class);

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname IN (
                      'ix_companies_owner_name',
                      'ux_job_sources_name',
                      'ux_technologies_shared_name',
                      'ux_technologies_owner_name',
                      'ix_technologies_owner_name',
                      'ix_job_applications_owner_status_applied_date',
                      'ix_job_applications_owner_source_id',
                      'ix_job_applications_owner_company_id',
                      'ix_job_applications_owner_created_at',
                      'ix_status_history_application_changed_at',
                      'ix_status_history_status_application',
                      'ix_application_technologies_technology_application'
                  )
                ORDER BY indexname
                """, String.class);

        List<String> checks = jdbcTemplate.queryForList("""
                SELECT conname
                FROM pg_constraint
                WHERE connamespace = current_schema()::regnamespace
                  AND contype = 'c'
                  AND conname IN (
                      'ck_companies_company_type',
                      'ck_job_applications_status',
                      'ck_job_applications_work_mode',
                      'ck_job_applications_salary_period',
                      'ck_job_applications_salary_non_negative',
                      'ck_job_applications_salary_range',
                      'ck_job_applications_salary_metadata',
                      'ck_job_applications_notes_length',
                      'ck_status_history_status'
                  )
                ORDER BY conname
                """, String.class);

        List<String> cascadingForeignKeys = jdbcTemplate.queryForList("""
                SELECT conname
                FROM pg_constraint
                WHERE connamespace = current_schema()::regnamespace
                  AND contype = 'f'
                  AND confdeltype = 'c'
                ORDER BY conname
                """, String.class);

        assertThat(identityCount).isEqualTo(5);
        assertThat(indexes).containsExactlyInAnyOrder(
                "ix_companies_owner_name",
                "ux_job_sources_name",
                "ux_technologies_shared_name",
                "ux_technologies_owner_name",
                "ix_technologies_owner_name",
                "ix_job_applications_owner_status_applied_date",
                "ix_job_applications_owner_source_id",
                "ix_job_applications_owner_company_id",
                "ix_job_applications_owner_created_at",
                "ix_status_history_application_changed_at",
                "ix_status_history_status_application",
                "ix_application_technologies_technology_application");
        assertThat(checks).hasSize(9);
        assertThat(cascadingForeignKeys).containsExactly(
                "fk_account_email_outbox_token",
                "fk_account_tokens_user",
                "fk_application_technologies_application",
                "fk_spring_session_attributes",
                "fk_status_history_application");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_sources", Integer.class))
                .isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM technologies", Integer.class))
                .isEqualTo(9);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO companies (owner_id, name, company_type)
                VALUES (1, 'TG006 Invalid Company', 'INVALID')
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_companies_company_type");
    }

    @Test
    void createsNormalizedTechnologyWithLocationAndSortedCatalog() {
        ObjectNode request = objectMapper.createObjectNode().put("name", "  TG006 Kotlin Coroutines  ");

        ResponseEntity<JsonNode> created = rest.postForEntity(
                "/api/technologies", request, JsonNode.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        long technologyId = created.getBody().path("id").asLong();
        assertThat(created.getHeaders().getLocation())
                .hasPath("/api/technologies/" + technologyId);
        assertThat(created.getBody().path("name").asText()).isEqualTo("TG006 Kotlin Coroutines");

        JsonNode catalog = rest.getForObject("/api/technologies", JsonNode.class);
        List<String> names = new ArrayList<>();
        catalog.forEach(item -> names.add(item.path("name").asText()));
        assertThat(names).contains("TG006 Kotlin Coroutines");
        assertThat(names).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
    }

    @Test
    void translatesConcurrentCaseInsensitiveTechnologyCreationToConflict() {
        ObjectNode firstRequest = objectMapper.createObjectNode().put("name", "TG006 Race Technology");
        ObjectNode secondRequest = objectMapper.createObjectNode().put("name", " tg006 race technology ");
        CountDownLatch start = new CountDownLatch(1);
        ResponseEntity<JsonNode> first;
        ResponseEntity<JsonNode> second;

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<ResponseEntity<JsonNode>> firstCall = CompletableFuture.supplyAsync(() -> {
                await(start);
                return rest.postForEntity("/api/technologies", firstRequest, JsonNode.class);
            }, executor);
            CompletableFuture<ResponseEntity<JsonNode>> secondCall = CompletableFuture.supplyAsync(() -> {
                await(start);
                return rest.postForEntity("/api/technologies", secondRequest, JsonNode.class);
            }, executor);
            start.countDown();
            first = firstCall.join();
            second = secondCall.join();
        }

        assertThat(List.of(first.getStatusCode(), second.getStatusCode()))
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        ResponseEntity<JsonNode> conflict = first.getStatusCode() == HttpStatus.CONFLICT ? first : second;
        assertProblem(conflict, HttpStatus.CONFLICT, "Conflict");
        assertThat(count(
                "SELECT COUNT(*) FROM technologies WHERE lower(name) = lower(?)",
                "TG006 Race Technology"))
                .isEqualTo(1);
    }

    @Test
    void searchesCompaniesWithStableLimitDuplicatesAndEmptyResults() {
        jdbcTemplate.update(
                "INSERT INTO companies (owner_id, name, company_type) VALUES (1, ?, 'OTHER')",
                "TG006 A Duplicate");
        jdbcTemplate.update(
                "INSERT INTO companies (owner_id, name, company_type) VALUES (1, ?, 'OTHER')",
                "TG006 A Duplicate");
        for (int index = 0; index < 20; index++) {
            jdbcTemplate.update(
                    "INSERT INTO companies (owner_id, name, company_type) VALUES (1, ?, 'OTHER')",
                    "TG006 B " + String.format("%02d", index));
        }

        JsonNode withoutQuery = rest.getForObject("/api/companies", JsonNode.class);
        JsonNode emptyQuery = rest.getForObject("/api/companies?query=", JsonNode.class);
        assertThat(withoutQuery).hasSize(20);
        assertThat(emptyQuery).isEqualTo(withoutQuery);
        List<String> firstPageNames = new ArrayList<>();
        withoutQuery.forEach(item -> firstPageNames.add(item.path("name").asText()));
        assertThat(firstPageNames).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);

        JsonNode duplicates = rest.getForObject(
                "/api/companies?query=Duplicate", JsonNode.class);
        List<Long> duplicateIds = new ArrayList<>();
        duplicates.forEach(item -> duplicateIds.add(item.path("id").asLong()));
        assertThat(duplicateIds).hasSize(2).doesNotHaveDuplicates();
        assertThat(duplicates).allSatisfy(item ->
                assertThat(item.path("name").asText()).isEqualTo("TG006 A Duplicate"));

        JsonNode missing = rest.getForObject(
                "/api/companies?query=NoMatches", JsonNode.class);
        assertThat(missing).isEmpty();
    }

    @Test
    void rejectsMissingCreateReferencesWithoutPartialRows() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        int initialApplications = count("SELECT COUNT(*) FROM job_applications");
        int initialJoins = count("SELECT COUNT(*) FROM job_application_technologies");

        ObjectNode missingCompany = validCreateRequest(sourceId, technologyId);
        missingCompany.remove("newCompany");
        missingCompany.put("companyId", Long.MAX_VALUE);
        ResponseEntity<JsonNode> companyResponse = rest.postForEntity(
                "/api/applications", missingCompany, JsonNode.class);
        assertProblem(companyResponse, HttpStatus.NOT_FOUND, "Resource not found");

        ObjectNode missingTechnology = validCreateRequest(sourceId, technologyId);
        missingTechnology.withObject("/newCompany").put("name", "TG007 Rolled Back Create Co");
        missingTechnology.set(
                "technologyIds",
                objectMapper.createArrayNode().add(technologyId).add(Long.MAX_VALUE));
        ResponseEntity<JsonNode> technologyResponse = rest.postForEntity(
                "/api/applications", missingTechnology, JsonNode.class);
        assertProblem(technologyResponse, HttpStatus.NOT_FOUND, "Resource not found");

        assertThat(count("SELECT COUNT(*) FROM job_applications")).isEqualTo(initialApplications);
        assertThat(count("SELECT COUNT(*) FROM job_application_technologies")).isEqualTo(initialJoins);
        assertThat(count(
                "SELECT COUNT(*) FROM companies WHERE name = ?",
                "TG007 Rolled Back Create Co"))
                .isZero();
    }

    @Test
    void rollsBackFailedPutAndPreservesOriginalAggregate() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        JsonNode created = createApplication(validCreateRequest(sourceId, technologyId));
        long applicationId = created.path("id").asLong();
        long companyId = created.path("company").path("id").asLong();
        JsonNode before = rest.getForObject("/api/applications/" + applicationId, JsonNode.class);

        ObjectNode missingSource = validUpdateRequest(companyId, sourceId, technologyId);
        missingSource.remove("companyId");
        missingSource.set("newCompany", objectMapper.createObjectNode()
                .put("name", "TG007 Missing Source Co")
                .put("companyType", "OTHER"));
        missingSource.put("sourceId", Long.MAX_VALUE);
        ResponseEntity<JsonNode> sourceResponse = exchangeJson(
                "/api/applications/" + applicationId, HttpMethod.PUT, missingSource);
        assertProblem(sourceResponse, HttpStatus.NOT_FOUND, "Resource not found");
        assertThat(count(
                "SELECT COUNT(*) FROM companies WHERE name = ?",
                "TG007 Missing Source Co"))
                .isZero();

        ObjectNode missingTechnology = validUpdateRequest(companyId, sourceId, technologyId);
        missingTechnology.remove("companyId");
        missingTechnology.set("newCompany", objectMapper.createObjectNode()
                .put("name", "TG007 Missing Technology Co")
                .put("companyType", "OTHER"));
        missingTechnology.set(
                "technologyIds",
                objectMapper.createArrayNode().add(technologyId).add(Long.MAX_VALUE));
        ResponseEntity<JsonNode> technologyResponse = exchangeJson(
                "/api/applications/" + applicationId, HttpMethod.PUT, missingTechnology);
        assertProblem(technologyResponse, HttpStatus.NOT_FOUND, "Resource not found");
        assertThat(count(
                "SELECT COUNT(*) FROM companies WHERE name = ?",
                "TG007 Missing Technology Co"))
                .isZero();

        JsonNode after = rest.getForObject("/api/applications/" + applicationId, JsonNode.class);
        assertThat(after).isEqualTo(before);
        assertThat(count(
                "SELECT COUNT(*) FROM job_application_technologies WHERE application_id = ?",
                applicationId))
                .isEqualTo(1);
    }

    @Test
    void returnsUniformNotFoundForWritesToMissingApplication() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        long missingId = Long.MAX_VALUE;
        ObjectNode update = validUpdateRequest(missingId, sourceId, technologyId);
        ObjectNode status = objectMapper.createObjectNode()
                .put("status", "APPLIED")
                .put("appliedDate", LocalDate.now(testClock).minusDays(1).toString());

        assertProblem(
                exchangeJson("/api/applications/" + missingId, HttpMethod.PUT, update),
                HttpStatus.NOT_FOUND,
                "Resource not found");
        assertProblem(
                exchangeJson("/api/applications/" + missingId + "/status", HttpMethod.PATCH, status),
                HttpStatus.NOT_FOUND,
                "Resource not found");
        ResponseEntity<JsonNode> delete = rest.exchange(
                "/api/applications/" + missingId,
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                JsonNode.class);
        assertProblem(delete, HttpStatus.NOT_FOUND, "Resource not found");

        assertThat(count("SELECT COUNT(*) FROM job_applications")).isZero();
        assertThat(count("SELECT COUNT(*) FROM companies")).isZero();
        assertThat(count("SELECT COUNT(*) FROM job_application_technologies")).isZero();
    }

    @Test
    void roundTripsAllFieldsForExistingCompany() {
        long linkedInId = findCatalogId("/api/sources", "LinkedIn");
        long referralId = findCatalogId("/api/sources", "Referral");
        long javaId = findCatalogId("/api/technologies", "Java");
        long springId = findCatalogId("/api/technologies", "Spring Boot");
        JsonNode companyOwner = createApplication(validCreateRequest(linkedInId, javaId));
        long companyId = companyOwner.path("company").path("id").asLong();

        ObjectNode request = validCreateRequestWithCompany(
                companyId, referralId, javaId, "  Platform Engineer  ");
        request.put("jobUrl", "  https://example.com/jobs/platform  ");
        request.put("status", "APPLIED");
        request.put("appliedDate", LocalDate.now(testClock).minusDays(2).toString());
        request.put("workMode", "ONSITE");
        request.put("location", "  Buenos Aires  ");
        request.put("salaryMin", "1000.00");
        request.put("salaryMax", "2500.00");
        request.put("currency", " usd ");
        request.put("salaryPeriod", "MONTHLY");
        request.put("notes", "  Full round-trip note  ");
        request.set("technologyIds", objectMapper.createArrayNode().add(javaId).add(springId));

        JsonNode created = createApplication(request);
        long applicationId = created.path("id").asLong();
        JsonNode detail = rest.getForObject("/api/applications/" + applicationId, JsonNode.class);

        assertThat(detail.path("company").path("id").asLong()).isEqualTo(companyId);
        assertThat(detail.path("positionTitle").asText()).isEqualTo("Platform Engineer");
        assertThat(detail.path("jobUrl").asText()).isEqualTo("https://example.com/jobs/platform");
        assertThat(detail.path("status").asText()).isEqualTo("APPLIED");
        assertThat(detail.path("appliedDate").asText()).isEqualTo(LocalDate.now(testClock).minusDays(2).toString());
        assertThat(detail.path("source").path("name").asText()).isEqualTo("Referral");
        assertThat(detail.path("workMode").asText()).isEqualTo("ONSITE");
        assertThat(detail.path("location").asText()).isEqualTo("Buenos Aires");
        assertThat(detail.path("salaryMin").asText()).isEqualTo("1000.00");
        assertThat(detail.path("salaryMax").asText()).isEqualTo("2500.00");
        assertThat(detail.path("currency").asText()).isEqualTo("USD");
        assertThat(detail.path("salaryPeriod").asText()).isEqualTo("MONTHLY");
        assertThat(detail.path("notes").asText()).isEqualTo("Full round-trip note");
        assertThat(technologyNames(detail)).containsExactly("Java", "Spring Boot");
        assertThat(detail.path("history")).hasSize(1);
        assertThat(detail.path("history").get(0).path("status").asText()).isEqualTo("APPLIED");
        assertThat(count(
                "SELECT COUNT(*) FROM job_application_technologies WHERE application_id = ?", applicationId))
                .isEqualTo(2);
    }

    @Test
    void putReplacesAllEditableDetailsAndPreservesWorkflow() {
        long linkedInId = findCatalogId("/api/sources", "LinkedIn");
        long referralId = findCatalogId("/api/sources", "Referral");
        long javaId = findCatalogId("/api/technologies", "Java");
        long springId = findCatalogId("/api/technologies", "Spring Boot");
        JsonNode created = createApplication(validCreateRequest(linkedInId, javaId));
        long applicationId = created.path("id").asLong();
        long originalCompanyId = created.path("company").path("id").asLong();
        String originalCompanyName = created.path("company").path("name").asText();

        ObjectNode replacementCompany = objectMapper.createObjectNode()
                .put("name", originalCompanyName)
                .put("website", "https://replacement.example.com")
                .put("companyType", "STARTUP")
                .put("industry", "  Fintech  ");
        ObjectNode update = validUpdateRequest(originalCompanyId, referralId, springId);
        update.remove("companyId");
        update.set("newCompany", replacementCompany);
        update.put("positionTitle", "  Principal Engineer  ");
        update.put("jobUrl", "  ");
        update.put("workMode", "HYBRID");
        update.put("location", "  ");
        update.putNull("salaryMin");
        update.put("salaryMax", "3500.00");
        update.put("currency", " eur ");
        update.put("salaryPeriod", "MONTHLY");
        update.put("notes", "  ");

        ResponseEntity<JsonNode> response = exchangeJson(
                "/api/applications/" + applicationId, HttpMethod.PUT, update);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = rest.getForObject("/api/applications/" + applicationId, JsonNode.class);
        assertThat(detail.path("company").path("id").asLong()).isNotEqualTo(originalCompanyId);
        assertThat(detail.path("company").path("name").asText()).isEqualTo(originalCompanyName);
        assertThat(detail.path("company").path("website").asText())
                .isEqualTo("https://replacement.example.com");
        assertThat(detail.path("company").path("companyType").asText()).isEqualTo("STARTUP");
        assertThat(detail.path("company").path("industry").asText()).isEqualTo("Fintech");
        assertThat(detail.path("positionTitle").asText()).isEqualTo("Principal Engineer");
        assertThat(detail.path("jobUrl").isNull()).isTrue();
        assertThat(detail.path("source").path("name").asText()).isEqualTo("Referral");
        assertThat(detail.path("workMode").asText()).isEqualTo("HYBRID");
        assertThat(detail.path("location").isNull()).isTrue();
        assertThat(detail.path("salaryMin").isNull()).isTrue();
        assertThat(detail.path("salaryMax").asText()).isEqualTo("3500.00");
        assertThat(detail.path("currency").asText()).isEqualTo("EUR");
        assertThat(detail.path("salaryPeriod").asText()).isEqualTo("MONTHLY");
        assertThat(detail.path("notes").isNull()).isTrue();
        assertThat(technologyNames(detail)).containsExactly("Spring Boot");
        assertThat(detail.path("status").asText()).isEqualTo("BOOKMARKED");
        assertThat(detail.path("history")).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM companies WHERE name = ?", originalCompanyName)).isEqualTo(2);
        assertThat(count(
                "SELECT COUNT(*) FROM job_application_technologies WHERE application_id = ?", applicationId))
                .isEqualTo(1);
    }

    @Test
    void supportsCrudFiltersStatusHistoryAndValidation() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");

        ObjectNode createRequest = validCreateRequest(sourceId, technologyId);
        ResponseEntity<JsonNode> created = rest.postForEntity("/api/applications", createRequest, JsonNode.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        assertThat(created.getBody()).isNotNull();
        long applicationId = created.getBody().path("id").asLong();
        assertThat(created.getBody().path("status").asText()).isEqualTo("BOOKMARKED");
        assertThat(created.getBody().path("history")).hasSize(1);

        ObjectNode updateRequest = validUpdateRequest(
                created.getBody().path("company").path("id").asLong(), sourceId, technologyId);
        ResponseEntity<JsonNode> updated = exchangeJson(
                "/api/applications/" + applicationId, HttpMethod.PUT, updateRequest);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().path("positionTitle").asText()).isEqualTo("Senior Backend Engineer");
        assertThat(updated.getBody().path("status").asText()).isEqualTo("BOOKMARKED");
        assertThat(updated.getBody().path("history")).hasSize(1);

        ResponseEntity<JsonNode> page = rest.getForEntity(
                "/api/applications?status=BOOKMARKED&technologyId=" + technologyId
                        + "&companyId=" + created.getBody().path("company").path("id").asLong(),
                JsonNode.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody().path("totalElements").asLong()).isEqualTo(1);

        ObjectNode statusRequest = objectMapper.createObjectNode();
        statusRequest.put("status", "APPLIED");
        statusRequest.put("appliedDate", LocalDate.now(testClock).minusDays(1).toString());
        ResponseEntity<JsonNode> changed = exchangeJson(
                "/api/applications/" + applicationId + "/status", HttpMethod.PATCH, statusRequest);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().path("status").asText()).isEqualTo("APPLIED");
        assertThat(changed.getBody().path("history")).hasSize(2);

        ResponseEntity<JsonNode> repeated = exchangeJson(
                "/api/applications/" + applicationId + "/status", HttpMethod.PATCH, statusRequest);
        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeated.getBody().path("history")).hasSize(2);

        ObjectNode invalid = validCreateRequest(sourceId, technologyId);
        invalid.put("status", "APPLIED");
        invalid.put("appliedDate", LocalDate.now(testClock).minusDays(1).toString());
        invalid.put("salaryMin", "1000.00");
        ResponseEntity<JsonNode> invalidResponse = rest.postForEntity("/api/applications", invalid, JsonNode.class);
        assertProblem(invalidResponse, HttpStatus.BAD_REQUEST, "Invalid request");

        ObjectNode duplicateTechnology = objectMapper.createObjectNode().put("name", " java ");
        ResponseEntity<JsonNode> conflict = rest.postForEntity(
                "/api/technologies", duplicateTechnology, JsonNode.class);
        assertProblem(conflict, HttpStatus.CONFLICT, "Conflict");

        ResponseEntity<Void> deleted = rest.exchange(
                "/api/applications/" + applicationId, HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<JsonNode> missingApplication = rest.getForEntity(
                "/api/applications/" + applicationId, JsonNode.class);
        assertProblem(missingApplication, HttpStatus.NOT_FOUND, "Resource not found");

        ResponseEntity<JsonNode> missingRoute = rest.getForEntity("/api/not-a-route", JsonNode.class);
        assertProblem(missingRoute, HttpStatus.NOT_FOUND, "Resource not found");
    }

    @Test
    void serializesConcurrentStatusChangesAndKeepsHistoryConsistent() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        JsonNode created = rest.postForEntity(
                "/api/applications", validCreateRequest(sourceId, technologyId), JsonNode.class).getBody();
        long applicationId = created.path("id").asLong();
        String appliedDate = LocalDate.now(testClock).minusDays(1).toString();

        ObjectNode applied = objectMapper.createObjectNode()
                .put("status", "APPLIED")
                .put("appliedDate", appliedDate);
        ObjectNode rejected = objectMapper.createObjectNode()
                .put("status", "REJECTED")
                .put("appliedDate", appliedDate);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<ResponseEntity<JsonNode>> first = CompletableFuture.supplyAsync(
                    () -> exchangeJson("/api/applications/" + applicationId + "/status", HttpMethod.PATCH, applied),
                    executor);
            CompletableFuture<ResponseEntity<JsonNode>> second = CompletableFuture.supplyAsync(
                    () -> exchangeJson("/api/applications/" + applicationId + "/status", HttpMethod.PATCH, rejected),
                    executor);
            assertThat(first.join().getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.join().getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        JsonNode detail = rest.getForObject("/api/applications/" + applicationId, JsonNode.class);
        JsonNode history = detail.path("history");
        assertThat(history).hasSize(3);
        assertThat(detail.path("status").asText())
                .isEqualTo(history.get(history.size() - 1).path("status").asText());
    }

    @Test
    void serializesConcurrentPutAndPatchWithoutLosingEitherChange() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        JsonNode created = createApplication(validCreateRequest(sourceId, technologyId));
        long applicationId = created.path("id").asLong();
        long companyId = created.path("company").path("id").asLong();
        String appliedDate = LocalDate.now(testClock).minusDays(2).toString();
        ObjectNode applied = objectMapper.createObjectNode()
                .put("status", "APPLIED")
                .put("appliedDate", appliedDate);
        assertThat(exchangeJson(
                                "/api/applications/" + applicationId + "/status",
                                HttpMethod.PATCH,
                                applied)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ObjectNode update = validUpdateRequest(companyId, sourceId, technologyId);
        update.put("positionTitle", "Concurrent PUT details");
        update.put("location", "Cordoba");
        update.put("appliedDate", appliedDate);
        ObjectNode status = objectMapper.createObjectNode()
                .put("status", "REJECTED")
                .put("appliedDate", appliedDate);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<ResponseEntity<JsonNode>> put = CompletableFuture.supplyAsync(() -> {
                await(start);
                return exchangeJson("/api/applications/" + applicationId, HttpMethod.PUT, update);
            }, executor);
            CompletableFuture<ResponseEntity<JsonNode>> patch = CompletableFuture.supplyAsync(() -> {
                await(start);
                return exchangeJson("/api/applications/" + applicationId + "/status", HttpMethod.PATCH, status);
            }, executor);
            start.countDown();

            assertThat(put.join().getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(patch.join().getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        JsonNode detail = rest.getForObject("/api/applications/" + applicationId, JsonNode.class);
        assertThat(detail.path("positionTitle").asText()).isEqualTo("Concurrent PUT details");
        assertThat(detail.path("location").asText()).isEqualTo("Cordoba");
        assertThat(detail.path("status").asText()).isEqualTo("REJECTED");
        assertThat(detail.path("history")).hasSize(3);
        assertThat(detail.path("history").get(2).path("status").asText()).isEqualTo("REJECTED");
    }

    @Test
    void serializesConcurrentPutsAsCompletePayloads() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        JsonNode created = createApplication(validCreateRequest(sourceId, technologyId));
        long applicationId = created.path("id").asLong();
        long companyId = created.path("company").path("id").asLong();
        ObjectNode firstUpdate = validUpdateRequest(companyId, sourceId, technologyId);
        firstUpdate.put("positionTitle", "Concurrent payload A");
        firstUpdate.put("jobUrl", "https://example.com/jobs/a");
        firstUpdate.put("workMode", "REMOTE");
        firstUpdate.put("location", "Location A");
        firstUpdate.put("notes", "Notes A");
        ObjectNode secondUpdate = validUpdateRequest(companyId, sourceId, technologyId);
        secondUpdate.put("positionTitle", "Concurrent payload B");
        secondUpdate.put("jobUrl", "https://example.com/jobs/b");
        secondUpdate.put("workMode", "ONSITE");
        secondUpdate.put("location", "Location B");
        secondUpdate.put("notes", "Notes B");
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<ResponseEntity<JsonNode>> first = CompletableFuture.supplyAsync(() -> {
                await(start);
                return exchangeJson("/api/applications/" + applicationId, HttpMethod.PUT, firstUpdate);
            }, executor);
            CompletableFuture<ResponseEntity<JsonNode>> second = CompletableFuture.supplyAsync(() -> {
                await(start);
                return exchangeJson("/api/applications/" + applicationId, HttpMethod.PUT, secondUpdate);
            }, executor);
            start.countDown();

            assertThat(first.join().getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.join().getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        JsonNode detail = rest.getForObject("/api/applications/" + applicationId, JsonNode.class);
        List<String> finalPayload = List.of(
                detail.path("positionTitle").asText(),
                detail.path("jobUrl").asText(),
                detail.path("workMode").asText(),
                detail.path("location").asText(),
                detail.path("notes").asText());
        assertThat(finalPayload).isIn(
                List.of("Concurrent payload A", "https://example.com/jobs/a", "REMOTE", "Location A", "Notes A"),
                List.of("Concurrent payload B", "https://example.com/jobs/b", "ONSITE", "Location B", "Notes B"));
        assertThat(detail.path("status").asText()).isEqualTo("BOOKMARKED");
        assertThat(detail.path("history")).hasSize(1);
    }

    @Test
    void serializesConcurrentDeleteAndPatchWithoutOrphans() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        JsonNode created = createApplication(validCreateRequest(sourceId, technologyId));
        long applicationId = created.path("id").asLong();
        ObjectNode status = objectMapper.createObjectNode()
                .put("status", "APPLIED")
                .put("appliedDate", LocalDate.now(testClock).minusDays(1).toString());
        CountDownLatch start = new CountDownLatch(1);
        ResponseEntity<Void> deleteResult;
        ResponseEntity<JsonNode> patchResult;

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<ResponseEntity<Void>> delete = CompletableFuture.supplyAsync(() -> {
                await(start);
                return rest.exchange(
                        "/api/applications/" + applicationId, HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
            }, executor);
            CompletableFuture<ResponseEntity<JsonNode>> patch = CompletableFuture.supplyAsync(() -> {
                await(start);
                return exchangeJson("/api/applications/" + applicationId + "/status", HttpMethod.PATCH, status);
            }, executor);
            start.countDown();
            deleteResult = delete.join();
            patchResult = patch.join();
        }

        assertThat(deleteResult.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(patchResult.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity(
                "/api/applications/" + applicationId, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(count("SELECT COUNT(*) FROM job_applications WHERE id = ?", applicationId)).isZero();
        assertThat(count(
                "SELECT COUNT(*) FROM application_status_history WHERE application_id = ?", applicationId))
                .isZero();
        assertThat(count(
                "SELECT COUNT(*) FROM job_application_technologies WHERE application_id = ?", applicationId))
                .isZero();
    }

    @Test
    void rollsBackNewCompanyAndStatusWhenLaterWritesFail() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        String companyName = "ApplyFlow Rollback Co";

        ObjectNode invalidReference = validCreateRequest(sourceId, technologyId);
        invalidReference.withObject("/newCompany").put("name", companyName);
        invalidReference.put("sourceId", Long.MAX_VALUE);
        ResponseEntity<JsonNode> rejected = rest.postForEntity(
                "/api/applications", invalidReference, JsonNode.class);

        assertProblem(rejected, HttpStatus.NOT_FOUND, "Resource not found");
        assertThat(count("SELECT COUNT(*) FROM companies WHERE name = ?", companyName)).isZero();

        JsonNode created = createApplication(validCreateRequest(sourceId, technologyId));
        long applicationId = created.path("id").asLong();
        String triggerName = "trg_applyflow_test_reject_history";
        String triggerFunctionName = "applyflow_test_reject_history";
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON application_status_history");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + triggerFunctionName + "()");
        jdbcTemplate.execute("""
                CREATE FUNCTION applyflow_test_reject_history()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    RAISE EXCEPTION 'Intentional history failure';
                END;
                $function$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_applyflow_test_reject_history
                AFTER INSERT ON application_status_history
                FOR EACH ROW
                EXECUTE FUNCTION applyflow_test_reject_history()
                """);
        try {
            ObjectNode statusRequest = objectMapper.createObjectNode()
                    .put("status", "APPLIED")
                    .put("appliedDate", LocalDate.now(testClock).minusDays(1).toString());
            ResponseEntity<JsonNode> failedStatus = exchangeJson(
                    "/api/applications/" + applicationId + "/status", HttpMethod.PATCH, statusRequest);

            assertProblem(failedStatus, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
            assertThat(failedStatus.getBody().toString().toLowerCase())
                    .doesNotContain("intentional history failure")
                    .doesNotContain(triggerName.toLowerCase())
                    .doesNotContain("sql")
                    .doesNotContain("exception")
                    .doesNotContain("stack");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM job_applications WHERE id = ?", String.class, applicationId))
                    .isEqualTo("BOOKMARKED");
            assertThat(count(
                    "SELECT COUNT(*) FROM application_status_history WHERE application_id = ?", applicationId))
                    .isEqualTo(1);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON application_status_history");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + triggerFunctionName + "()");
            deleteApplication(applicationId);
        }
    }

    @Test
    void deleteCascadesOnlyAggregateRowsAndPreservesCatalogs() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        JsonNode created = createApplication(validCreateRequest(sourceId, technologyId));
        long applicationId = created.path("id").asLong();
        long companyId = created.path("company").path("id").asLong();

        assertThat(count(
                "SELECT COUNT(*) FROM application_status_history WHERE application_id = ?", applicationId))
                .isEqualTo(1);
        assertThat(count(
                "SELECT COUNT(*) FROM job_application_technologies WHERE application_id = ?", applicationId))
                .isEqualTo(1);

        deleteApplication(applicationId);

        assertThat(count("SELECT COUNT(*) FROM job_applications WHERE id = ?", applicationId)).isZero();
        assertThat(count(
                "SELECT COUNT(*) FROM application_status_history WHERE application_id = ?", applicationId))
                .isZero();
        assertThat(count(
                "SELECT COUNT(*) FROM job_application_technologies WHERE application_id = ?", applicationId))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM companies WHERE id = ?", companyId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM job_sources WHERE id = ?", sourceId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM technologies WHERE id = ?", technologyId)).isEqualTo(1);
    }

    @Test
    void paginatesDeterministicallyAcrossTiedSortValuesAndCombinedFilters() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        List<Long> expectedIds = new ArrayList<>();

        JsonNode first = createApplication(validCreateRequest(sourceId, technologyId));
        long companyId = first.path("company").path("id").asLong();
        expectedIds.add(first.path("id").asLong());
        for (int index = 1; index < 5; index++) {
            JsonNode created = createApplication(validCreateRequestWithCompany(
                    companyId, sourceId, technologyId, "Tied Position"));
            expectedIds.add(created.path("id").asLong());
        }

        List<Long> observedIds = new ArrayList<>();
        for (int pageNumber = 0; pageNumber < 3; pageNumber++) {
            ResponseEntity<JsonNode> response = rest.getForEntity(
                    "/api/applications?companyId=" + companyId
                            + "&sourceId=" + sourceId
                            + "&technologyId=" + technologyId
                            + "&status=BOOKMARKED&sortBy=status&direction=DESC&size=2&page=" + pageNumber,
                    JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().path("totalElements").asLong()).isEqualTo(5);
            assertThat(response.getBody().path("totalPages").asInt()).isEqualTo(3);
            response.getBody().path("items").forEach(item -> observedIds.add(item.path("id").asLong()));
        }

        assertThat(observedIds).containsExactlyElementsOf(expectedIds.stream().sorted().toList());
        expectedIds.forEach(this::deleteApplication);
    }

    @Test
    void combinesIndependentFiltersWithStatusOrWithoutDuplicates() {
        long linkedInId = findCatalogId("/api/sources", "LinkedIn");
        long referralId = findCatalogId("/api/sources", "Referral");
        long javaId = findCatalogId("/api/technologies", "Java");
        long springId = findCatalogId("/api/technologies", "Spring Boot");

        ObjectNode firstRequest = validCreateRequest(linkedInId, javaId);
        firstRequest.put("positionTitle", "Filter Alpha");
        firstRequest.set("technologyIds", objectMapper.createArrayNode().add(javaId).add(springId));
        JsonNode first = createApplication(firstRequest);
        long firstId = first.path("id").asLong();
        long sharedCompanyId = first.path("company").path("id").asLong();

        ObjectNode secondRequest = validCreateRequestWithCompany(
                sharedCompanyId, referralId, springId, "Filter Beta");
        secondRequest.put("status", "APPLIED");
        secondRequest.put("appliedDate", LocalDate.now(testClock).minusDays(2).toString());
        long secondId = createApplication(secondRequest).path("id").asLong();

        ObjectNode thirdRequest = validCreateRequest(linkedInId, javaId);
        thirdRequest.withObject("/newCompany").put("name", "Independent Filter Co");
        thirdRequest.put("positionTitle", "Filter Gamma");
        thirdRequest.put("status", "REJECTED");
        thirdRequest.put("appliedDate", LocalDate.now(testClock).minusDays(1).toString());
        long thirdId = createApplication(thirdRequest).path("id").asLong();

        assertPageIds("/api/applications?companyId=" + sharedCompanyId, firstId, secondId);
        assertPageIds("/api/applications?sourceId=" + linkedInId, firstId, thirdId);
        assertPageIds("/api/applications?technologyId=" + springId, firstId, secondId);
        assertPageIds(
                "/api/applications?status=APPLIED&status=REJECTED",
                secondId,
                thirdId);
        assertPageIds(
                "/api/applications?companyId=" + sharedCompanyId
                        + "&sourceId=" + referralId
                        + "&technologyId=" + springId
                        + "&status=APPLIED&status=REJECTED",
                secondId);
    }

    @Test
    void appliesListDefaultsAndReturnsCoherentEmptyPage() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        List<Long> ids = new ArrayList<>();
        for (String title : List.of("Default Alpha", "Default Beta", "Default Gamma")) {
            ObjectNode request = validCreateRequest(sourceId, technologyId);
            request.withObject("/newCompany").put("name", title + " Co");
            request.put("positionTitle", title);
            ids.add(createApplication(request).path("id").asLong());
        }
        ids.forEach(id -> jdbcTemplate.update(
                "UPDATE job_applications SET created_at = CAST('2026-01-01T00:00:00.123Z' AS TIMESTAMP(3) WITH TIME ZONE) WHERE id = ?",
                id));

        ResponseEntity<JsonNode> defaults = rest.getForEntity("/api/applications", JsonNode.class);
        assertThat(defaults.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(defaults.getBody()).isNotNull();
        assertThat(defaults.getBody().path("page").asInt()).isZero();
        assertThat(defaults.getBody().path("size").asInt()).isEqualTo(20);
        assertThat(defaults.getBody().path("totalElements").asLong()).isEqualTo(3);
        assertThat(defaults.getBody().path("totalPages").asInt()).isEqualTo(1);
        assertThat(pageIds(defaults.getBody())).containsExactlyElementsOf(ids.stream().sorted().toList());

        ResponseEntity<JsonNode> beyondTotal = rest.getForEntity(
                "/api/applications?page=2&size=2", JsonNode.class);
        assertThat(beyondTotal.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(beyondTotal.getBody().path("items")).isEmpty();
        assertThat(beyondTotal.getBody().path("page").asInt()).isEqualTo(2);
        assertThat(beyondTotal.getBody().path("size").asInt()).isEqualTo(2);
        assertThat(beyondTotal.getBody().path("totalElements").asLong()).isEqualTo(3);
        assertThat(beyondTotal.getBody().path("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    void supportsAllowlistedSortsAndRejectsInvalidListParameters() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        ObjectNode firstRequest = validCreateRequest(sourceId, technologyId);
        firstRequest.withObject("/newCompany").put("name", "Sort Alpha Co");
        firstRequest.put("positionTitle", "Alpha");
        firstRequest.put("status", "APPLIED");
        firstRequest.put("appliedDate", "2026-01-01");
        long firstId = createApplication(firstRequest).path("id").asLong();

        ObjectNode secondRequest = validCreateRequest(sourceId, technologyId);
        secondRequest.withObject("/newCompany").put("name", "Sort Beta Co");
        secondRequest.put("positionTitle", "Beta");
        secondRequest.put("status", "REJECTED");
        secondRequest.put("appliedDate", "2026-01-02");
        long secondId = createApplication(secondRequest).path("id").asLong();

        jdbcTemplate.update(
                "UPDATE job_applications SET created_at = CAST('2026-01-01T00:00:00Z' AS TIMESTAMP(3) WITH TIME ZONE), "
                        + "updated_at = CAST('2026-02-01T00:00:00Z' AS TIMESTAMP(3) WITH TIME ZONE) WHERE id = ?",
                firstId);
        jdbcTemplate.update(
                "UPDATE job_applications SET created_at = CAST('2026-01-02T00:00:00Z' AS TIMESTAMP(3) WITH TIME ZONE), "
                        + "updated_at = CAST('2026-02-02T00:00:00Z' AS TIMESTAMP(3) WITH TIME ZONE) WHERE id = ?",
                secondId);

        for (String sortBy : List.of("createdAt", "updatedAt", "appliedDate", "positionTitle", "status")) {
            assertOrderedPageIds(
                    "/api/applications?sortBy=" + sortBy + "&direction=ASC",
                    firstId,
                    secondId);
            assertOrderedPageIds(
                    "/api/applications?sortBy=" + sortBy + "&direction=DESC",
                    secondId,
                    firstId);
        }

        for (String path : List.of(
                "/api/applications?page=-1",
                "/api/applications?size=0",
                "/api/applications?size=101",
                "/api/applications?sourceId=0",
                "/api/applications?companyId=-1",
                "/api/applications?technologyId=0",
                "/api/applications?sortBy=notAllowed")) {
            assertProblem(rest.getForEntity(path, JsonNode.class), HttpStatus.BAD_REQUEST, "Invalid request");
        }
        assertProblem(
                rest.getForEntity("/api/applications?direction=SIDEWAYS", JsonNode.class),
                HttpStatus.BAD_REQUEST,
                "Malformed request");
    }

    @Test
    void preservesAppliedDateRulesAcrossPutAndIdempotentPatch() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        JsonNode created = createApplication(validCreateRequest(sourceId, technologyId));
        long applicationId = created.path("id").asLong();
        long companyId = created.path("company").path("id").asLong();
        String originalDate = LocalDate.now(testClock).minusDays(2).toString();
        String correctedDate = LocalDate.now(testClock).minusDays(1).toString();

        ObjectNode applied = objectMapper.createObjectNode()
                .put("status", "APPLIED")
                .put("appliedDate", originalDate);
        assertThat(exchangeJson(
                "/api/applications/" + applicationId + "/status", HttpMethod.PATCH, applied).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ObjectNode update = validUpdateRequest(companyId, sourceId, technologyId);
        update.putNull("appliedDate");
        ResponseEntity<JsonNode> removal = exchangeJson(
                "/api/applications/" + applicationId, HttpMethod.PUT, update);
        assertThat(removal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        update.put("appliedDate", correctedDate);
        ResponseEntity<JsonNode> corrected = exchangeJson(
                "/api/applications/" + applicationId, HttpMethod.PUT, update);
        assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(corrected.getBody().path("status").asText()).isEqualTo("APPLIED");
        assertThat(corrected.getBody().path("appliedDate").asText()).isEqualTo(correctedDate);
        assertThat(corrected.getBody().path("history")).hasSize(2);

        ResponseEntity<JsonNode> repeated = exchangeJson(
                "/api/applications/" + applicationId + "/status", HttpMethod.PATCH, applied);
        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeated.getBody().path("appliedDate").asText()).isEqualTo(correctedDate);
        assertThat(repeated.getBody().path("history")).hasSize(2);
        deleteApplication(applicationId);
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = {
            "RESPONSE_RECEIVED", "HR_INTERVIEW", "TECHNICAL_INTERVIEW", "FINAL_INTERVIEW", "OFFER"
    })
    void rejectsPutThatMovesAppliedDateAfterAnyRecordedResponse(ApplicationStatus responseStatus) {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        testClock.setInstant(Instant.parse("2026-08-01T09:00:00Z"));

        ObjectNode createRequest = validCreateRequest(sourceId, technologyId);
        createRequest.put("status", "APPLIED");
        createRequest.put("appliedDate", "2026-08-01");
        JsonNode created = createApplication(createRequest);
        long applicationId = created.path("id").asLong();

        testClock.setInstant(Instant.parse("2026-08-05T09:00:00Z"));
        ObjectNode responseStatusRequest = objectMapper.createObjectNode().put("status", responseStatus.name());
        ResponseEntity<JsonNode> responded = exchangeJson(
                "/api/applications/" + applicationId + "/status", HttpMethod.PATCH, responseStatusRequest);
        assertThat(responded.getStatusCode()).isEqualTo(HttpStatus.OK);

        testClock.setInstant(DEFAULT_TEST_INSTANT);
        ObjectNode update = validUpdateRequest(
                created.path("company").path("id").asLong(), sourceId, technologyId);
        update.put("appliedDate", "2026-08-06");
        ResponseEntity<JsonNode> rejected = exchangeJson(
                "/api/applications/" + applicationId, HttpMethod.PUT, update);

        assertProblem(rejected, HttpStatus.BAD_REQUEST, "Invalid request");
        JsonNode persisted = rest.getForObject("/api/applications/" + applicationId, JsonNode.class);
        assertThat(persisted.path("appliedDate").asText()).isEqualTo("2026-08-01");
        assertThat(persisted.path("history")).hasSize(2);
        assertThat(persisted.path("history").get(1).path("status").asText()).isEqualTo(responseStatus.name());
        assertThat(persisted.path("history").get(1).path("changedAt").asText())
                .isEqualTo("2026-08-05T09:00:00Z");
    }

    @Test
    void firstResponseGovernsAppliedDateWhileSameUtcDayRemainsAllowed() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        testClock.setInstant(Instant.parse("2026-08-01T09:00:00Z"));

        ObjectNode createRequest = validCreateRequest(sourceId, technologyId);
        createRequest.put("status", "APPLIED");
        createRequest.put("appliedDate", "2026-08-01");
        JsonNode created = createApplication(createRequest);
        long applicationId = created.path("id").asLong();

        testClock.setInstant(Instant.parse("2026-08-05T09:00:00Z"));
        ResponseEntity<JsonNode> firstResponse = exchangeJson(
                "/api/applications/" + applicationId + "/status",
                HttpMethod.PATCH,
                objectMapper.createObjectNode().put("status", "HR_INTERVIEW"));
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        testClock.setInstant(Instant.parse("2026-08-07T09:00:00Z"));
        ResponseEntity<JsonNode> laterResponse = exchangeJson(
                "/api/applications/" + applicationId + "/status",
                HttpMethod.PATCH,
                objectMapper.createObjectNode().put("status", "OFFER"));
        assertThat(laterResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        testClock.setInstant(DEFAULT_TEST_INSTANT);
        ObjectNode update = validUpdateRequest(
                created.path("company").path("id").asLong(), sourceId, technologyId);
        update.put("appliedDate", "2026-08-06");
        assertProblem(
                exchangeJson("/api/applications/" + applicationId, HttpMethod.PUT, update),
                HttpStatus.BAD_REQUEST,
                "Invalid request");

        update.put("appliedDate", "2026-08-05");
        ResponseEntity<JsonNode> sameDay = exchangeJson(
                "/api/applications/" + applicationId, HttpMethod.PUT, update);
        assertThat(sameDay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sameDay.getBody().path("appliedDate").asText()).isEqualTo("2026-08-05");
        assertThat(sameDay.getBody().path("history")).hasSize(3);
        assertThat(sameDay.getBody().path("history").get(1).path("changedAt").asText())
                .isEqualTo("2026-08-05T09:00:00Z");
        assertThat(sameDay.getBody().path("history").get(2).path("changedAt").asText())
                .isEqualTo("2026-08-07T09:00:00Z");
    }

    @Test
    void recordsHistoryFromInjectedClockAndKeepsNoOpTimestampUnchanged() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        Instant createInstant = Instant.parse("2026-08-11T23:59:59.123Z");
        testClock.setInstant(createInstant);

        JsonNode created = createApplication(validCreateRequest(sourceId, technologyId));
        long applicationId = created.path("id").asLong();
        assertThat(created.path("history")).hasSize(1);
        assertThat(created.path("history").get(0).path("changedAt").asText())
                .isEqualTo(createInstant.toString());

        Instant statusInstant = Instant.parse("2026-08-12T00:00:00.456Z");
        testClock.setInstant(statusInstant);
        ObjectNode applied = objectMapper.createObjectNode()
                .put("status", "APPLIED")
                .put("appliedDate", "2026-08-12");
        ResponseEntity<JsonNode> changed = exchangeJson(
                "/api/applications/" + applicationId + "/status",
                HttpMethod.PATCH,
                applied);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().path("history")).hasSize(2);
        assertThat(changed.getBody().path("history").get(1).path("changedAt").asText())
                .isEqualTo(statusInstant.toString());
        JsonNode historyBeforeNoOp = changed.getBody().path("history").deepCopy();

        testClock.setInstant(Instant.parse("2026-08-13T09:30:00.789Z"));
        ResponseEntity<JsonNode> repeated = exchangeJson(
                "/api/applications/" + applicationId + "/status",
                HttpMethod.PATCH,
                applied);
        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeated.getBody().path("history")).isEqualTo(historyBeforeNoOp);
        assertThat(repeated.getBody().path("history").get(1).path("changedAt").asText())
                .isEqualTo(statusInstant.toString());
    }

    @Test
    void ordersHistoryByIdentityWhenTimestampsTie() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        JsonNode created = createApplication(validCreateRequest(sourceId, technologyId));
        long applicationId = created.path("id").asLong();

        String insert = """
                INSERT INTO application_status_history (application_id, status, changed_at)
                VALUES (?, ?, CAST('2026-01-01T00:00:00.123Z' AS TIMESTAMP(3) WITH TIME ZONE))
                """;
        jdbcTemplate.update(insert, applicationId, "APPLIED");
        long firstId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM application_status_history WHERE application_id = ?", Long.class, applicationId);
        jdbcTemplate.update(insert, applicationId, "REJECTED");
        long secondId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM application_status_history WHERE application_id = ?", Long.class, applicationId);

        JsonNode detail = rest.getForObject("/api/applications/" + applicationId, JsonNode.class);
        List<Long> historyIds = new ArrayList<>();
        detail.path("history").forEach(item -> historyIds.add(item.path("id").asLong()));
        assertThat(historyIds.indexOf(firstId)).isLessThan(historyIds.indexOf(secondId));
        deleteApplication(applicationId);
    }

    @Test
    void returnsProblemDetailsForFrameworkAndPayloadErrors() {
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        jsonHeaders.setAccept(List.of(MediaType.APPLICATION_PROBLEM_JSON));
        ResponseEntity<JsonNode> methodNotAllowed = rest.exchange(
                "/api/sources", HttpMethod.POST,
                new HttpEntity<>(objectMapper.createObjectNode(), jsonHeaders), JsonNode.class);
        assertProblem(methodNotAllowed, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");

        HttpHeaders textHeaders = new HttpHeaders();
        textHeaders.setContentType(MediaType.TEXT_PLAIN);
        textHeaders.setAccept(List.of(MediaType.APPLICATION_PROBLEM_JSON));
        ResponseEntity<JsonNode> unsupportedMedia = rest.exchange(
                "/api/technologies", HttpMethod.POST,
                new HttpEntity<>("Java", textHeaders), JsonNode.class);
        assertProblem(unsupportedMedia, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type");

        HttpHeaders xmlHeaders = new HttpHeaders();
        xmlHeaders.setAccept(List.of(MediaType.APPLICATION_XML));
        ResponseEntity<JsonNode> notAcceptable = rest.exchange(
                "/api/sources", HttpMethod.GET, new HttpEntity<>(xmlHeaders), JsonNode.class);
        assertProblem(notAcceptable, HttpStatus.NOT_ACCEPTABLE, "Not acceptable");

        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        ObjectNode invalidEnum = validCreateRequest(sourceId, technologyId);
        invalidEnum.put("status", "NOT_A_STATUS");
        ResponseEntity<JsonNode> malformed = rest.postForEntity(
                "/api/applications", invalidEnum, JsonNode.class);
        assertProblem(malformed, HttpStatus.BAD_REQUEST, "Malformed request");

        ObjectNode unknownProperty = validCreateRequest(sourceId, technologyId);
        unknownProperty.put("unexpected", true);
        ResponseEntity<JsonNode> unknown = rest.postForEntity(
                "/api/applications", unknownProperty, JsonNode.class);
        assertProblem(unknown, HttpStatus.BAD_REQUEST, "Malformed request");

        ResponseEntity<JsonNode> brokenJson = rest.exchange(
                "/api/applications",
                HttpMethod.POST,
                new HttpEntity<>("{\"positionTitle\":", jsonHeaders),
                JsonNode.class);
        assertProblem(brokenJson, HttpStatus.BAD_REQUEST, "Malformed request");

        ResponseEntity<JsonNode> pathTypeMismatch = rest.getForEntity(
                "/api/applications/not-a-number", JsonNode.class);
        assertProblem(pathTypeMismatch, HttpStatus.BAD_REQUEST, "Malformed request");

        ResponseEntity<JsonNode> directionMismatch = rest.getForEntity(
                "/api/applications?direction=SIDEWAYS", JsonNode.class);
        assertProblem(directionMismatch, HttpStatus.BAD_REQUEST, "Malformed request");

        ResponseEntity<JsonNode> statusMismatch = rest.getForEntity(
                "/api/applications?status=NOT_A_STATUS", JsonNode.class);
        assertProblem(statusMismatch, HttpStatus.BAD_REQUEST, "Malformed request");

        ObjectNode missingRequired = validCreateRequest(sourceId, technologyId);
        missingRequired.remove("positionTitle");
        ResponseEntity<JsonNode> validation = rest.postForEntity(
                "/api/applications", missingRequired, JsonNode.class);
        assertProblem(validation, HttpStatus.BAD_REQUEST, "Validation failed");
        assertThat(validation.getBody().path("errors").has("positionTitle")).isTrue();
    }

    @Test
    void enforcesUnicodeAndCollectionRequestBoundsWithoutTruncation() {
        long sourceId = findCatalogId("/api/sources", "LinkedIn");
        long technologyId = findCatalogId("/api/technologies", "Java");
        String atLimit = "🚀".repeat(5000);
        ObjectNode accepted = validCreateRequest(sourceId, technologyId).put("notes", atLimit);
        JsonNode created = createApplication(accepted);
        assertThat(created.path("notes").asText()).isEqualTo(atLimit);

        ObjectNode oversized = validCreateRequest(sourceId, technologyId).put("notes", atLimit + "🚀");
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/applications", oversized, JsonNode.class);
        assertProblem(response, HttpStatus.BAD_REQUEST, "Validation failed");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().path("errors").has("notes")).isTrue();

        ArrayNode technologies = objectMapper.createArrayNode();
        for (int index = 0; index < 51; index++) technologies.add(technologyId);
        oversized = validCreateRequest(sourceId, technologyId).set("technologyIds", technologies);
        response = rest.postForEntity("/api/applications", oversized, JsonNode.class);
        assertProblem(response, HttpStatus.BAD_REQUEST, "Validation failed");
        assertThat(response.getBody().path("errors").has("technologyIds")).isTrue();

        ResponseEntity<JsonNode> companyQuery = rest.getForEntity(
                "/api/companies?query=" + "a".repeat(161), JsonNode.class);
        assertProblem(companyQuery, HttpStatus.BAD_REQUEST, "Validation failed");
        assertThat(companyQuery.getBody().path("errors").has("query")).isTrue();

        String repeatedStatuses = String.join("&", Collections.nCopies(10, "status=BOOKMARKED"));
        ResponseEntity<JsonNode> statuses = rest.getForEntity(
                "/api/applications?" + repeatedStatuses, JsonNode.class);
        assertProblem(statuses, HttpStatus.BAD_REQUEST, "Validation failed");
        assertThat(statuses.getBody().path("errors").has("status")).isTrue();
    }

    private long findCatalogId(String path, String name) {
        JsonNode items = rest.getForObject(path, JsonNode.class);
        for (JsonNode item : items) {
            if (name.equals(item.path("name").asText())) {
                return item.path("id").asLong();
            }
        }
        throw new AssertionError("Catalog item not found: " + name);
    }

    private List<String> technologyNames(JsonNode application) {
        List<String> names = new ArrayList<>();
        application.path("technologies").forEach(item -> names.add(item.path("name").asText()));
        return names;
    }

    private void assertPageIds(String path, long... expectedIds) {
        ResponseEntity<JsonNode> response = rest.getForEntity(path, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        List<Long> actualIds = pageIds(response.getBody());
        assertThat(actualIds).doesNotHaveDuplicates();
        assertThat(response.getBody().path("totalElements").asLong()).isEqualTo(expectedIds.length);
        assertThat(actualIds).containsExactlyInAnyOrder(
                java.util.Arrays.stream(expectedIds).boxed().toArray(Long[]::new));
    }

    private void assertOrderedPageIds(String path, long... expectedIds) {
        ResponseEntity<JsonNode> response = rest.getForEntity(path, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(pageIds(response.getBody())).containsExactly(
                java.util.Arrays.stream(expectedIds).boxed().toArray(Long[]::new));
    }

    private List<Long> pageIds(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private void assertProblem(
            ResponseEntity<JsonNode> response,
            HttpStatus expectedStatus,
            String expectedTitle
    ) {
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asInt()).isEqualTo(expectedStatus.value());
        assertThat(response.getBody().path("title").asText()).isEqualTo(expectedTitle);
        assertThat(response.getBody().path("detail").asText()).isNotBlank();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent requests did not receive the start signal");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating concurrent requests", exception);
        }
    }

    private ObjectNode validCreateRequest(long sourceId, long technologyId) {
        ObjectNode company = objectMapper.createObjectNode();
        company.put("name", "ApplyFlow Integration Co");
        company.put("website", "https://example.com");
        company.put("companyType", "OTHER");

        ArrayNode technologies = objectMapper.createArrayNode().add(technologyId);
        ObjectNode request = objectMapper.createObjectNode();
        request.set("newCompany", company);
        request.put("positionTitle", "Backend Engineer");
        request.put("jobUrl", "https://example.com/jobs/backend");
        request.put("status", "BOOKMARKED");
        request.put("sourceId", sourceId);
        request.put("workMode", "REMOTE");
        request.set("technologyIds", technologies);
        return request;
    }

    private ObjectNode validUpdateRequest(long companyId, long sourceId, long technologyId) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("companyId", companyId);
        request.put("positionTitle", "Senior Backend Engineer");
        request.put("jobUrl", "https://example.com/jobs/backend-senior");
        request.put("sourceId", sourceId);
        request.put("workMode", "HYBRID");
        request.set("technologyIds", objectMapper.createArrayNode().add(technologyId));
        return request;
    }

    private ObjectNode validCreateRequestWithCompany(
            long companyId,
            long sourceId,
            long technologyId,
            String positionTitle
    ) {
        ObjectNode request = validCreateRequest(sourceId, technologyId);
        request.remove("newCompany");
        request.put("companyId", companyId);
        request.put("positionTitle", positionTitle);
        return request;
    }

    private JsonNode createApplication(ObjectNode request) {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/applications", request, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void deleteApplication(long applicationId) {
        ResponseEntity<Void> response = rest.exchange(
                "/api/applications/" + applicationId, HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private int count(String sql, Object... arguments) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return result == null ? 0 : result;
    }

    private ResponseEntity<JsonNode> exchangeJson(String path, HttpMethod method, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockTestConfiguration {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(DEFAULT_TEST_INSTANT);
        }
    }

    static final class MutableClock extends Clock {

        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
