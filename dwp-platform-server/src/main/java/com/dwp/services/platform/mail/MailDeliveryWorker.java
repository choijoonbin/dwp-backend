package com.dwp.services.platform.mail;

import com.dwp.platform.contract.ExecutionContext;
import com.dwp.platform.contract.MailConnectorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
class MailDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(MailDeliveryWorker.class);

    private final MailDeliveryRepository deliveries;
    private final MailConnectorRegistry connectors;
    private final MailDeliveryCompletionService completion;
    private final boolean enabled;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maximumAttempts;
    private final String workerId;

    MailDeliveryWorker(
            MailDeliveryRepository deliveries,
            MailConnectorRegistry connectors,
            MailDeliveryCompletionService completion,
            @Value("${dwp.platform.mail.delivery.enabled:true}") boolean enabled,
            @Value("${dwp.platform.mail.delivery.batch-size:25}") int batchSize,
            @Value("${dwp.platform.mail.delivery.lease-seconds:30}") int leaseSeconds,
            @Value("${dwp.platform.mail.delivery.maximum-attempts:5}") int maximumAttempts,
            @Value("${dwp.platform.mail.delivery.worker-id:${HOSTNAME:local}}") String workerName) {
        this.deliveries = deliveries;
        this.connectors = connectors;
        this.completion = completion;
        this.enabled = enabled;
        this.batchSize = positive(batchSize, "batchSize");
        this.leaseSeconds = positive(leaseSeconds, "leaseSeconds");
        this.maximumAttempts = positive(maximumAttempts, "maximumAttempts");
        this.workerId = workerName + ':' + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${dwp.platform.mail.delivery.poll-delay-ms:1000}")
    void deliverPending() {
        if (!enabled) return;
        try {
            deliveries.releaseExpiredLeases();
            for (MailDeliveryRepository.DeliveryJob job
                    : deliveries.claim(workerId, batchSize, leaseSeconds)) {
                deliver(job);
            }
        } catch (RuntimeException exception) {
            log.error("Mail delivery polling failed", exception);
        }
    }

    private void deliver(MailDeliveryRepository.DeliveryJob job) {
        MailConnectorPort connector = connectors.connector(job.providerType()).orElse(null);
        if (connector == null) {
            completion.fail(
                    job, workerId, maximumAttempts, "MAIL_ADAPTER_NOT_DEPLOYED", true);
            return;
        }
        try {
            MailConnectorPort.ConnectionContext context = new MailConnectorPort.ConnectionContext(
                    new ExecutionContext(
                            job.tenantId().toString(), job.createdBy().toString(), Set.of(),
                            correlation(job)),
                    job.connectionId(), job.credentialReference(), job.mailDomain());
            MailConnectorPort.Readiness readiness = connector.readiness(context);
            if (readiness.state() != MailConnectorPort.ReadinessState.READY) {
                completion.fail(
                        job, workerId, maximumAttempts,
                        readiness.errorCode() == null
                                ? "MAIL_ADAPTER_" + readiness.state().name()
                                : sanitize(readiness.errorCode()),
                        readiness.state() == MailConnectorPort.ReadinessState.CONFIGURATION_REQUIRED
                                || readiness.state()
                                == MailConnectorPort.ReadinessState.AUTHENTICATION_REQUIRED);
                return;
            }
            MailConnectorPort.DeliveryReceipt receipt = connector.send(
                    new MailConnectorPort.SendRequest(
                            context,
                            job.providerAccountReference(),
                            job.idempotencyKey(),
                            job.recipients(),
                            job.subject(),
                            job.body(),
                            job.replyToProviderMessageReference()));
            completion.complete(job, workerId, receipt);
        } catch (RuntimeException exception) {
            log.warn(
                    "Mail delivery attempt failed for provider={} deliveryId={}",
                    job.providerType(), job.deliveryId(), exception);
            completion.fail(
                    job, workerId, maximumAttempts,
                    sanitize(exception.getClass().getSimpleName()), false);
        }
    }

    private String correlation(MailDeliveryRepository.DeliveryJob job) {
        return job.correlationId() == null || job.correlationId().isBlank()
                ? "mail-delivery:" + job.deliveryId()
                : job.correlationId();
    }

    private String sanitize(String value) {
        String normalized = value == null ? "MAIL_DELIVERY_FAILED"
                : value.replaceAll("[^A-Za-z0-9_.-]", "_").toUpperCase();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private int positive(int value, String field) {
        if (value < 1) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }
}
