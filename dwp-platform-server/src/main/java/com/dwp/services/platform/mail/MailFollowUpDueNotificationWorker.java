package com.dwp.services.platform.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
class MailFollowUpDueNotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(
            MailFollowUpDueNotificationWorker.class);

    private final MailFollowUpDueNotificationTransactions transactions;
    private final boolean enabled;
    private final int batchSize;

    MailFollowUpDueNotificationWorker(
            MailFollowUpDueNotificationTransactions transactions,
            @Value("${dwp.platform.mail.follow-up-notifications.enabled:true}") boolean enabled,
            @Value("${dwp.platform.mail.follow-up-notifications.batch-size:100}") int batchSize) {
        this.transactions = transactions;
        this.enabled = enabled;
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Mail follow-up notification batch size is invalid.");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString =
            "${dwp.platform.mail.follow-up-notifications.poll-delay-ms:30000}")
    void publishDue() {
        if (!enabled) return;
        try {
            transactions.publishDue(OffsetDateTime.now(ZoneOffset.UTC), batchSize);
        } catch (RuntimeException exception) {
            log.error("Mail follow-up notification polling failed", exception);
        }
    }
}
