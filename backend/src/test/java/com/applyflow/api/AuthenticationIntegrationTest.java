package com.applyflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.applyflow.entity.AccountTokenPurpose;
import com.applyflow.entity.UserAccount;
import com.applyflow.repository.UserAccountRepository;
import com.applyflow.service.AccountEmailSender;
import com.applyflow.service.AccountEmailOutboxProcessor;
import com.applyflow.service.AccountEmailOutboxService;
import com.applyflow.service.OidcAccountService;

import jakarta.servlet.http.Cookie;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthenticationIntegrationTest.EmailTestConfiguration.class)
class AuthenticationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
        registry.add("app.mail.delivery-enabled", () -> true);
        // Exclude the scheduled bean entirely; outbox tests drive distinct processors explicitly.
        registry.add("app.mail.outbox.scheduling-enabled", () -> false);
        registry.add("app.security.trusted-proxies.mode", () -> "x-forwarded-for");
        registry.add("app.security.trusted-proxies.cidrs", () -> "10.0.0.0/24");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CapturingAccountEmailSender emailSender;

    @Autowired
    private AccountEmailOutboxService outboxService;

    @Autowired
    private OidcAccountService oidcAccountService;

    @Autowired
    private UserAccountRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM spring_session");
        jdbcTemplate.update("DELETE FROM job_applications");
        jdbcTemplate.update("DELETE FROM companies");
        jdbcTemplate.update("DELETE FROM technologies WHERE owner_id IS NOT NULL");
        jdbcTemplate.update("DELETE FROM account_email_outbox");
        jdbcTemplate.update("DELETE FROM account_tokens");
        jdbcTemplate.update("DELETE FROM auth_rate_limits");
        jdbcTemplate.update("DELETE FROM users");
        emailSender.clear();
    }

    @Test
    void registersVerifiesLogsInAndLogsOutWithoutPersistingTheRawToken() throws Exception {
        mockMvc.perform(register("Ferdi Example", "Ferdi@Example.com", "a-secure-password"))
                .andExpect(status().isAccepted());
        deliverEmails();

        CapturedEmail verification = emailSender.onlyMessage();
        assertThat(verification.email()).isEqualTo("ferdi@example.com");
        assertThat(verification.purpose()).isEqualTo(AccountTokenPurpose.EMAIL_VERIFICATION);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_tokens WHERE token_hash = ?",
                Integer.class,
                verification.rawToken())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_email_outbox WHERE encrypted_token = ?",
                Integer.class,
                verification.rawToken())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE email = 'ferdi@example.com'",
                String.class)).startsWith("{bcrypt}");

        mockMvc.perform(post("/api/auth/email-verification/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + verification.rawToken() + "\"}"))
                .andExpect(status().isNoContent());

        MvcResult login = mockMvc.perform(login("ferdi@example.com", "a-secure-password", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ferdi@example.com"))
                .andExpect(jsonPath("$.authenticationMethods[0]").value("PASSWORD"))
                .andReturn();
        Cookie sessionCookie = login.getResponse().getCookie("APPLYFLOW_SESSION");
        assertThat(sessionCookie).isNotNull();

        mockMvc.perform(get("/api/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ferdi Example"));

        mockMvc.perform(post("/api/auth/logout").cookie(sessionCookie).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me").cookie(sessionCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationConsumesPostgreSqlRateLimitBucketsBeforeCreatingTheAccount() throws Exception {
        mockMvc.perform(register("Rate Limited Registration", "rate-limit-registration@example.com", "a-secure-password"))
                .andExpect(status().isAccepted());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_rate_limits WHERE bucket_key LIKE 'register:%'",
                Integer.class)).isEqualTo(2);
        assertThat(userRepository.findByEmailIgnoreCase("rate-limit-registration@example.com")).isPresent();
    }

    @Test
    void protectsStateChangesWithCsrfAndRejectsUnverifiedPasswordLogin() throws Exception {
        mockMvc.perform(register("Pending User", "pending@example.com", "a-secure-password"))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("No Csrf", "no-csrf@example.com", "a-secure-password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(login("pending@example.com", "a-secure-password", false))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/applications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rateLimitsLoginByIpAndIdentityWithAProblemResponse() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            mockMvc.perform(login("limited@example.com", "not-the-password", false))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(login("limited@example.com", "not-the-password", false))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.title").value("Too many attempts"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Retry-After")).isNotBlank());
    }

    @Test
    void forgedHeadersCannotSplitAnUntrustedPeersLoginIpBucket() throws Exception {
        for (int attempt = 0; attempt < 11; attempt++) {
            MvcResult result = mockMvc.perform(login("forged-" + attempt + "@example.com", "wrong-password", false)
                            .servletPath("/api/auth/login")
                            .with(remoteAddress("203.0.113.9"))
                            .header("Forwarded", "for=198.51.100." + attempt)
                            .header("X-Forwarded-For", "192.0.2." + attempt))
                    .andExpect(attempt < 10 ? status().isUnauthorized() : status().isTooManyRequests())
                    .andReturn();
            if (attempt == 10) {
                assertThat(result.getResponse().getHeader("Retry-After")).isNotBlank();
            }
        }
        assertThat(jdbcTemplate.queryForList(
                "SELECT attempts FROM auth_rate_limits WHERE bucket_key LIKE 'login:ip:%'", Integer.class))
                .containsExactly(11);
    }

    @Test
    void rateLimitsEachPublicEmailEndpointAcrossIndependentIpAndIdentityBuckets() throws Exception {
        assertRateLimitedByIdentity("/api/auth/register", 5, ignored -> registrationJson(
                "Limited User", "register-limit@example.com", "a-secure-password"), status().isAccepted());
        assertRateLimitedByIp("/api/auth/password/forgot", attempt -> "{\"email\":\"forgot-" + attempt + "@example.com\"}", status().isAccepted());
        assertRateLimitedByIdentity("/api/auth/password/reset", 8,
                ignored -> "{\"token\":\"invalid-reset-token\",\"password\":\"a-secure-password\",\"passwordConfirmation\":\"a-secure-password\"}",
                status().isBadRequest());
        assertRateLimitedByIp("/api/auth/email-verification/resend", attempt -> "{\"email\":\"resend-" + attempt + "@example.com\"}", status().isAccepted());
    }

    @Test
    void retriesPersistedOutboxDeliveryAfterFailureAndCanResumeFromAnotherProcessor() throws Exception {
        mockMvc.perform(register("Outbox User", "outbox@example.com", "a-secure-password"))
                .andExpect(status().isAccepted());
        emailSender.failNextDelivery();
        AccountEmailOutboxProcessor first = new AccountEmailOutboxProcessor(outboxService, true);
        AccountEmailOutboxProcessor second = new AccountEmailOutboxProcessor(outboxService, true);

        first.deliverPending();

        assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM account_email_outbox", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT sent_at IS NULL FROM account_email_outbox", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT claim_token IS NULL FROM account_email_outbox", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT next_attempt_at > CURRENT_TIMESTAMP FROM account_email_outbox", Boolean.class)).isTrue();
        // Move it decisively into the past; TIMESTAMP(3) rounding can put CURRENT_TIMESTAMP
        // fractionally ahead of the JVM clock used by the next claim query.
        jdbcTemplate.update("UPDATE account_email_outbox SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");

        second.deliverPending();

        assertThat(emailSender.messages()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT sent_at IS NOT NULL FROM account_email_outbox", Boolean.class)).isTrue();
    }

    @Test
    void twoProcessorsClaimOneOutboxMessageOnlyOnceBeforeSmtpDelivery() throws Exception {
        mockMvc.perform(register("Concurrent Outbox", "outbox-concurrent@example.com", "a-secure-password"))
                .andExpect(status().isAccepted());
        emailSender.pauseNextDelivery();
        AccountEmailOutboxProcessor first = new AccountEmailOutboxProcessor(outboxService, true);
        AccountEmailOutboxProcessor second = new AccountEmailOutboxProcessor(outboxService, true);

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            var firstRun = workers.submit(first::deliverPending);
            emailSender.awaitDeliveryStart();
            var secondRun = workers.submit(second::deliverPending);
            secondRun.get(5, TimeUnit.SECONDS);
            emailSender.releaseDelivery();
            firstRun.get(5, TimeUnit.SECONDS);
        }

        assertThat(emailSender.messages()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account_email_outbox WHERE sent_at IS NOT NULL", Integer.class)).isEqualTo(1);
    }

    @Test
    void passwordResetIsGenericHasOneTimeUseAndReplacesTheCredential() throws Exception {
        registerAndVerify("Reset User", "reset@example.com", "old-password-value");
        emailSender.clear();

        mockMvc.perform(post("/api/auth/password/forgot")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reset@example.com\"}"))
                .andExpect(status().isAccepted());
        deliverEmails();
        String rawToken = emailSender.onlyMessage().rawToken();
        String resetBody = "{\"token\":\"" + rawToken
                + "\",\"password\":\"new-password-value\","
                + "\"passwordConfirmation\":\"new-password-value\"}";

        mockMvc.perform(post("/api/auth/password/reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/password/reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody))
                .andExpect(status().isBadRequest());
        mockMvc.perform(login("reset@example.com", "old-password-value", false))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(login("reset@example.com", "new-password-value", false))
                .andExpect(status().isOk());

        emailSender.clear();
        mockMvc.perform(post("/api/auth/password/forgot")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"missing@example.com\"}"))
                .andExpect(status().isAccepted());
        assertThat(emailSender.messages()).isEmpty();
    }

    @Test
    void passwordResetInvalidatesEveryExistingSession() throws Exception {
        registerAndVerify("Session User", "sessions@example.com", "old-password-value");
        Cookie firstSession = mockMvc.perform(login("sessions@example.com", "old-password-value", false))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("APPLYFLOW_SESSION");
        Cookie secondSession = mockMvc.perform(login("sessions@example.com", "old-password-value", false))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("APPLYFLOW_SESSION");
        assertThat(firstSession).isNotNull();
        assertThat(secondSession).isNotNull();

        emailSender.clear();
        mockMvc.perform(post("/api/auth/password/forgot").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"sessions@example.com\"}"))
                .andExpect(status().isAccepted());
        deliverEmails();
        String token = emailSender.onlyMessage().rawToken();
        mockMvc.perform(post("/api/auth/password/reset").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"new-password-value\",\"passwordConfirmation\":\"new-password-value\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").cookie(firstSession)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").cookie(secondSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void verifiedPasswordAccountLinksToGoogleByEmailWithoutCreatingADuplicate() throws Exception {
        registerAndVerify("Password First", "same@example.com", "a-secure-password");
        UserAccount before = userRepository.findByEmailIgnoreCase("same@example.com").orElseThrow();

        UserAccount reconciled = oidcAccountService.reconcile(
                "google-subject-a", "SAME@example.com", true, "Google Name");

        assertThat(reconciled.getId()).isEqualTo(before.getId());
        assertThat(reconciled.getGoogleSubject()).isEqualTo("google-subject-a");
        assertThat(reconciled.hasPassword()).isTrue();
        assertThat(userRepository.count()).isOne();
    }

    @Test
    void googleFirstRegistrationUsesPasswordSetupAndKeepsOneUser() throws Exception {
        UserAccount googleUser = oidcAccountService.reconcile(
                "google-subject-b", "google-first@example.com", true, "Google First");
        emailSender.clear();

        mockMvc.perform(register("Different Submitted Name", "google-first@example.com", "a-secure-password"))
                .andExpect(status().isAccepted());
        deliverEmails();

        CapturedEmail setup = emailSender.onlyMessage();
        assertThat(setup.purpose()).isEqualTo(AccountTokenPurpose.PASSWORD_SETUP);
        assertThat(userRepository.count()).isOne();
        assertThat(userRepository.findById(googleUser.getId()).orElseThrow().hasPassword()).isFalse();

        String body = "{\"token\":\"" + setup.rawToken()
                + "\",\"password\":\"a-secure-password\","
                + "\"passwordConfirmation\":\"a-secure-password\"}";
        mockMvc.perform(post("/api/auth/password/reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
        UserAccount linked = userRepository.findById(googleUser.getId()).orElseThrow();
        assertThat(linked.hasGoogle()).isTrue();
        assertThat(linked.hasPassword()).isTrue();
    }

    @Test
    void rejectsUnverifiedGoogleEmailAndConflictingProviderSubjects() {
        assertThatThrownBy(() -> oidcAccountService.reconcile(
                "unverified-subject", "unverified@example.com", false, "Unverified"))
                .isInstanceOf(OAuth2AuthenticationException.class);

        oidcAccountService.reconcile("first-subject", "conflict@example.com", true, "Conflict");
        assertThatThrownBy(() -> oidcAccountService.reconcile(
                "second-subject", "conflict@example.com", true, "Conflict"))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(userRepository.count()).isOne();
    }

    @Test
    void rejectsOversizedCredentialsAndProviderClaimsBeforePersistence() throws Exception {
        mockMvc.perform(login("a".repeat(255), "password", false))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE));

        mockMvc.perform(register("Bounded User", "bounded@example.com", "a".repeat(73)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());

        assertThatThrownBy(() -> oidcAccountService.reconcile(
                "s".repeat(256), "bounded-provider@example.com", true, "Bounded Provider"))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void rejectsCurrentPasswordsBeyondTheBcryptByteBoundaryWithoutTruncationEquivalence() throws Exception {
        String boundaryPassword = "é".repeat(36);
        registerAndVerify("Bcrypt Boundary", "bcrypt-boundary@example.com", boundaryPassword);
        Cookie session = mockMvc.perform(login("bcrypt-boundary@example.com", boundaryPassword, false)).andExpect(status().isOk()).andReturn().getResponse().getCookie("APPLYFLOW_SESSION");
        String body = "{\"currentPassword\":\"" + boundaryPassword + "x\",\"password\":\"new-password-value\",\"passwordConfirmation\":\"new-password-value\"}";
        mockMvc.perform(put("/api/auth/password").with(csrf()).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Invalid request"));
        mockMvc.perform(put("/api/auth/password").with(csrf()).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace(boundaryPassword + "x", boundaryPassword))).andExpect(status().isNoContent());
    }

    private void registerAndVerify(String name, String email, String password) throws Exception {
        mockMvc.perform(register(name, email, password)).andExpect(status().isAccepted());
        deliverEmails();
        String token = emailSender.onlyMessage().rawToken();
        mockMvc.perform(post("/api/auth/email-verification/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isNoContent());
    }

    private void deliverEmails() {
        new AccountEmailOutboxProcessor(outboxService, true).deliverPending();
    }

    private void assertRateLimitedByIdentity(
            String path,
            int limit,
            IntFunction<String> body,
            ResultMatcher initialStatus
    ) throws Exception {
        for (int attempt = 0; attempt < limit; attempt++) {
            mockMvc.perform(publicJsonPost(path, body.apply(attempt), "198.51.100." + attempt)).andExpect(initialStatus);
        }
        mockMvc.perform(publicJsonPost(path, body.apply(limit), "198.51.100.250"))
                .andExpect(status().isTooManyRequests());
    }

    private void assertRateLimitedByIp(String path, IntFunction<String> body, ResultMatcher initialStatus) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(publicJsonPost(path, body.apply(attempt), "203.0.113.10")).andExpect(initialStatus);
        }
        mockMvc.perform(publicJsonPost(path, body.apply(5), "203.0.113.10"))
                .andExpect(status().isTooManyRequests());
    }

    private MockHttpServletRequestBuilder publicJsonPost(String path, String body, String remoteAddress) {
        return post(path).with(csrf()).with(remoteAddress(remoteAddress))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private RequestPostProcessor remoteAddress(String value) {
        return request -> {
            request.setRemoteAddr(value);
            return request;
        };
    }

    private MockHttpServletRequestBuilder register(String name, String email, String password) {
        return post("/api/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationJson(name, email, password));
    }

    private String registrationJson(String name, String email, String password) {
        return "{\"fullName\":\"" + name + "\",\"email\":\"" + email
                + "\",\"password\":\"" + password
                + "\",\"passwordConfirmation\":\"" + password + "\"}";
    }

    private MockHttpServletRequestBuilder login(String email, String password, boolean rememberMe) {
        return post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", email)
                .param("password", password)
                .param("rememberMe", Boolean.toString(rememberMe));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EmailTestConfiguration {
        @Bean
        @Primary
        CapturingAccountEmailSender capturingAccountEmailSender() {
            return new CapturingAccountEmailSender();
        }
    }

    static final class CapturingAccountEmailSender implements AccountEmailSender {
        private final List<CapturedEmail> messages = new ArrayList<>();
        private int failuresRemaining;
        private CountDownLatch deliveryStarted;
        private CountDownLatch deliveryRelease;

        @Override
        public void sendAccountLink(
                String email,
                String fullName,
                AccountTokenPurpose purpose,
                String rawToken,
                java.util.UUID deliveryId
        ) {
            CountDownLatch started;
            CountDownLatch release;
            synchronized (this) {
                if (failuresRemaining > 0) {
                    failuresRemaining--;
                    throw new IllegalStateException("Synthetic SMTP failure");
                }
                started = deliveryStarted;
                release = deliveryRelease;
            }
            if (started != null) {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release synthetic SMTP delivery");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while delivering synthetic SMTP message", exception);
                }
                synchronized (this) {
                    deliveryStarted = null;
                    deliveryRelease = null;
                }
            }
            synchronized (this) {
                messages.add(new CapturedEmail(email, fullName, purpose, rawToken));
            }
        }

        synchronized void failNextDelivery() {
            failuresRemaining = 1;
        }

        synchronized void pauseNextDelivery() {
            deliveryStarted = new CountDownLatch(1);
            deliveryRelease = new CountDownLatch(1);
        }

        void awaitDeliveryStart() {
            CountDownLatch started;
            synchronized (this) {
                started = deliveryStarted;
            }
            try {
                if (started == null || !started.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Synthetic SMTP delivery did not start");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for synthetic SMTP delivery", exception);
            }
        }

        synchronized void releaseDelivery() {
            if (deliveryRelease != null) {
                deliveryRelease.countDown();
            }
        }

        synchronized CapturedEmail onlyMessage() {
            assertThat(messages).hasSize(1);
            return messages.getFirst();
        }

        synchronized List<CapturedEmail> messages() {
            return List.copyOf(messages);
        }

        synchronized void clear() {
            messages.clear();
            failuresRemaining = 0;
            deliveryStarted = null;
            deliveryRelease = null;
        }
    }

    record CapturedEmail(String email, String fullName, AccountTokenPurpose purpose, String rawToken) {
    }
}
