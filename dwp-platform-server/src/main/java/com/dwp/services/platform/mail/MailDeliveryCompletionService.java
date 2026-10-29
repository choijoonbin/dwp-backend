package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static com.dwp.services.platform.mail.MailTypes.ProviderType;

@Service
class MailDeliveryCompletionService {

    private final MailDeliveryRepository deliveries;
    private final MailCommandRepository commands;
    private final MailInboundMessageService inboundMessages;
    private final MailMetrics metrics;

    @Autowired
    MailDeliveryCompletionService(
            MailDeliveryRepository deliveries,
            MailCommandRepository commands,
            MailInboundMessageService inboundMessages,
            MailMetrics metrics) {
        this.deliveries = deliveries;
        this.commands = commands;
        this.inboundMessages = inboundMessages;
        this.metrics = metrics;
    }

    MailDeliveryCompletionService(
            MailDeliveryRepository deliveries,
            MailCommandRepository commands,
            MailInboundMessageService inboundMessages) {
        this(deliveries, commands, inboundMessages, null);
    }

    @Transactional
    void complete(
            MailDeliveryRepository.DeliveryJob job,
            String workerId,
            MailConnectorPort.DeliveryReceipt receipt) {
        complete(job, workerId, receipt, MailConnectorPort.SenderMode.ACCOUNT);
    }

    @Transactional
    void complete(
            MailDeliveryRepository.DeliveryJob job,
            String workerId,
            MailConnectorPort.DeliveryReceipt receipt,
            MailConnectorPort.SenderMode senderMode) {
        if (deliveries.markDelivered(job, workerId, receipt) == 0) return;
        if (job.providerType() == ProviderType.DWP_SANDBOX
                && receipt.outcome() == MailConnectorPort.DeliveryOutcome.DELIVERED) {
            for (MailDeliveryRepository.InboundMessage inbound
                    : deliveries.mirrorSandboxDelivery(job, receipt, senderMode)) {
                inboundMessages.inboundMessageMaterialized(
                        job.tenantId(), inbound.threadId(), inbound.messageId(),
                        inbound.receivedAt());
            }
        }
        OffsetDateTime acceptedAt = receipt.acceptedAt() == null ? null
                : OffsetDateTime.ofInstant(receipt.acceptedAt(), ZoneOffset.UTC);
        String deliveryState = receipt.outcome().name();
        String eventType = switch (receipt.outcome()) {
            case DELIVERED -> "mail.message.delivered";
            case PROVIDER_ACCEPTED -> "mail.message.provider-accepted";
            case RESULT_UNKNOWN -> "mail.message.delivery.reconciliation-required";
        };
        Map<String, Object> outcome = new java.util.LinkedHashMap<>();
        outcome.put("deliveryState", deliveryState);
        outcome.put("attempt", job.attemptCount());
        outcome.put("providerType", job.providerType().name());
        if (acceptedAt != null) outcome.put("acceptedAt", acceptedAt.toString());
        commands.audit(
                job.tenantId(), job.createdBy(), eventType, "MAIL_MESSAGE",
                job.messageId().toString(), correlation(job),
                Map.of("deliveryState", "SENDING", "attempt", job.attemptCount()),
                Map.copyOf(outcome));
        commands.domainEvent(
                job.tenantId(), "MAIL_THREAD", job.threadId(), eventType,
                eventPayload(job, deliveryState, acceptedAt),
                correlation(job));
        if (metrics != null) {
            metrics.delivery(job.providerType(), deliveryState.toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Transactional
    void fail(
            MailDeliveryRepository.DeliveryJob job,
            String workerId,
            int maximumAttempts,
            String errorCode,
            boolean permanent) {
        fail(job, workerId, maximumAttempts, errorCode, permanent, null);
    }

    @Transactional
    void fail(
            MailDeliveryRepository.DeliveryJob job,
            String workerId,
            int maximumAttempts,
            String errorCode,
            boolean permanent,
            java.time.Instant providerRetryAfter) {
        boolean terminal = permanent || job.attemptCount() >= maximumAttempts;
        OffsetDateTime nextAttempt = terminal
                ? null
                : providerRetryAfter == null
                    ? OffsetDateTime.now().plusSeconds(backoffSeconds(job.attemptCount()))
                    : OffsetDateTime.ofInstant(providerRetryAfter, ZoneOffset.UTC);
        String status = terminal ? "FAILED" : "RETRY_WAIT";
        if (deliveries.markFailed(
                job, workerId, status, errorCode, nextAttempt) == 0) return;
        if (terminal) {
            commands.audit(
                    job.tenantId(), job.createdBy(), "mail.message.delivery.failed",
                    "MAIL_MESSAGE", job.messageId().toString(), correlation(job),
                    Map.of("deliveryState", "SENDING", "attempt", job.attemptCount()),
                    Map.of(
                            "deliveryState", "FAILED",
                            "attempt", job.attemptCount(),
                            "providerType", job.providerType().name(),
                            "errorCode", errorCode));
            commands.domainEvent(
                    job.tenantId(), "MAIL_THREAD", job.threadId(),
                    "mail.message.delivery.failed",
                    Map.of(
                            "messageId", job.messageId(),
                            "threadId", job.threadId(),
                            "providerType", job.providerType().name(),
                            "errorCode", errorCode),
                    correlation(job));
        }
        if (metrics != null) {
            metrics.delivery(job.providerType(), terminal ? "failed" : "retry_wait");
        }
    }

    @Transactional
    boolean retry(Long tenantId, Long userId, java.util.UUID threadId, java.util.UUID messageId,
                  String correlationId) {
        if (deliveries.retry(tenantId, userId, threadId, messageId) == 0) return false;
        commands.audit(
                tenantId, userId, "mail.message.delivery.retried", "MAIL_MESSAGE",
                messageId.toString(), correlationId, Map.of("deliveryState", "FAILED"),
                Map.of("deliveryState", "QUEUED"));
        return true;
    }

    private long backoffSeconds(int attempt) {
        return Math.min(300L, 5L * (1L << Math.min(6, Math.max(0, attempt - 1))));
    }

    private String correlation(MailDeliveryRepository.DeliveryJob job) {
        return job.correlationId() == null || job.correlationId().isBlank()
                ? "mail-delivery:" + job.deliveryId()
                : job.correlationId();
    }

    private Map<String, Object> eventPayload(
            MailDeliveryRepository.DeliveryJob job,
            String deliveryState,
            OffsetDateTime acceptedAt) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("messageId", job.messageId());
        payload.put("threadId", job.threadId());
        payload.put("providerType", job.providerType().name());
        payload.put("deliveryState", deliveryState);
        if (acceptedAt != null) payload.put("acceptedAt", acceptedAt.toString());
        return Map.copyOf(payload);
    }
}
