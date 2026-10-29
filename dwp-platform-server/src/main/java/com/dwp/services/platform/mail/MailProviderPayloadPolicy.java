package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Bounds untrusted provider material before scanning, storage, or database work begins. */
@Component
class MailProviderPayloadPolicy {

    private final int maximumMessages;
    private final int maximumRecipients;
    private final long maximumBodyBytes;
    private final int maximumMetadataEntries;
    private final long maximumMetadataBytes;
    private final int maximumAttachments;
    private final long maximumAttachmentBytes;

    MailProviderPayloadPolicy(
            @Value("${dwp.platform.mail.provider-payload.maximum-messages:500}")
            int maximumMessages,
            @Value("${dwp.platform.mail.provider-payload.maximum-recipients:500}")
            int maximumRecipients,
            @Value("${dwp.platform.mail.provider-payload.maximum-body-bytes:10485760}")
            long maximumBodyBytes,
            @Value("${dwp.platform.mail.provider-payload.maximum-metadata-entries:100}")
            int maximumMetadataEntries,
            @Value("${dwp.platform.mail.provider-payload.maximum-metadata-bytes:65536}")
            long maximumMetadataBytes,
            @Value("${dwp.platform.mail.provider-payload.maximum-attachments:100}")
            int maximumAttachments,
            @Value("${dwp.platform.mail.provider-payload.maximum-attachment-bytes:157286400}")
            long maximumAttachmentBytes) {
        this.maximumMessages = positive(maximumMessages, "maximumMessages");
        this.maximumRecipients = positive(maximumRecipients, "maximumRecipients");
        this.maximumBodyBytes = positive(maximumBodyBytes, "maximumBodyBytes");
        this.maximumMetadataEntries = positive(maximumMetadataEntries, "maximumMetadataEntries");
        this.maximumMetadataBytes = positive(maximumMetadataBytes, "maximumMetadataBytes");
        this.maximumAttachments = positive(maximumAttachments, "maximumAttachments");
        this.maximumAttachmentBytes = positive(maximumAttachmentBytes, "maximumAttachmentBytes");
    }

    static MailProviderPayloadPolicy defaults() {
        return new MailProviderPayloadPolicy(
                500, 500, 10L * 1024L * 1024L,
                100, 64L * 1024L, 100, 150L * 1024L * 1024L);
    }

    void validate(MailConnectorPort.SyncBatch batch) {
        if (batch == null) fail("PROVIDER_BATCH_INVALID");
        if (batch.messages().size() > maximumMessages) {
            fail("PROVIDER_BATCH_MESSAGE_LIMIT_EXCEEDED");
        }
        long recipientCount = 0;
        long bodyBytes = 0;
        long metadataEntries = 0;
        long metadataBytes = 0;
        long attachmentCount = 0;
        long attachmentBytes = 0;
        for (MailConnectorPort.ProviderMessage message : batch.messages()) {
            recipientCount = boundedAdd(
                    recipientCount, message.recipients().size(), maximumRecipients,
                    "PROVIDER_RECIPIENT_LIMIT_EXCEEDED");
            bodyBytes = boundedAdd(
                    bodyBytes, bytes(message.body()), maximumBodyBytes,
                    "PROVIDER_BODY_LIMIT_EXCEEDED");
            MetadataSize messageMetadata = metadataSize(message.metadata());
            metadataEntries = boundedAdd(
                    metadataEntries, messageMetadata.entries(), maximumMetadataEntries,
                    "PROVIDER_METADATA_LIMIT_EXCEEDED");
            metadataBytes = boundedAdd(
                    metadataBytes, messageMetadata.bytes(), maximumMetadataBytes,
                    "PROVIDER_METADATA_LIMIT_EXCEEDED");
            attachmentCount = boundedAdd(
                    attachmentCount, message.attachments().size(), maximumAttachments,
                    "PROVIDER_ATTACHMENT_COUNT_EXCEEDED");
            for (MailConnectorPort.ProviderAttachment attachment : message.attachments()) {
                attachmentBytes = boundedAdd(
                        attachmentBytes, attachment.sizeBytes(), maximumAttachmentBytes,
                        "PROVIDER_ATTACHMENT_BYTES_EXCEEDED");
                MetadataSize attachmentMetadata = metadataSize(attachment.metadata());
                metadataEntries = boundedAdd(
                        metadataEntries, attachmentMetadata.entries(), maximumMetadataEntries,
                        "PROVIDER_METADATA_LIMIT_EXCEEDED");
                metadataBytes = boundedAdd(
                        metadataBytes, attachmentMetadata.bytes(), maximumMetadataBytes,
                        "PROVIDER_METADATA_LIMIT_EXCEEDED");
            }
        }
    }

    private MetadataSize metadataSize(Map<String, String> metadata) {
        long total = 0;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            try {
                total = Math.addExact(total, bytes(entry.getKey()));
                total = Math.addExact(total, bytes(entry.getValue()));
            } catch (ArithmeticException overflow) {
                fail("PROVIDER_METADATA_LIMIT_EXCEEDED");
            }
        }
        return new MetadataSize(metadata.size(), total);
    }

    private long boundedAdd(long current, long added, long maximum, String code) {
        long total;
        try {
            total = Math.addExact(current, added);
        } catch (ArithmeticException overflow) {
            fail(code);
            return -1;
        }
        if (total > maximum) fail(code);
        return total;
    }

    private long bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private void fail(String code) {
        throw new MailExternalSyncMaterializer.SyncFailure(code);
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private record MetadataSize(long entries, long bytes) { }
}
