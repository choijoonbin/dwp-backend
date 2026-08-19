package com.dwp.platform.contract;

import java.net.URI;
import java.time.Instant;
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
            Map<String, String> metadata) {

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

    record SendRequest(
            ConnectionContext context,
            String providerAccountReference,
            UUID idempotencyKey,
            List<String> recipients,
            String subject,
            String textBody,
            String replyToProviderMessageReference) {

        public SendRequest {
            if (context == null || idempotencyKey == null) {
                throw new IllegalArgumentException("context and idempotencyKey are required");
            }
            providerAccountReference = ContractChecks.required(
                    providerAccountReference, "providerAccountReference");
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
            if (recipients.isEmpty() || recipients.size() > 500) {
                throw new IllegalArgumentException("recipients must contain between 1 and 500 entries");
            }
            subject = ContractChecks.required(subject, "subject");
            textBody = ContractChecks.required(textBody, "textBody");
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
