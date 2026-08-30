package com.applyflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class ProductionSecurityConfigurationTest {

    @ParameterizedTest(name = "rejects {1}")
    @MethodSource("unsafeSettings")
    void rejectsUnsafeProductionConfigurationWithoutEchoingValues(Map<String, Object> overrides, String property) {
        assertThatThrownBy(() -> new ProductionSecurityConfiguration(settings(overrides)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(property)
                .hasMessageNotContainingAny("replace-with-secret-value", "synthetic-mail-secret", "synthetic-db-secret");
    }

    @Test
    void acceptsSafeSyntheticProductionConfiguration() {
        assertThatCode(() -> new ProductionSecurityConfiguration(settings(Map.of()))).doesNotThrowAnyException();
    }

    @Test
    void acceptsNonLoopbackIpv6LiteralHosts() {
        assertThatCode(() -> new ProductionSecurityConfiguration(settings(Map.of(
                "databaseUrl", "jdbc:postgresql://[2001:db8::10]:5432/applyflow",
                "frontendUrl", "https://[2001:db8::20]", "corsAllowedOrigins", "https://[2001:db8::20]",
                "googleRedirectUri", "https://[2001:db8::30]/login/oauth2/code/google"))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsOrdinaryFullyQualifiedHostsWithOneTerminalDot() {
        assertThatCode(() -> new ProductionSecurityConfiguration(settings(Map.of(
                "databaseUrl", "jdbc:postgresql://db.example.com.:5432/applyflow",
                "frontendUrl", "https://app.example.com.", "corsAllowedOrigins", "https://app.example.com.",
                "googleRedirectUri", "https://api.example.com./login/oauth2/code/google"))))
                .doesNotThrowAnyException();
    }

    @Test
    void prodProfileEnablesRuntimeTransportGuards() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));
        yaml.afterPropertiesSet();
        var properties = yaml.getObject();
        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(properties.getProperty("app.security.hsts-enabled")).isEqualTo("true");
        assertThat(properties.getProperty("app.mail.delivery-enabled")).isEqualTo("true");
    }

    private static Stream<Arguments> unsafeSettings() {
        return Stream.of(
                Arguments.of(Map.of("databaseUrl", "jdbc:postgresql://localhost:5432/applyflow"), "spring.datasource"),
                Arguments.of(Map.of("databaseUrl", "jdbc:postgresql://localhost.:5432/applyflow"), "spring.datasource"),
                Arguments.of(Map.of("databaseUrl", "jdbc:postgresql://[::1]:5432/applyflow"), "spring.datasource"),
                Arguments.of(Map.of("databaseUrl", "jdbc:postgresql://user:secret@db.example.com/applyflow"), "spring.datasource"),
                Arguments.of(Map.of("databaseUrl", "jdbc:postgresql://db.example.com/applyflow?password=secret"), "spring.datasource"),
                Arguments.of(Map.of("databaseUrl", "jdbc:postgresql://db.example.com/applyflow#secret"), "spring.datasource"),
                Arguments.of(Map.of("databaseUsername", ""), "spring.datasource"),
                Arguments.of(Map.of("databaseUsername", "applyflow"), "spring.datasource"),
                Arguments.of(Map.of("databasePassword", "applyflow"), "spring.datasource"),
                Arguments.of(Map.of("googleClientId", "not-configured"), "google.client-id"),
                Arguments.of(Map.of("googleClientSecret", "replace-with-secret-value"), "google.client-secret"),
                Arguments.of(Map.of("googleRedirectUri", "http://api.example.com/login/oauth2/code/google"), "google.redirect-uri"),
                Arguments.of(Map.of("googleRedirectUri", "https://[::1]/login/oauth2/code/google"), "google.redirect-uri"),
                Arguments.of(Map.of("googleRedirectUri", "https://localhost./login/oauth2/code/google"), "google.redirect-uri"),
                Arguments.of(Map.of("frontendUrl", "http://app.example.com"), "app.frontend-url"),
                Arguments.of(Map.of("frontendUrl", "https://[::1]"), "app.frontend-url"),
                Arguments.of(Map.of("frontendUrl", "https://app.local."), "app.frontend-url"),
                Arguments.of(Map.of("corsAllowedOrigins", "*"), "app.cors.allowed-origins"),
                Arguments.of(Map.of("corsAllowedOrigins", "https://other.example.com"), "app.cors.allowed-origins"),
                Arguments.of(Map.of("secureCookie", false), "session.cookie.secure"),
                Arguments.of(Map.of("cookieSameSite", "strict"), "session.cookie.same-site"),
                Arguments.of(Map.of("hstsEnabled", false), "app.security.hsts-enabled"),
                Arguments.of(Map.of("forwardHeadersStrategy", "native"), "server.forward-headers-strategy"),
                Arguments.of(Map.of("trustedProxyMode", "forwarded"), "trusted-proxy configuration"),
                Arguments.of(Map.of("mailHost", "localhost"), "spring.mail.host"),
                Arguments.of(Map.of("mailUsername", ""), "spring.mail.username"),
                Arguments.of(Map.of("mailPassword", ""), "spring.mail.password"),
                Arguments.of(Map.of("mailAuthentication", false), "mail.smtp.auth"),
                Arguments.of(Map.of("startTlsEnabled", false), "mail.smtp.starttls"),
                Arguments.of(Map.of("startTlsRequired", false), "mail.smtp.starttls"),
                Arguments.of(Map.of("mailFrom", "no-reply@applyflow.local"), "app.mail.from"),
                Arguments.of(Map.of("mailFrom", "first..last@example.com"), "app.mail.from"),
                Arguments.of(Map.of("messageIdDomain", "applyflow.local"), "app.mail.message-id-domain"),
                Arguments.of(Map.of("messageIdDomain", " mail.example.com "), "app.mail.message-id-domain"),
                Arguments.of(Map.of("outboxEncryptionKey", "not-a-key"), "OUTBOX_ENCRYPTION_KEY"),
                Arguments.of(Map.of("outboxEncryptionKey", "0123456789abcdef".repeat(4)), "OUTBOX_ENCRYPTION_KEY"));
    }

    private static ProductionSecurityConfiguration.Settings settings(Map<String, Object> values) {
        return new ProductionSecurityConfiguration.Settings(
                string(values, "databaseUrl", "jdbc:postgresql://db.internal.example:5432/applyflow"),
                string(values, "databaseUsername", "synthetic-db-user"),
                string(values, "databasePassword", "synthetic-db-secret"),
                string(values, "googleClientId", "synthetic-client-id"),
                string(values, "googleClientSecret", "synthetic-google-secret"),
                string(values, "googleRedirectUri", "https://api.example.com/login/oauth2/code/google"),
                string(values, "frontendUrl", "https://app.example.com"),
                string(values, "corsAllowedOrigins", "https://app.example.com"),
                bool(values, "secureCookie", true), string(values, "cookieSameSite", "lax"),
                bool(values, "hstsEnabled", true), string(values, "forwardHeadersStrategy", "none"),
                string(values, "trustedProxyMode", "none"), string(values, "trustedProxyCidrs", ""),
                bool(values, "mailDeliveryEnabled", true), string(values, "mailHost", "smtp.example.com"),
                string(values, "mailUsername", "synthetic-user"), string(values, "mailPassword", "synthetic-mail-secret"),
                bool(values, "mailAuthentication", true), bool(values, "startTlsEnabled", true),
                bool(values, "startTlsRequired", true), string(values, "mailFrom", "no-reply@example.com"),
                string(values, "messageIdDomain", "mail.example.com"),
                string(values, "outboxEncryptionKey", "abcdef0123456789".repeat(4)));
    }

    private static String string(Map<String, Object> values, String key, String fallback) {
        return (String) values.getOrDefault(key, fallback);
    }

    private static boolean bool(Map<String, Object> values, String key, boolean fallback) {
        return (boolean) values.getOrDefault(key, fallback);
    }
}
