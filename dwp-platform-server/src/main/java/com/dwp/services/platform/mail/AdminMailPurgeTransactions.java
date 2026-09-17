package com.dwp.services.platform.mail;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

/**
 * Runs the physical delete behind a JDBC savepoint. PostgreSQL marks a transaction as
 * aborted after a constraint failure; rolling this nested boundary back first lets the
 * outer purge transaction persist an explicit FAILED job instead of losing its receipt.
 */
@Component
class AdminMailPurgeTransactions {

    private final AdminMailCompletionRepository repository;
    private final AdminMailPurgeGuard guard;
    private final TransactionTemplate nested;

    @Autowired
    AdminMailPurgeTransactions(
            AdminMailCompletionRepository repository,
            PlatformTransactionManager transactionManager,
            AdminMailPurgeGuard guard) {
        this.repository = repository;
        this.guard = guard;
        this.nested = new TransactionTemplate(transactionManager);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    AdminMailPurgeTransactions(
            AdminMailCompletionRepository repository,
            PlatformTransactionManager transactionManager) {
        this(repository, transactionManager, new AdminMailPurgeGuard(repository));
    }

    AdminMailCompletionRepository.DeleteCounts deleteCandidates(
            long tenantId, OffsetDateTime before) {
        return Objects.requireNonNull(nested.execute(
                status -> repository.deletePurgeCandidates(tenantId, before)));
    }

    AdminMailCompletionRepository.DeleteCounts deleteCandidates(
            long tenantId, List<UUID> threadIds) {
        return Objects.requireNonNull(nested.execute(
                status -> repository.deletePurgeCandidates(tenantId, threadIds)));
    }

    AdminMailCompletionRepository.DeleteCounts revalidateAndDelete(
            AdminMailCompletionRepository.PurgeLeaseRow job) {
        return Objects.requireNonNull(nested.execute(status -> {
            repository.lockRetentionLifecycle(job.tenantId());
            guard.requireSafe(job);
            AdminMailCompletionRepository.PurgePreviewRow preview = repository
                    .purgePreview(job.tenantId(), job.snapshotId())
                    .orElseThrow(() -> new AdminMailPurgeGuard.PurgeBlockedException(
                            "PURGE_PREVIEW_MISSING"));
            return repository.deleteEligiblePurgeCandidates(job, preview.before());
        }));
    }
}
