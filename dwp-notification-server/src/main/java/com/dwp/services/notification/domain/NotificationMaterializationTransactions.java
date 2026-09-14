package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationMaterializationRepository.PersistenceResult;
import com.dwp.services.notification.domain.NotificationMaterializationRepository.RenderedContent;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.operations.NotificationRetentionService;
import com.dwp.services.notification.realtime.NotificationChangePublisher;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final JdbcTemplate jdbc;

    public NotificationMaterializationTransactions(
            PlatformTransactionManager transactionManager,
            NotificationDatabaseScope databaseScope,
            NotificationMaterializationRepository repository,
            NotificationRetentionService retentionService,
            NotificationChangePublisher changePublisher) {
        this(transactionManager, databaseScope, repository, retentionService, changePublisher, null);
    }

    @Autowired
    public NotificationMaterializationTransactions(
            PlatformTransactionManager transactionManager,
            NotificationDatabaseScope databaseScope,
            NotificationMaterializationRepository repository,
            NotificationRetentionService retentionService,
            NotificationChangePublisher changePublisher,
            JdbcTemplate jdbc) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.databaseScope = databaseScope;
        this.repository = repository;
        this.retentionService = retentionService;
        this.changePublisher = changePublisher;
        this.jdbc = jdbc;
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
            return persist(tenantId, request, contract, content, sourcePayloadHash,
                    correlationId, entitledRecipientUserIds, admittedAt);
        }));
    }

    public void requireExistingWorkerTransaction(long tenantId) {
        if (jdbc == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || tenantId <= 0 || !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT ntf_is_worker() AND ntf_current_tenant_id()=?", Boolean.class, tenantId)))
            throw new IllegalStateException("SLA materialization requires an existing tenant worker transaction.");
    }

    public TemplateContract contractWithinWorkerTransaction(long tenantId, String typeKey,
            String sourceEventType, int schemaVersion, String locale) {
        requireExistingWorkerTransaction(tenantId);
        return repository.contract(tenantId, typeKey, sourceEventType, schemaVersion, locale);
    }

    public PersistenceResult materializeWithinWorkerTransaction(long tenantId,
            DirectMaterializationRequest request, TemplateContract contract, RenderedContent content,
            String sourcePayloadHash, String correlationId, Set<Long> entitledRecipientUserIds, Instant admittedAt) {
        requireExistingWorkerTransaction(tenantId);
        return persist(tenantId, request, contract, content, sourcePayloadHash,
                correlationId, entitledRecipientUserIds, admittedAt);
    }

    private PersistenceResult persist(long tenantId, DirectMaterializationRequest request,
            TemplateContract contract, RenderedContent content, String sourcePayloadHash,
            String correlationId, Set<Long> entitledRecipientUserIds, Instant admittedAt) {
        PersistenceResult result = repository.materialize(tenantId, request, contract, content,
                sourcePayloadHash, correlationId, entitledRecipientUserIds);
        if (!result.result().duplicate() && result.result().notificationId() != null)
            retentionService.applyDefaultExpiry(tenantId, result.result().notificationId(), admittedAt);
        changePublisher.publishAfterCommit(result.signals());
        return result;
    }
}
