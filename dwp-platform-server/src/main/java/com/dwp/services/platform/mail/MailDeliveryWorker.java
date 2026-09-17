package com.dwp.services.platform.mail;

import com.dwp.platform.contract.ExecutionContext;
import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
class MailDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(MailDeliveryWorker.class);

    private final MailDeliveryRepository deliveries;
    private final MailConnectorRegistry connectors;
    private final MailDeliveryCompletionService completion;
    private final TenantMediaStorage mediaStorage;
    private final boolean enabled;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maximumAttempts;
    private final String workerId;

    MailDeliveryWorker(
            MailDeliveryRepository deliveries,
            MailConnectorRegistry connectors,
            MailDeliveryCompletionService completion,
            TenantMediaStorage mediaStorage,
            @Value("${dwp.platform.mail.delivery.enabled:true}") boolean enabled,
            @Value("${dwp.platform.mail.delivery.batch-size:25}") int batchSize,
            @Value("${dwp.platform.mail.delivery.lease-seconds:30}") int leaseSeconds,
            @Value("${dwp.platform.mail.delivery.maximum-attempts:5}") int maximumAttempts,
            @Value("${dwp.platform.mail.delivery.worker-id:${HOSTNAME:local}}") String workerName) {
        this.deliveries = deliveries;
        this.connectors = connectors;
        this.completion = completion;
        this.mediaStorage = mediaStorage;
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
        MailConnectorPort.ConnectionContext context;
        try {
            context = new MailConnectorPort.ConnectionContext(
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
        } catch (RuntimeException exception) {
            log.warn(
                    "Mail delivery readiness check failed for provider={} deliveryId={}",
                    job.providerType(), job.deliveryId(), exception);
            completion.fail(
                    job, workerId, maximumAttempts,
                    sanitize(exception.getClass().getSimpleName()), false);
            return;
        }
        MailConnectorPort.SenderMode senderMode = deliveries.authorizedSenderMode(job)
                .orElse(null);
        if (senderMode == null) {
            completion.fail(
                    job, workerId, maximumAttempts,
                    "MAIL_SEND_AUTHORIZATION_REVOKED", true);
            return;
        }
        Set<MailConnectorPort.Capability> capabilities = connector.manifest().capabilities();
        if (!capabilities.contains(MailConnectorPort.Capability.SEND)) {
            completion.fail(
                    job, workerId, maximumAttempts,
                    "MAIL_ADAPTER_SEND_NOT_SUPPORTED", true);
            return;
        }
        if (senderMode == MailConnectorPort.SenderMode.SEND_ON_BEHALF
                && !capabilities.contains(MailConnectorPort.Capability.SEND_ON_BEHALF)) {
            completion.fail(
                    job, workerId, maximumAttempts,
                    "MAIL_ADAPTER_SEND_ON_BEHALF_NOT_SUPPORTED", true);
            return;
        }
        if (!job.bccRecipients().isEmpty()
                && !capabilities.contains(MailConnectorPort.Capability.BCC)) {
            completion.fail(
                    job, workerId, maximumAttempts,
                    "MAIL_ADAPTER_BCC_NOT_SUPPORTED", true);
            return;
        }
        if ("HTML".equals(job.bodyFormat())
                && !capabilities.contains(MailConnectorPort.Capability.HTML_BODY)) {
            completion.fail(
                    job, workerId, maximumAttempts,
                    "MAIL_ADAPTER_HTML_NOT_SUPPORTED", true);
            return;
        }
        if (job.expectedAttachmentCount() > 0
                && !capabilities.contains(MailConnectorPort.Capability.ATTACHMENTS)) {
            completion.fail(
                    job, workerId, maximumAttempts,
                    "MAIL_ADAPTER_ATTACHMENTS_NOT_SUPPORTED", true);
            return;
        }
        List<MailConnectorPort.OutboundAttachment> attachments;
        try {
            attachments = outboundAttachments(job);
        } catch (AttachmentIntegrityException exception) {
            log.warn(
                    "Mail attachment integrity check failed for deliveryId={}",
                    job.deliveryId(), exception);
            completion.fail(
                    job, workerId, maximumAttempts,
                    "MAIL_ATTACHMENT_INTEGRITY_FAILED", true);
            return;
        } catch (RuntimeException | IOException exception) {
            log.warn(
                    "Mail attachment loading failed for deliveryId={}",
                    job.deliveryId(), exception);
            completion.fail(
                    job, workerId, maximumAttempts,
                    "MAIL_ATTACHMENT_LOAD_FAILED", false);
            return;
        }
        try {
            MailConnectorPort.DeliveryReceipt receipt = connector.send(
                    new MailConnectorPort.SendRequest(
                            context,
                            job.providerAccountReference(),
                            job.deliveryId(),
                            job.toRecipients(),
                            job.ccRecipients(),
                            job.bccRecipients(),
                            job.subject(),
                            job.body(),
                            MailConnectorPort.BodyFormat.valueOf(job.bodyFormat()),
                            attachments,
                            senderMode,
                            job.replyToProviderMessageReference()));
            completion.complete(job, workerId, receipt, senderMode);
        } catch (RuntimeException exception) {
            log.warn(
                    "Mail delivery attempt failed for provider={} deliveryId={}",
                    job.providerType(), job.deliveryId(), exception);
            completion.fail(
                    job, workerId, maximumAttempts,
                    "MAIL_PROVIDER_RESULT_UNKNOWN", true);
        }
    }

    private List<MailConnectorPort.OutboundAttachment> outboundAttachments(
            MailDeliveryRepository.DeliveryJob job) throws IOException {
        if (job.expectedAttachmentCount() != job.attachments().size()) {
            throw new AttachmentIntegrityException("The attachment projection is incomplete.");
        }
        List<MailConnectorPort.OutboundAttachment> result = new ArrayList<>();
        for (MailDeliveryRepository.DeliveryAttachment attachment : job.attachments()) {
            byte[] content = mediaStorage.load(
                    job.tenantId(), attachment.storageReference()).getContentAsByteArray();
            if (content.length != attachment.sizeBytes()
                    || !sha256(content).equalsIgnoreCase(attachment.checksumSha256())) {
                throw new AttachmentIntegrityException("The attachment content has changed.");
            }
            result.add(new MailConnectorPort.OutboundAttachment(
                    attachment.attachmentId(), attachment.fileName(),
                    attachment.contentType(), attachment.sizeBytes(),
                    attachment.checksumSha256(), content));
        }
        return List.copyOf(result);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
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

    private static final class AttachmentIntegrityException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private AttachmentIntegrityException(String message) {
            super(message);
        }
    }
}
