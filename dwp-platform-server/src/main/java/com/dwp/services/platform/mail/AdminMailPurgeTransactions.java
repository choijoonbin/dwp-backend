package com.dwp.services.platform.mail;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Runs the physical delete behind a JDBC savepoint. PostgreSQL marks a transaction as
 * aborted after a constraint failure; rolling this nested boundary back first lets the
 * outer purge transaction persist an explicit FAILED job instead of losing its receipt.
 */
@Component
class AdminMailPurgeTransactions {

    private final AdminMailCompletionRepository repository;
    private final TransactionTemplate nested;

    AdminMailPurgeTransactions(
            AdminMailCompletionRepository repository,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.nested = new TransactionTemplate(transactionManager);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    AdminMailCompletionRepository.DeleteCounts deleteCandidates(
            long tenantId, OffsetDateTime before) {
        return Objects.requireNonNull(nested.execute(
                status -> repository.deletePurgeCandidates(tenantId, before)));
    }
}
