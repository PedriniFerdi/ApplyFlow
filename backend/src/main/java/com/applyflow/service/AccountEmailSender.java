package com.applyflow.service;

import java.util.UUID;

import com.applyflow.entity.AccountTokenPurpose;

public interface AccountEmailSender {
    void sendAccountLink(String email, String fullName, AccountTokenPurpose purpose, String rawToken, UUID deliveryId);
}
