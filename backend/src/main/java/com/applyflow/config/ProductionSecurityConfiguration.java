package com.applyflow.config;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.mail.internet.InternetAddress;

@Component
@Profile("prod")
public class ProductionSecurityConfiguration {

    private static final String SAMPLE_OUTBOX_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    public ProductionSecurityConfiguration(
            @Value("${spring.datasource.url}") String databaseUrl,
            @Value("${spring.datasource.username}") String databaseUsername,
            @Value("${spring.datasource.password}") String databasePassword,
            @Value("${spring.security.oauth2.client.registration.google.client-id}") String googleClientId,
            @Value("${spring.security.oauth2.client.registration.google.client-secret}") String googleClientSecret,
            @Value("${spring.security.oauth2.client.registration.google.redirect-uri}") String googleRedirectUri,
            @Value("${app.frontend-url}") String frontendUrl,
            @Value("${app.cors.allowed-origins}") String corsAllowedOrigins,
            @Value("${server.servlet.session.cookie.secure}") boolean secureCookie,
            @Value("${server.servlet.session.cookie.same-site}") String cookieSameSite,
            @Value("${app.security.hsts-enabled}") boolean hstsEnabled,
            @Value("${server.forward-headers-strategy}") String forwardHeadersStrategy,
            @Value("${app.security.trusted-proxies.mode}") String trustedProxyMode,
            @Value("${app.security.trusted-proxies.cidrs}") String trustedProxyCidrs,
            @Value("${app.mail.delivery-enabled}") boolean mailDeliveryEnabled,
            @Value("${spring.mail.host}") String mailHost,
            @Value("${spring.mail.username}") String mailUsername,
            @Value("${spring.mail.password}") String mailPassword,
            @Value("${spring.mail.properties.mail.smtp.auth}") boolean mailAuthentication,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable}") boolean startTlsEnabled,
            @Value("${spring.mail.properties.mail.smtp.starttls.required}") boolean startTlsRequired,
            @Value("${app.mail.from}") String mailFrom,
            @Value("${app.mail.message-id-domain}") String messageIdDomain,
            @Value("${app.mail.outbox.encryption-key}") String outboxEncryptionKey
    ) {
        this(new Settings(databaseUrl, databaseUsername, databasePassword, googleClientId, googleClientSecret, googleRedirectUri,
                frontendUrl, corsAllowedOrigins, secureCookie, cookieSameSite, hstsEnabled, forwardHeadersStrategy,
                trustedProxyMode, trustedProxyCidrs, mailDeliveryEnabled, mailHost, mailUsername, mailPassword,
                mailAuthentication, startTlsEnabled, startTlsRequired, mailFrom, messageIdDomain, outboxEncryptionKey));
    }

    ProductionSecurityConfiguration(Settings settings) {
        List<String> invalid = new ArrayList<>();
        require(safeDatabase(settings.databaseUrl(), settings.databaseUsername(), settings.databasePassword()), invalid, "spring.datasource");
        require(!placeholder(settings.googleClientId()), invalid, "google.client-id");
        require(!placeholder(settings.googleClientSecret()), invalid, "google.client-secret");
        require(httpsCallback(settings.googleRedirectUri()), invalid, "google.redirect-uri");
        require(httpsOrigin(settings.frontendUrl()), invalid, "app.frontend-url");
        List<String> origins = Arrays.stream(settings.corsAllowedOrigins().split(",", -1)).map(String::trim).toList();
        require(!origins.isEmpty() && origins.stream().allMatch(ProductionSecurityConfiguration::httpsOrigin)
                && origins.contains(settings.frontendUrl()), invalid, "app.cors.allowed-origins");
        require(settings.secureCookie(), invalid, "session.cookie.secure");
        require(Set.of("lax", "none").contains(normalize(settings.cookieSameSite())), invalid, "session.cookie.same-site");
        require(settings.hstsEnabled(), invalid, "app.security.hsts-enabled");
        require("none".equals(normalize(settings.forwardHeadersStrategy())), invalid, "server.forward-headers-strategy");
        boolean noProxyHeaders = "none".equals(normalize(settings.trustedProxyMode()));
        require(noProxyHeaders == settings.trustedProxyCidrs().isBlank(), invalid, "trusted-proxy configuration");
        require(settings.mailDeliveryEnabled(), invalid, "app.mail.delivery-enabled");
        require(nonLocalHost(settings.mailHost()), invalid, "spring.mail.host");
        require(!settings.mailUsername().isBlank(), invalid, "spring.mail.username");
        require(!settings.mailPassword().isBlank(), invalid, "spring.mail.password");
        require(settings.mailAuthentication(), invalid, "mail.smtp.auth");
        require(settings.startTlsEnabled() && settings.startTlsRequired(), invalid, "mail.smtp.starttls");
        require(validAddress(settings.mailFrom()), invalid, "app.mail.from");
        require(settings.messageIdDomain().equals(normalize(settings.messageIdDomain()))
                && validDomain(settings.messageIdDomain()), invalid, "app.mail.message-id-domain");
        require(settings.outboxEncryptionKey().matches("(?i)[0-9a-f]{64}")
                && !SAMPLE_OUTBOX_KEY.equalsIgnoreCase(settings.outboxEncryptionKey()), invalid, "OUTBOX_ENCRYPTION_KEY");
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("Unsafe prod configuration: " + String.join(", ", invalid));
        }
    }

    private static boolean safeDatabase(String value, String username, String password) {
        try {
            URI uri = URI.create(value.substring("jdbc:".length()));
            return value.startsWith("jdbc:postgresql://") && nonLocalHost(uri.getHost())
                    && uri.getPath() != null && uri.getPath().length() > 1
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && !username.isBlank() && !"applyflow".equals(username)
                    && !password.isBlank() && !"applyflow".equals(password);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean httpsOrigin(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && nonLocalHost(uri.getHost()) && uri.getUserInfo() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty()) && uri.getQuery() == null && uri.getFragment() == null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean httpsCallback(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && nonLocalHost(uri.getHost()) && uri.getUserInfo() == null
                    && "/login/oauth2/code/google".equals(uri.getPath()) && uri.getQuery() == null && uri.getFragment() == null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean validAddress(String value) {
        try {
            InternetAddress address = new InternetAddress(value, true);
            address.validate();
            int separator = address.getAddress().lastIndexOf('@');
            return value.equals(address.getAddress()) && separator > 0
                    && validDomain(address.getAddress().substring(separator + 1));
        } catch (jakarta.mail.internet.AddressException exception) {
            return false;
        }
    }

    private static boolean validDomain(String value) {
        String domain = normalize(value);
        return domain.length() <= 253 && domain.contains(".") && !domain.endsWith(".local")
                && domain.matches("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
    }

    private static boolean nonLocalHost(String value) {
        String host = normalize(value);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
            if (host.endsWith(".")) return false;
        }
        if (host.isBlank() || host.equals("localhost") || host.endsWith(".local")) return false;
        boolean literal = host.contains(":") || host.matches("[0-9.]+");
        if (!literal) return true;
        if (!host.matches("[0-9a-f:.]+") || (host.indexOf(':') < 0 && !host.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}"))) return false;
        try {
            InetAddress address = InetAddress.getByName(host);
            return !address.isAnyLocalAddress() && !address.isLoopbackAddress();
        } catch (java.net.UnknownHostException exception) {
            return false;
        }
    }

    private static boolean placeholder(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() || normalized.equals("not-configured") || normalized.startsWith("replace-with-");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void require(boolean condition, List<String> invalid, String property) {
        if (!condition) invalid.add(property);
    }

    record Settings(String databaseUrl, String databaseUsername, String databasePassword, String googleClientId, String googleClientSecret,
            String googleRedirectUri, String frontendUrl, String corsAllowedOrigins, boolean secureCookie,
            String cookieSameSite, boolean hstsEnabled, String forwardHeadersStrategy, String trustedProxyMode,
            String trustedProxyCidrs, boolean mailDeliveryEnabled, String mailHost, String mailUsername,
            String mailPassword, boolean mailAuthentication, boolean startTlsEnabled, boolean startTlsRequired,
            String mailFrom, String messageIdDomain, String outboxEncryptionKey) {
    }
}
