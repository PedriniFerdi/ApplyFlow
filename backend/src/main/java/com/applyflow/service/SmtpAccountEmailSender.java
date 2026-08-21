package com.applyflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.applyflow.entity.AccountTokenPurpose;

@Component
public class SmtpAccountEmailSender implements AccountEmailSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmtpAccountEmailSender.class);

    private final JavaMailSender mailSender;
    private final boolean deliveryEnabled;
    private final String from;
    private final String frontendUrl;

    public SmtpAccountEmailSender(
            JavaMailSender mailSender,
            @Value("${app.mail.delivery-enabled}") boolean deliveryEnabled,
            @Value("${app.mail.from}") String from,
            @Value("${app.frontend-url}") String frontendUrl
    ) {
        this.mailSender = mailSender;
        this.deliveryEnabled = deliveryEnabled;
        this.from = from;
        this.frontendUrl = frontendUrl.replaceAll("/$", "");
    }

    @Override
    public void sendAccountLink(String email, String fullName, AccountTokenPurpose purpose, String rawToken, java.util.UUID deliveryId) {
        if (!deliveryEnabled) {
            LOGGER.warn("Account email delivery is disabled; event was not delivered for purpose {}", purpose);
            return;
        }
        try {
            var message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject(subject(purpose));
            helper.setText(body(fullName, purpose, rawToken), false);
            message.setHeader("Message-ID", "<applyflow-" + deliveryId + "@applyflow.local>");
            message.setHeader("X-ApplyFlow-Delivery-Id", deliveryId.toString());
            mailSender.send(message);
        } catch (jakarta.mail.MessagingException exception) {
            throw new IllegalStateException("Unable to compose account email", exception);
        }
    }

    private String subject(AccountTokenPurpose purpose) {
        return switch (purpose) {
            case EMAIL_VERIFICATION -> "Verify your ApplyFlow email";
            case PASSWORD_RESET -> "Reset your ApplyFlow password";
            case PASSWORD_SETUP -> "Set your ApplyFlow password";
        };
    }

    private String body(String fullName, AccountTokenPurpose purpose, String rawToken) {
        String path = switch (purpose) {
            case EMAIL_VERIFICATION -> "/verify-email?token=";
            case PASSWORD_RESET, PASSWORD_SETUP -> "/reset-password?token=";
        };
        String action = switch (purpose) {
            case EMAIL_VERIFICATION -> "verify your email";
            case PASSWORD_RESET -> "reset your password";
            case PASSWORD_SETUP -> "set a password for your account";
        };
        return "Hello " + fullName + ",\n\nUse this secure link to " + action + ":\n"
                + frontendUrl + path + rawToken
                + "\n\nIf you did not request this, you can ignore this email.";
    }
}
