package com.applyflow.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class ProductionSecurityConfigurationTest {

    private static final String DEFAULT_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String UNIQUE_KEY = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Test
    void rejectsAnUnsafeProductionConfiguration() {
        assertThatThrownBy(() -> new ProductionSecurityConfiguration(false, true, true, UNIQUE_KEY).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secure cookies");
        assertThatThrownBy(() -> new ProductionSecurityConfiguration(true, true, true, DEFAULT_KEY).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OUTBOX_ENCRYPTION_KEY");
    }

    @Test
    void acceptsACompleteProductionConfiguration() {
        assertThatCode(() -> new ProductionSecurityConfiguration(true, true, true, UNIQUE_KEY).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void prodProfileResourceEnablesTheRuntimeHttpsAndCookieGuards() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));

        assertThatCode(() -> yaml.afterPropertiesSet()).doesNotThrowAnyException();
        var properties = yaml.getObject();
        org.assertj.core.api.Assertions.assertThat(properties).isNotNull();
        org.assertj.core.api.Assertions.assertThat(properties.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        org.assertj.core.api.Assertions.assertThat(properties.getProperty("app.security.hsts-enabled")).isEqualTo("true");
        org.assertj.core.api.Assertions.assertThat(properties.getProperty("app.mail.delivery-enabled")).isEqualTo("true");
    }
}
