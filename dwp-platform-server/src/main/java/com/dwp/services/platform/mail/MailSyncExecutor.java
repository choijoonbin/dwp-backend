package com.dwp.services.platform.mail;

import com.dwp.platform.contract.ExecutionContext;
import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.platform.contract.MailSyncProviderException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Performs provider I/O without an ambient database transaction. */
@Component
class MailSyncExecutor {

    private static final int MAXIMUM_PAGES_PER_ACCOUNT = 20;

    private final AdminMailCompletionRepository repository;
    private final MailConnectorRegistry connectors;
    private final MailExternalSyncMaterializer materializer;
    private final MailSyncJobRepository syncJobs;

    @Autowired
    MailSyncExecutor(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectors,
            MailExternalSyncMaterializer materializer,
            MailSyncJobRepository syncJobs) {
        this.repository = repository;
        this.connectors = connectors;
        this.materializer = materializer;
        this.syncJobs = syncJobs;
    }

    MailSyncExecutor(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectors,
            MailExternalSyncMaterializer materializer) {
        this(repository, connectors, materializer, null);
    }

    ExecutionResult execute(MailSyncJobRepository.SyncJob job) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new SyncWorkFailure("SYNC_NETWORK_TRANSACTION_ACTIVE", true);
        }
        AdminMailCompletionRepository.ConnectionRow connection = repository
                .connection(job.tenantId(), job.connectionId())
                .orElseThrow(() -> new SyncWorkFailure("SYNC_CONNECTION_UNAVAILABLE", true));
        if (connection.version() != job.connectionVersion()) {
            throw new SyncWorkFailure("CONNECTION_VERSION_CONFLICT", true);
        }
        MailTypes.ProviderType provider;
        try {
            provider = MailTypes.ProviderType.valueOf(connection.providerType());
        } catch (IllegalArgumentException invalid) {
            throw new SyncWorkFailure("PROVIDER_TYPE_UNSUPPORTED", true);
        }
        MailConnectorPort connector = connectors.connector(provider)
                .orElseThrow(() -> new SyncWorkFailure(
                        "CONNECTOR_ADAPTER_UNAVAILABLE", false));
        MailConnectorPort.ConnectionContext context;
        try {
            context = new MailConnectorPort.ConnectionContext(
                    new ExecutionContext(
                            Long.toString(job.tenantId()), Long.toString(job.actorId()),
                            Set.of("ADMIN.MAIL:MANAGE"), correlation(job.correlationId())),
                    connection.id(), connection.secretReference(), connection.mailDomain());
        } catch (RuntimeException invalidSecret) {
            throw new SyncWorkFailure("CREDENTIAL_REFERENCE_INVALID", true);
        }
        List<AdminMailCompletionRepository.AccountRow> accounts = repository
                .connectionAccounts(job.tenantId(), connection.id());
        if (accounts.isEmpty()) {
            throw new SyncWorkFailure("NO_ACTIVE_ACCOUNT", true);
        }
        int batches = 0;
        int inserted = 0;
        int duplicates = 0;
        int blocked = 0;
        int accountsProcessed = 0;
        for (AdminMailCompletionRepository.AccountRow account : accounts) {
            accountsProcessed++;
            AdminMailCompletionRepository.AccountRow pageAccount = account;
            boolean complete = false;
            for (int page = 0; page < MAXIMUM_PAGES_PER_ACCOUNT; page++) {
                MailConnectorPort.SyncBatch batch;
                try {
                    batch = connector.synchronize(new MailConnectorPort.SyncRequest(
                            context, pageAccount.providerAccountRef(),
                            pageAccount.cursor(), 500));
                } catch (MailSyncProviderException providerFailure) {
                    throw providerFailure(job, pageAccount, providerFailure);
                } catch (RuntimeException providerFailure) {
                    throw new SyncWorkFailure("SYNC_PROVIDER_UNAVAILABLE", false);
                }
                if (batch.cursorResetRequired()) {
                    throw resetFailure(job, pageAccount, batch.nextCursor());
                }
                MailExternalSyncMaterializer.SyncResult result;
                try {
                    result = materializer.materialize(
                            job.tenantId(), job.actorId(), pageAccount, batch);
                } catch (MailExternalSyncMaterializer.SyncFailure failure) {
                    boolean terminal = Set.of(
                            "PROVIDER_BATCH_INVALID",
                            "PROVIDER_BATCH_MESSAGE_LIMIT_EXCEEDED",
                            "PROVIDER_RECIPIENT_LIMIT_EXCEEDED",
                            "PROVIDER_BODY_LIMIT_EXCEEDED",
                            "PROVIDER_METADATA_LIMIT_EXCEEDED",
                            "PROVIDER_ATTACHMENT_COUNT_EXCEEDED",
                            "PROVIDER_ATTACHMENT_BYTES_EXCEEDED",
                            "SYNC_CURSOR_RESET_REQUIRED").contains(failure.code());
                    throw new SyncWorkFailure(failure.code(), terminal);
                }
                batches++;
                inserted += result.inserted();
                duplicates += result.duplicate();
                blocked += result.blockedAttachments();
                if (!batch.partial()) {
                    complete = true;
                    break;
                }
                if (Objects.equals(pageAccount.cursor(), batch.nextCursor())) {
                    throw new SyncWorkFailure("SYNC_CURSOR_DID_NOT_ADVANCE", true);
                }
                pageAccount = new AdminMailCompletionRepository.AccountRow(
                        pageAccount.id(), pageAccount.email(),
                        pageAccount.providerAccountRef(), batch.nextCursor(),
                        pageAccount.cursorGeneration() + 1);
            }
            if (!complete) {
                return new ExecutionResult(
                        accountsProcessed, batches, inserted, duplicates, blocked, true);
            }
        }
        return new ExecutionResult(
                accounts.size(), batches, inserted, duplicates, blocked, false);
    }

    private SyncWorkFailure providerFailure(
            MailSyncJobRepository.SyncJob job,
            AdminMailCompletionRepository.AccountRow account,
            MailSyncProviderException failure) {
        return switch (failure.category()) {
            case AUTHENTICATION -> new SyncWorkFailure(
                    failure.errorCode(), true, "AUTHENTICATION", null, Map.of());
            case RATE_LIMIT -> new SyncWorkFailure(
                    failure.errorCode(), false, "RATE_LIMIT",
                    java.time.OffsetDateTime.ofInstant(
                            failure.retryAfter(), java.time.ZoneOffset.UTC), Map.of());
            case CURSOR_RESET -> resetFailure(job, account, failure.resetCursor());
            case TRANSIENT -> new SyncWorkFailure(
                    failure.errorCode(), false, "TRANSIENT", null, Map.of());
            case PERMANENT -> new SyncWorkFailure(
                    failure.errorCode(), true, "PERMANENT", null, Map.of());
        };
    }

    private SyncWorkFailure resetFailure(
            MailSyncJobRepository.SyncJob job,
            AdminMailCompletionRepository.AccountRow account,
            String resetCursor) {
        if ("RECOVERY".equals(job.triggerKind())) {
            return new SyncWorkFailure(
                    "SYNC_CURSOR_RESET_REPEATED", true,
                    "CURSOR_RESET", null, Map.of());
        }
        if (syncJobs == null) {
            return new SyncWorkFailure("SYNC_CURSOR_RESET_REQUIRED", true);
        }
        MailSyncJobRepository.ResetTransition transition =
                syncJobs.resetCursorAndEnqueueRecovery(job, account, resetCursor);
        return new SyncWorkFailure(
                "SYNC_CURSOR_RESET_REQUIRED", true, "CURSOR_RESET",
                null, transition.evidence());
    }

    private String correlation(String value) {
        return value == null || value.isBlank() ? "mail-sync-worker" : value;
    }

    record ExecutionResult(
            int accounts,
            int batches,
            int insertedMessages,
            int duplicateMessages,
            int blockedAttachments,
            boolean continuationRequired) {

        ExecutionResult(
                int accounts,
                int batches,
                int insertedMessages,
                int duplicateMessages,
                int blockedAttachments) {
            this(accounts, batches, insertedMessages, duplicateMessages,
                    blockedAttachments, false);
        }
    }

    static final class SyncWorkFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final boolean terminal;
        private final String classification;
        private final java.time.OffsetDateTime retryAt;
        private final transient Map<String, Object> resetEvidence;

        SyncWorkFailure(String code, boolean terminal) {
            this(code, terminal, terminal ? "PERMANENT" : "TRANSIENT", null, Map.of());
        }

        SyncWorkFailure(
                String code,
                boolean terminal,
                String classification,
                java.time.OffsetDateTime retryAt,
                Map<String, Object> resetEvidence) {
            super(code);
            this.code = code;
            this.terminal = terminal;
            this.classification = classification;
            this.retryAt = retryAt;
            this.resetEvidence = resetEvidence == null ? Map.of() : Map.copyOf(resetEvidence);
        }

        String code() {
            return code;
        }

        boolean terminal() {
            return terminal;
        }

        String classification() {
            return classification;
        }

        java.time.OffsetDateTime retryAt() {
            return retryAt;
        }

        Map<String, Object> resetEvidence() {
            return resetEvidence;
        }
    }
}
