package com.applyflow.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OutboxTokenCipher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public OutboxTokenCipher(@Value("${app.mail.outbox.encryption-key}") String keyMaterial) {
        try {
            byte[] keyBytes = HexFormat.of().parseHex(keyMaterial);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("must contain exactly 32 bytes");
            }
            this.key = new SecretKeySpec(keyBytes, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("app.mail.outbox.encryption-key must be a 64-character hexadecimal AES-256 key", exception);
        }
    }

    public String encrypt(UUID outboxId, String plaintext) {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(outboxId.toString().getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt account email token", exception);
        }
    }

    public String decrypt(UUID outboxId, String ciphertext) {
        String[] parts = ciphertext.split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalStateException("Invalid encrypted outbox payload");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128,
                    Base64.getUrlDecoder().decode(parts[0])));
            cipher.updateAAD(outboxId.toString().getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to decrypt account email token", exception);
        }
    }
}
