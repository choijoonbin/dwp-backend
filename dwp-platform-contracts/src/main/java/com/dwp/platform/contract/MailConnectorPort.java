package com.dwp.platform.contract;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Provider-neutral boundary implemented by Graph, Gmail, NAVER WORKS and RFC adapters. */
public interface MailConnectorPort {

    Manifest manifest();

    Readiness readiness(ConnectionContext context);

    SyncBatch synchronize(SyncRequest request);

    DeliveryReceipt send(SendRequest request);

    SubscriptionReceipt renewSubscription(SubscriptionRequest request);

    enum ProviderFamily {
        MICROSOFT_GRAPH,
        GOOGLE_GMAIL,
        NAVER_WORKS,
        JMAP,
        IMAP_SMTP,
        DWP_SANDBOX
    }

    enum Capability {
        READ,
        SEND,
        SEND_ON_BEHALF,
        BCC,
        HTML_BODY,
        ATTACHMENTS,
        THREADS,
        FOLDERS,
        LABELS,
        DELTA_SYNC,
        PUSH,
        TENANT_WIDE_AUTHORIZATION
    }

    enum ReadinessState {
        READY,
        CONFIGURATION_REQUIRED,
        AUTHENTICATION_REQUIRED,
        DEGRADED,
        UNAVAILABLE
    }

    enum BodyFormat {
        TEXT,
        HTML
    }

    enum SenderMode {
        ACCOUNT,
        SEND_AS,
        SEND_ON_BEHALF
    }

    record Manifest(
            ProviderFamily provider,
            String adapterVersion,
            String protocol,
            Set<Capability> capabilities) {

        public Manifest {
            if (provider == null) throw new IllegalArgumentException("provider is required");
            adapterVersion = ContractChecks.required(adapterVersion, "adapterVersion");
            protocol = ContractChecks.required(protocol, "protocol");
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }
    }

    record ConnectionContext(
            ExecutionContext execution,
            UUID connectionId,
            URI secretReference,
            String mailDomain) {

        public ConnectionContext {
            if (execution == null || connectionId == null) {
                throw new IllegalArgumentException("execution and connectionId are required");
            }
            if (secretReference != null && !Set.of(
                    "vault", "aws-sm", "gcp-sm", "azure-kv", "secret")
                    .contains(secretReference.getScheme())) {
                throw new IllegalArgumentException("secretReference must use an approved secret-store scheme");
            }
        }
    }

    record Readiness(
            ReadinessState state,
            Instant checkedAt,
            String errorCode,
            Instant retryAfter) {

        public Readiness {
            if (state == null || checkedAt == null) {
                throw new IllegalArgumentException("state and checkedAt are required");
            }
        }
    }

    record SyncRequest(
            ConnectionContext context,
            String providerAccountReference,
            String opaqueCursor,
            int limit) {

        public SyncRequest {
            if (context == null) throw new IllegalArgumentException("context is required");
            providerAccountReference = ContractChecks.required(
                    providerAccountReference, "providerAccountReference");
            limit = ContractChecks.limit(limit, 500);
        }
    }

