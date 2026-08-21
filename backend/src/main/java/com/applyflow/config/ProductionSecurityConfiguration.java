package com.applyflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
@Profile("prod")
public class ProductionSecurityConfiguration {

    private final boolean secureCookie;
    private final boolean hstsEnabled;
    private final boolean mailDeliveryEnabled;
    private final String outboxEncryptionKey;

    public ProductionSecurityConfiguration(
            @Value("${server.servlet.session.cookie.secure}") boolean secureCookie,
            @Value("${app.security.hsts-enabled}") boolean hstsEnabled,
            @Value("${app.mail.delivery-enabled}") boolean mailDeliveryEnabled,
            @Value("${app.mail.outbox.encryption-key}") String outboxEncryptionKey
    ) {
        this.secureCookie = secureCookie;
        this.hstsEnabled = hstsEnabled;
        this.mailDeliveryEnabled = mailDeliveryEnabled;
        this.outboxEncryptionKey = outboxEncryptionKey;
    }

    @PostConstruct
    void validate() {
        if (!secureCookie || !hstsEnabled || !mailDeliveryEnabled
                || "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".equals(outboxEncryptionKey)) {
            throw new IllegalStateException("The prod profile requires secure cookies, HSTS, enabled SMTP delivery, and a unique OUTBOX_ENCRYPTION_KEY");
        }
    }
}
