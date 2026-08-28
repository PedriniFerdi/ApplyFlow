package com.applyflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mail.javamail.JavaMailSender;

import com.applyflow.entity.AccountTokenPurpose;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

class SmtpAccountEmailSenderTest {

    @ParameterizedTest
    @EnumSource(AccountTokenPurpose.class)
    void sendsPurposeSpecificTextWithDeletionCodeOutsideTheStaticUrl(AccountTokenPurpose purpose) throws Exception {
        JavaMailSender mail = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage((Session) null);
        when(mail.createMimeMessage()).thenReturn(message);
        String code = "A".repeat(43);
        UUID deliveryId = UUID.randomUUID();
        new SmtpAccountEmailSender(mail, true, "sender@example.com", "https://applyflow.example/")
                .sendAccountLink("owner@example.com", "Owner", purpose, code, deliveryId);
        verify(mail).send(message);
        String body = (String) message.getContent();
        String path = switch (purpose) {
            case EMAIL_VERIFICATION -> "/verify-email?token=" + code;
            case PASSWORD_RESET, PASSWORD_SETUP -> "/reset-password?token=" + code;
            case ACCOUNT_DELETION -> "/settings/security";
        };
        assertThat(Pattern.compile("https://\\S+").matcher(body).results().map(MatchResult::group).toList())
                .containsExactly("https://applyflow.example" + path);
        assertThat(body.split(Pattern.quote(code), -1)).hasSize(2);
        assertThat(message.getHeader("X-ApplyFlow-Delivery-Id", null)).isEqualTo(deliveryId.toString());
        if (purpose == AccountTokenPurpose.ACCOUNT_DELETION) {
            assertThat(message.getSubject()).isEqualTo("Confirm deletion of your ApplyFlow account");
            assertThat(body).contains("Sign in to the same ApplyFlow account", "\n" + code + "\n",
                    "Requesting this code does not delete anything.", "permanently delete your account");
        }
    }
}
