package com.dwp.services.platform.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Claims durable subscription renewal leases; provider I/O is delegated outside the claim tx. */
@Component
@ConditionalOnProperty(
        name = "dwp.platform.mail.subscriptions.enabled",
        havingValue = "true")
class MailSubscriptionRenewalWorker {

    private static final Logger log = LoggerFactory.getLogger(
            MailSubscriptionRenewalWorker.class);

    private final MailSubscriptionLedger ledger;
    private final MailSubscriptionCoordinator coordinator;
    private final MailMetrics metrics;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maximumAttempts;
    private final String workerId;

    MailSubscriptionRenewalWorker(
            MailSubscriptionLedger ledger,
            MailSubscriptionCoordinator coordinator,
            MailMetrics metrics,
            @Value("${dwp.platform.mail.subscriptions.batch-size:20}")
            int batchSize,
            @Value("${dwp.platform.mail.subscriptions.lease-seconds:60}")
            int leaseSeconds,
            @Value("${dwp.platform.mail.subscriptions.maximum-attempts:5}")
            int maximumAttempts,
            @Value("${dwp.platform.mail.subscriptions.worker-id:${HOSTNAME:local}}")
            String workerName) {
        this.ledger = ledger;
        this.coordinator = coordinator;
        this.metrics = metrics;
        this.batchSize = positive(batchSize, "batchSize");
        this.leaseSeconds = positive(leaseSeconds, "leaseSeconds");
        this.maximumAttempts = positive(maximumAttempts, "maximumAttempts");
        this.workerId = workerName + ':' + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString =
            "${dwp.platform.mail.subscriptions.poll-delay-ms:30000}")
    void renewDue() {
        try {
            int scheduled = ledger.scheduleMissingSubscriptions();
            int expired = ledger.expireDue();
            MailSubscriptionLedger.LeaseRecovery leases =
                    ledger.releaseExpiredLeases(maximumAttempts);
            metrics.subscription("scheduled", scheduled);
            metrics.subscription("expired", expired);
            metrics.subscription("retry_wait", leases.retryWait());
            metrics.subscription("failed", leases.failed());
            for (MailSubscriptionLedger.Renewal renewal
                    : ledger.claim(workerId, batchSize, leaseSeconds)) {
                MailSubscriptionCoordinator.Outcome outcome = coordinator.renew(
                        renewal, workerId, maximumAttempts);
                metrics.subscription(outcome.name().toLowerCase(
                        java.util.Locale.ROOT), 1);
            }
        } catch (RuntimeException failure) {
            metrics.subscription("poll_failed", 1);
            log.error("Mail subscription renewal poll failed", failure);
        }
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
