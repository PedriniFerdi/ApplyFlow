package com.applyflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.Serial;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.applyflow.security.AuthenticatedUser;
import com.applyflow.service.AccountExportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OwnershipIsolationIntegrationTest {

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
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private AccountExportService exports;

    @Autowired
    private ObjectMapper mapper;

    private Long userA;
    private Long userB;
    private Long applicationA;
    private Long applicationB;
    private Long companyB;
    private Long privateTechnologyB;
    private Long sourceId;

    @BeforeEach
    void createIsolatedFixture() {
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM job_applications");
        jdbcTemplate.update("DELETE FROM companies");
        jdbcTemplate.update("DELETE FROM technologies WHERE owner_id IS NOT NULL");
        jdbcTemplate.update("DELETE FROM account_email_outbox");
        jdbcTemplate.update("DELETE FROM account_tokens");
        jdbcTemplate.update("DELETE FROM auth_rate_limits");
        jdbcTemplate.update("DELETE FROM users");

        userA = insertUser("user-a@example.com", "User A");
        userB = insertUser("user-b@example.com", "User B");
        Long companyA = insertCompany(userA, "Company A");
        companyB = insertCompany(userB, "Company B");
        privateTechnologyB = jdbcTemplate.queryForObject(
                "INSERT INTO technologies (owner_id, name) VALUES (?, 'Private B') RETURNING id",
                Long.class,
                userB);
        sourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM job_sources WHERE name = 'LinkedIn'", Long.class);
        applicationA = insertApplication(userA, companyA, sourceId, "Application A");
        applicationB = insertApplication(userB, companyB, sourceId, "Application B");
    }

    @Test
    void isolatesListsDetailsMutationsCatalogsAndAnalyticsAcrossUsers() throws Exception {
        mockMvc.perform(get("/api/applications").with(authentication(as(userA, "user-a@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(applicationA));

        mockMvc.perform(get("/api/applications/{id}", applicationB)
                        .with(authentication(as(userA, "user-a@example.com"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/applications/{id}", applicationB)
                        .with(authentication(as(userA, "user-a@example.com")))
                        .with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/applications/{id}", applicationB)
                        .with(authentication(as(userA, "user-a@example.com")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequest(companyB, sourceId, privateTechnologyB)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/applications/{id}/status", applicationB)
                        .with(authentication(as(userA, "user-a@example.com")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPLIED\",\"appliedDate\":\"2026-08-13\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/applications")
                        .with(authentication(as(userA, "user-a@example.com")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest(companyB, sourceId, privateTechnologyB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/companies?query=Company")
                        .with(authentication(as(userA, "user-a@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Company A"));
        mockMvc.perform(get("/api/technologies")
                        .with(authentication(as(userA, "user-a@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Private B')]").isEmpty());

        mockMvc.perform(get("/api/analytics/summary")
                        .with(authentication(as(userA, "user-a@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalApplications").value(1));

        mockMvc.perform(get("/api/applications/{id}", applicationB)
                        .with(authentication(as(userB, "user-b@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionTitle").value("Application B"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "status", "delete", "technology"})
    void ownedMutationsWaitForTheAccountBeforeLockingDependentRows(String operation) throws Exception {
        Long company = jdbcTemplate.queryForObject(
                "SELECT company_id FROM job_applications WHERE id = ?", Long.class, applicationA);
        String details = "{\"companyId\":" + company + ",\"positionTitle\":\"Updated\",\"sourceId\":" + sourceId
                + ",\"workMode\":\"REMOTE\",\"technologyIds\":[]}";
        MockHttpServletRequestBuilder request = switch (operation) {
            case "create" -> post("/api/applications").content(details.replace("{", "{\"status\":\"BOOKMARKED\","));
            case "update" -> put("/api/applications/{id}", applicationA).content(details);
            case "status" -> patch("/api/applications/{id}/status", applicationA)
                    .content("{\"status\":\"WITHDRAWN\",\"appliedDate\":\""
                            + java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1) + "\"}");
            case "delete" -> delete("/api/applications/{id}", applicationA);
            case "technology" -> post("/api/technologies").content("{\"name\":\"New technology\"}");
            default -> throw new IllegalArgumentException(operation);
        };
        AccountLockTestSupport.whileUserLocked(jdbcTemplate, transactions, userA,
                () -> mockMvc.perform(request.with(authentication(as(userA, "user-a@example.com")))
                                .with(csrf()).contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().is2xxSuccessful()).andReturn(),
                () -> jdbcTemplate.queryForObject(
                        "SELECT id FROM job_applications WHERE id = ? FOR UPDATE NOWAIT", Long.class, applicationA));
    }

    @Test
    void exportsCompleteOwnerDataBeyondCursorAndListLimitsWithoutSecrets() throws Exception {
        jdbcTemplate.update("INSERT INTO companies (owner_id, name, company_type) SELECT ?, 'Owned ' || n, 'OTHER' FROM generate_series(1, 205) n", userA);
        jdbcTemplate.update("INSERT INTO technologies (owner_id, name) SELECT ?, 'Owned ' || n FROM generate_series(1, 205) n", userA);
        jdbcTemplate.update("""
                INSERT INTO job_applications (owner_id, company_id, position_title, status, source_id, work_mode, notes,
                    salary_min, salary_max, currency, salary_period)
                SELECT ?, (SELECT MIN(id) FROM companies WHERE owner_id = ?), 'Owned ' || n, 'BOOKMARKED', ?, 'REMOTE',
                    'Personal notes', 99999999999999999.99, 99999999999999999.99, 'USD', 'YEARLY' FROM generate_series(1, 205) n
                """, userA, userA, sourceId);
        jdbcTemplate.update("INSERT INTO application_status_history (application_id, status, changed_at) SELECT id, status, CURRENT_TIMESTAMP FROM job_applications WHERE owner_id = ?", userA);
        jdbcTemplate.update("""
                INSERT INTO job_application_technologies (application_id, technology_id)
                SELECT a.id, t.id FROM job_applications a CROSS JOIN technologies t WHERE a.owner_id = ?
                    AND t.id IN ((SELECT MIN(id) FROM technologies WHERE owner_id IS NULL), (SELECT MIN(id) FROM technologies WHERE owner_id = ?))
                """, userA, userA);
        JsonNode json = exportJson(userA);
        assertThat(json.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(java.time.Instant.parse(json.path("exportedAt").asText())).isBeforeOrEqualTo(java.time.Instant.now());
        assertThat(json.path("profile").path("id").asLong()).isEqualTo(userA);
        for (String section : List.of("companies", "applications", "history", "technologies")) {
            assertThat(json.path(section).size()).as(section).isEqualTo(206);
        }
        assertThat(json.path("applicationTechnologies").size()).isEqualTo(412);
        assertThat(json.path("sources").size()).isOne();
        assertThat(json.path("applications").get(1).path("salaryMin").isTextual()).isTrue();
        assertThat(json.path("applications").get(1).path("salaryMin").asText()).isEqualTo("99999999999999999.99");
        assertThat(json.path("complete").asBoolean()).isTrue();
        assertThat(json.toString()).doesNotContain("User B", "Company B", "Private B", "Application B", "{noop}unused", "passwordHash", "googleSubject", "token", "session");
        jdbcTemplate.update("DELETE FROM job_applications WHERE owner_id = ?", userB);
        JsonNode empty = exportJson(userB);
        assertThat(empty.path("applications").size()).isZero();
        assertThat(empty.path("companies").size()).isOne();
        assertThat(empty.path("technologies").get(0).path("name").asText()).isEqualTo("Private B");
    }

    @Test
    void exportUsesOneReadOnlyRepeatableReadSnapshotAcrossSections() throws Exception {
        jdbcTemplate.update("INSERT INTO application_status_history (application_id, status, changed_at) VALUES (?, 'BOOKMARKED', CURRENT_TIMESTAMP)", applicationA);
        TransactionTemplate concurrent = new TransactionTemplate(transactions.getTransactionManager());
        concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        exports.write(userA, new FilterOutputStream(bytes) {
            private boolean changed;

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                if (!changed) {
                    changed = true;
                    assertThat(jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
                    assertThat(jdbcTemplate.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
                    concurrent.executeWithoutResult(status -> {
                        jdbcTemplate.update("UPDATE job_applications SET status = 'WITHDRAWN' WHERE id = ?", applicationA);
                        jdbcTemplate.update("UPDATE application_status_history SET status = 'WITHDRAWN' WHERE application_id = ?", applicationA);
                    });
                }
                out.write(buffer, offset, length);
            }
        });
        JsonNode json = mapper.readTree(bytes.toByteArray());
        assertThat(json.path("applications").get(0).path("status").asText()).isEqualTo("BOOKMARKED");
        assertThat(json.path("history").get(0).path("status").asText()).isEqualTo("BOOKMARKED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM job_applications WHERE id = ?", String.class, applicationA)).isEqualTo("WITHDRAWN");
    }

    @Test
    void interruptedExportDoesNotCloseOrMarkPartialJsonComplete() {
        ByteArrayOutputStream partial = new ByteArrayOutputStream();
        FilterOutputStream disconnected = new FilterOutputStream(partial) {
            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                if (partial.size() > 0) {
                    throw new IOException("Synthetic disconnect");
                }
                out.write(buffer, offset, length);
            }
        };
        assertThatThrownBy(() -> exports.write(userA, disconnected)).isInstanceOf(IOException.class);
        assertThat(partial.toString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("\"complete\"");
        assertThatThrownBy(() -> mapper.readTree(partial.toByteArray())).isInstanceOf(IOException.class);
    }

    @Test
    void exportRequiresAuthenticationAndLimitsAccountAndTrustedClientIpIndependently() throws Exception {
        mockMvc.perform(get("/api/account/export")).andExpect(status().isUnauthorized());
        for (int attempt = 0; attempt < 3; attempt++) {
            exportJson(userA);
        }
        mockMvc.perform(exportRequest(userA).with(servlet -> { servlet.setRemoteAddr("198.51.100.9"); return servlet; }))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        mockMvc.perform(exportRequest(userB).header("X-Forwarded-For", "198.51.100.10"))
                .andExpect(status().isTooManyRequests());
    }

    private MockHttpServletRequestBuilder exportRequest(Long owner) throws Exception {
        String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, owner);
        var login = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", email).param("password", "unused"))
                .andExpect(status().isOk()).andReturn();
        var cookie = login.getResponse().getCookie("APPLYFLOW_SESSION");
        assertThat(cookie).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM spring_session WHERE principal_name = ?", Integer.class, email)).isPositive();
        // Export is the first HTTP request after login, using its already-persisted session.
        return get("/api/account/export").param("ownerId", userB.toString()).cookie(cookie);
    }

    private JsonNode exportJson(Long owner) throws Exception {
        var started = mockMvc.perform(exportRequest(owner)).andExpect(request().asyncStarted()).andReturn();
        var completed = mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"applyflow-account-export.json\""))
                .andReturn();
        return mapper.readTree(completed.getResponse().getContentAsByteArray());
    }

    private Long insertUser(String email, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (full_name, email, password_hash, email_verified_at)
                VALUES (?, ?, '{noop}unused', CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, name, email);
    }

    private Long insertCompany(Long ownerId, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO companies (owner_id, name, company_type)
                VALUES (?, ?, 'OTHER')
                RETURNING id
                """, Long.class, ownerId, name);
    }

    private Long insertApplication(Long ownerId, Long companyId, Long sourceId, String title) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO job_applications (
                    owner_id, company_id, position_title, status, source_id, work_mode
                ) VALUES (?, ?, ?, 'BOOKMARKED', ?, 'REMOTE')
                RETURNING id
                """, Long.class, ownerId, companyId, title, sourceId);
    }

    private String updateRequest(Long companyId, Long sourceId, Long technologyId) {
        return "{\"companyId\":" + companyId + ",\"positionTitle\":\"Blocked update\",\"sourceId\":" + sourceId
                + ",\"workMode\":\"REMOTE\",\"technologyIds\":[" + technologyId + "]}";
    }

    private String createRequest(Long companyId, Long sourceId, Long technologyId) {
        return "{\"companyId\":" + companyId + ",\"positionTitle\":\"Blocked relation\",\"status\":\"BOOKMARKED\",\"sourceId\":" + sourceId
                + ",\"workMode\":\"REMOTE\",\"technologyIds\":[" + technologyId + "]}";
    }

    private Authentication as(Long userId, String email) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new TestPrincipal(userId, email),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private record TestPrincipal(Long userId, String email) implements AuthenticatedUser {
        @Serial
        private static final long serialVersionUID = 1L;
    }
}
