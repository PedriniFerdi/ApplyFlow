package com.applyflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class OutboxTokenCipherTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void encryptsAccountTokensWithoutLeavingTheirPlaintextInTheOutboxPayload() {
        OutboxTokenCipher cipher = new OutboxTokenCipher(KEY);
        UUID messageId = UUID.randomUUID();
        String encrypted = cipher.encrypt(messageId, "one-time-secret");

        assertThat(encrypted).doesNotContain("one-time-secret");
        assertThat(cipher.decrypt(messageId, encrypted)).isEqualTo("one-time-secret");
        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), encrypted)).isInstanceOf(IllegalStateException.class);
    }
}