    record ProviderMessage(
            String providerMessageReference,
            String providerThreadReference,
            String providerFolderReference,
            Instant occurredAt,
            String sender,
            List<String> recipients,
            String subject,
            String textBody,
            Map<String, String> metadata,
            BodyFormat bodyFormat,
            List<ProviderAttachment> attachments) {

        /** Source-compatible constructor for adapters that only expose plain-text messages. */
        public ProviderMessage(
                String providerMessageReference,
                String providerThreadReference,
                String providerFolderReference,
                Instant occurredAt,
                String sender,
                List<String> recipients,
                String subject,
                String textBody,
                Map<String, String> metadata) {
            this(providerMessageReference, providerThreadReference, providerFolderReference,
                    occurredAt, sender, recipients, subject, textBody, metadata,
                    BodyFormat.TEXT, List.of());
        }

        /** Preferred constructor for typed body content and provider attachment snapshots. */
        public ProviderMessage(
                String providerMessageReference,
                String providerThreadReference,
                String providerFolderReference,
                Instant occurredAt,
                String sender,
                List<String> recipients,
                String subject,
                String body,
                BodyFormat bodyFormat,
                List<ProviderAttachment> attachments,
                Map<String, String> metadata) {
            this(providerMessageReference, providerThreadReference, providerFolderReference,
                    occurredAt, sender, recipients, subject, body, metadata,
                    bodyFormat, attachments);
        }

        public ProviderMessage {
            providerMessageReference = ContractChecks.required(
                    providerMessageReference, "providerMessageReference");
            providerThreadReference = ContractChecks.required(
                    providerThreadReference, "providerThreadReference");
            providerFolderReference = ContractChecks.required(
                    providerFolderReference, "providerFolderReference");
            if (occurredAt == null) throw new IllegalArgumentException("occurredAt is required");
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            bodyFormat = bodyFormat == null ? BodyFormat.TEXT : bodyFormat;
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        /** Body content in the declared {@link #bodyFormat()}. */
        public String body() {
            return textBody;
        }
    }

    record ProviderAttachment(
            String providerAttachmentReference,
            String contentReference,
            String fileName,
            String contentType,
            long sizeBytes,
            String checksumSha256,
            byte[] content,
            Map<String, String> metadata) {

        public ProviderAttachment {
            providerAttachmentReference = ContractChecks.required(
                    providerAttachmentReference, "providerAttachmentReference");
            fileName = ContractChecks.required(fileName, "fileName");
            contentType = ContractChecks.required(contentType, "contentType");
            if (sizeBytes < 1) {
                throw new IllegalArgumentException("sizeBytes must be positive");
            }
            contentReference = contentReference == null || contentReference.isBlank()
                    ? null : contentReference.trim();
            checksumSha256 = checksumSha256 == null || checksumSha256.isBlank()
                    ? null : ContractChecks.sha256(checksumSha256, "checksumSha256");
            content = content == null ? null : content.clone();
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            if (content != null) {
                if (sizeBytes != content.length) {
                    throw new IllegalArgumentException("sizeBytes must match content length");
                }
                if (checksumSha256 == null) {
                    throw new IllegalArgumentException(
                            "checksumSha256 is required when content is supplied");
                }
                if (!checksumSha256.equals(sha256(content))) {
                    throw new IllegalArgumentException("checksumSha256 must match content");
                }
            }
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }

        public boolean contentAvailable() {
            return content != null;
        }

        private static String sha256(byte[] content) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(content));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }
    }

    record SyncBatch(
            List<ProviderMessage> messages,
            String nextCursor,
            boolean cursorResetRequired,
            boolean partial) {

        public SyncBatch {
            messages = messages == null ? List.of() : List.copyOf(messages);
            nextCursor = ContractChecks.required(nextCursor, "nextCursor");
        }
    }

    record OutboundAttachment(
            UUID attachmentId,
            String fileName,
            String contentType,
            long sizeBytes,
            String checksumSha256,
            byte[] content) {

        public OutboundAttachment {
            if (attachmentId == null) {
                throw new IllegalArgumentException("attachmentId is required");
            }
            fileName = ContractChecks.required(fileName, "fileName");
            contentType = ContractChecks.required(contentType, "contentType");
            checksumSha256 = ContractChecks.sha256(checksumSha256, "checksumSha256");
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException("content must not be empty");
            }
            content = content.clone();
            if (sizeBytes != content.length) {
                throw new IllegalArgumentException("sizeBytes must match content length");
            }
            if (!checksumSha256.equals(sha256(content))) {
                throw new IllegalArgumentException("checksumSha256 must match content");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        private static String sha256(byte[] content) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(content));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }
    }

    record SendRequest(
            ConnectionContext context,
            String providerAccountReference,
            UUID idempotencyKey,
            List<String> toRecipients,
            List<String> ccRecipients,
            List<String> bccRecipients,
            String subject,
            String body,
            BodyFormat bodyFormat,
            List<OutboundAttachment> attachments,
            SenderMode senderMode,
            String replyToProviderMessageReference) {

        public SendRequest(
                ConnectionContext context,
                String providerAccountReference,
                UUID idempotencyKey,
                List<String> toRecipients,
                List<String> ccRecipients,
                List<String> bccRecipients,
                String subject,
                String body,
                BodyFormat bodyFormat,
                List<OutboundAttachment> attachments,
                String replyToProviderMessageReference) {
            this(context, providerAccountReference, idempotencyKey,
                    toRecipients, ccRecipients, bccRecipients, subject, body,
                    bodyFormat, attachments, SenderMode.ACCOUNT,
                    replyToProviderMessageReference);
        }

        public SendRequest(
                ConnectionContext context,
                String providerAccountReference,
                UUID idempotencyKey,
                List<String> recipients,
                String subject,
                String textBody,
                String replyToProviderMessageReference) {
            this(context, providerAccountReference, idempotencyKey,
                    recipients, List.of(), List.of(), subject, textBody,
                    BodyFormat.TEXT, List.of(),
                    SenderMode.ACCOUNT,
                    replyToProviderMessageReference);
        }

        public SendRequest(
                ConnectionContext context,
                String providerAccountReference,
                UUID idempotencyKey,
                List<String> toRecipients,
                List<String> ccRecipients,
                List<String> bccRecipients,
                String subject,
                String textBody,
                String replyToProviderMessageReference) {
            this(context, providerAccountReference, idempotencyKey,
                    toRecipients, ccRecipients, bccRecipients, subject, textBody,
                    BodyFormat.TEXT, List.of(),
                    SenderMode.ACCOUNT,
                    replyToProviderMessageReference);
        }

        public SendRequest {
            if (context == null || idempotencyKey == null) {
                throw new IllegalArgumentException("context and idempotencyKey are required");
            }
            providerAccountReference = ContractChecks.required(
                    providerAccountReference, "providerAccountReference");
            toRecipients = toRecipients == null ? List.of() : List.copyOf(toRecipients);
            ccRecipients = ccRecipients == null ? List.of() : List.copyOf(ccRecipients);
            bccRecipients = bccRecipients == null ? List.of() : List.copyOf(bccRecipients);
            int recipientCount = toRecipients.size() + ccRecipients.size() + bccRecipients.size();
            if (recipientCount < 1 || recipientCount > 500) {
                throw new IllegalArgumentException("recipients must contain between 1 and 500 entries");
            }
            subject = ContractChecks.required(subject, "subject");
            body = ContractChecks.required(body, "body");
            if (bodyFormat == null) {
                throw new IllegalArgumentException("bodyFormat is required");
            }
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            if (senderMode == null) {
                throw new IllegalArgumentException("senderMode is required");
            }
        }
    }

    record DeliveryReceipt(
            String providerMessageReference,
            String providerThreadReference,
            Instant acceptedAt) {

        public DeliveryReceipt {
            providerMessageReference = ContractChecks.required(
                    providerMessageReference, "providerMessageReference");
            providerThreadReference = ContractChecks.required(
                    providerThreadReference, "providerThreadReference");
            if (acceptedAt == null) throw new IllegalArgumentException("acceptedAt is required");
        }
    }

    record SubscriptionRequest(
            ConnectionContext context,
            URI callback,
            Instant requestedUntil) {

        public SubscriptionRequest {
            if (context == null || callback == null || requestedUntil == null) {
                throw new IllegalArgumentException("context, callback and requestedUntil are required");
            }
            if (!"https".equalsIgnoreCase(callback.getScheme())) {
                throw new IllegalArgumentException("callback must use HTTPS");
            }
        }
    }

    record SubscriptionReceipt(
            String providerSubscriptionReference,
            Instant expiresAt) {

        public SubscriptionReceipt {
            providerSubscriptionReference = ContractChecks.required(
                    providerSubscriptionReference, "providerSubscriptionReference");
            if (expiresAt == null) throw new IllegalArgumentException("expiresAt is required");
        }
    }
}
