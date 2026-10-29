package com.dwp.services.platform.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
class MailFollowUpDueNotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(
            MailFollowUpDueNotificationWorker.class);

    private final MailFollowUpDueNotificationTransactions transactions;
    private final MailMetrics metrics;
    private final boolean enabled;
    private final int batchSize;

    @Autowired
    MailFollowUpDueNotificationWorker(
            MailFollowUpDueNotificationTransactions transactions,
            MailMetrics metrics,
            @Value("${dwp.platform.mail.follow-up-notifications.enabled:true}") boolean enabled,
            @Value("${dwp.platform.mail.follow-up-notifications.batch-size:100}") int batchSize) {
        this.transactions = transactions;
        this.metrics = metrics;
        this.enabled = enabled;
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Mail follow-up notification batch size is invalid.");
        }
        this.batchSize = batchSize;
    }

    MailFollowUpDueNotificationWorker(
            MailFollowUpDueNotificationTransactions transactions,
            boolean enabled,
            int batchSize) {
        this(transactions, null, enabled, batchSize);
    }

    @Scheduled(fixedDelayString =
            "${dwp.platform.mail.follow-up-notifications.poll-delay-ms:30000}")
    void publishDue() {
        if (!enabled) return;
        try {
            MailFollowUpDueNotificationTransactions.BatchResult result =
                    transactions.publishDue(OffsetDateTime.now(ZoneOffset.UTC), batchSize);
            if (metrics != null) metrics.followUp(result);
        } catch (RuntimeException exception) {
            if (metrics != null) metrics.followUpFailure();
            log.error("Mail follow-up notification polling failed", exception);
        }
    }
}
