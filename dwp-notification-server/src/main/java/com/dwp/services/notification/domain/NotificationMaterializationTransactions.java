package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationMaterializationRepository.PersistenceResult;
import com.dwp.services.notification.domain.NotificationMaterializationRepository.RenderedContent;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.operations.NotificationRetentionService;
import com.dwp.services.notification.realtime.NotificationChangePublisher;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

@Component
public class NotificationMaterializationTransactions {

    private final TransactionTemplate transactions;
    private final NotificationDatabaseScope databaseScope;
    private final NotificationMaterializationRepository repository;
    private final NotificationRetentionService retentionService;
    private final NotificationChangePublisher changePublisher;

    public NotificationMaterializationTransactions(
            PlatformTransactionManager transactionManager,
            NotificationDatabaseScope databaseScope,
            NotificationMaterializationRepository repository,
            NotificationRetentionService retentionService,
            NotificationChangePublisher changePublisher) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.databaseScope = databaseScope;
        this.repository = repository;
        this.retentionService = retentionService;
        this.changePublisher = changePublisher;
    }

    public TemplateContract contract(
            long tenantId,
            String typeKey,
            String sourceEventType,
            int sourceSchemaVersion,
            String locale) {
        return Objects.requireNonNull(transactions.execute(ignored -> {
            databaseScope.applyWorker(tenantId);
            return repository.contract(
                    tenantId, typeKey, sourceEventType, sourceSchemaVersion, locale);
        }));
    }

    public PersistenceResult materialize(
            long tenantId,
            DirectMaterializationRequest request,
            TemplateContract contract,
            RenderedContent content,
            String sourcePayloadHash,
            String correlationId,
            Set<Long> entitledRecipientUserIds,
            Instant admittedAt) {
        return Objects.requireNonNull(transactions.execute(ignored -> {
            databaseScope.applyWorker(tenantId);
            PersistenceResult result = repository.materialize(
                    tenantId,
                    request,
                    contract,
                    content,
                    sourcePayloadHash,
                    correlationId,
                    entitledRecipientUserIds);
            if (!result.result().duplicate() && result.result().notificationId() != null) {
                retentionService.applyDefaultExpiry(
                        tenantId, result.result().notificationId(), admittedAt);
            }
            changePublisher.publishAfterCommit(result.signals());
            return result;
        }));
    }
}
