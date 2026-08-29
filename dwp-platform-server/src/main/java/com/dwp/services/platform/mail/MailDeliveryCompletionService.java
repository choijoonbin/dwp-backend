package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
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

    MailDeliveryCompletionService(
            MailDeliveryRepository deliveries,
            MailCommandRepository commands) {
        this.deliveries = deliveries;
        this.commands = commands;
    }

    @Transactional
    void complete(
            MailDeliveryRepository.DeliveryJob job,
            String workerId,
            MailConnectorPort.DeliveryReceipt receipt) {
        if (deliveries.markDelivered(job, workerId, receipt) == 0) return;
        if (job.providerType() == ProviderType.DWP_SANDBOX) {
            deliveries.mirrorSandboxDelivery(job, receipt);
        }
        OffsetDateTime acceptedAt = OffsetDateTime.ofInstant(
                receipt.acceptedAt(), ZoneOffset.UTC);
        commands.audit(
                job.tenantId(), job.createdBy(), "mail.message.sent", "MAIL_MESSAGE",
                job.messageId().toString(), correlation(job),
                Map.of("deliveryState", "SENDING", "attempt", job.attemptCount()),
                Map.of(
                        "deliveryState", "SENT",
                        "attempt", job.attemptCount(),
                        "providerType", job.providerType().name(),
                        "acceptedAt", acceptedAt.toString()));
        commands.domainEvent(
                job.tenantId(), "MAIL_MESSAGE", job.messageId(), "mail.message.sent",
                Map.of(
                        "messageId", job.messageId(),
                        "threadId", job.threadId(),
                        "providerType", job.providerType().name(),
                        "acceptedAt", acceptedAt.toString()),
                correlation(job));
    }

    @Transactional
    void fail(
            MailDeliveryRepository.DeliveryJob job,
            String workerId,
            int maximumAttempts,
            String errorCode,
            boolean permanent) {
        boolean terminal = permanent || job.attemptCount() >= maximumAttempts;
        OffsetDateTime nextAttempt = terminal
                ? null
                : OffsetDateTime.now().plusSeconds(backoffSeconds(job.attemptCount()));
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
                    job.tenantId(), "MAIL_MESSAGE", job.messageId(),
                    "mail.message.delivery.failed",
                    Map.of(
                            "messageId", job.messageId(),
                            "threadId", job.threadId(),
                            "providerType", job.providerType().name(),
                            "errorCode", errorCode),
                    correlation(job));
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
}
