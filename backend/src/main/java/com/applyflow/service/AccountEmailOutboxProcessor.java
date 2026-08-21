package com.applyflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.mail.outbox.scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AccountEmailOutboxProcessor {

    private final AccountEmailOutboxService outboxService;
    private final boolean deliveryEnabled;

    public AccountEmailOutboxProcessor(AccountEmailOutboxService outboxService,
            @Value("${app.mail.delivery-enabled}") boolean deliveryEnabled) {
        this.outboxService = outboxService;
        this.deliveryEnabled = deliveryEnabled;
    }

    @Scheduled(fixedDelayString = "${app.mail.outbox.retry-interval}")
    public void deliverPending() {
        if (!deliveryEnabled) {
            return;
        }
        while (outboxService.deliverNext()) {
            // Drain currently due messages. Each item has its own transaction and retry state.
        }
    }
}
