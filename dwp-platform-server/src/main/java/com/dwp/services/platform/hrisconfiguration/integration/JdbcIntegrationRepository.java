package com.dwp.services.platform.hrisconfiguration.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.JdbcIntegrationRepositoryData.*;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** PostgreSQL adapter for the exact six-table V334 ownership model. */
@Repository
@ConditionalOnProperty(
        name = "dwp.hris.integration-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcIntegrationRepository implements JdbcIntegrationRepositoryOperations {
    private final JdbcIntegrationCommands commands;
    private final JdbcIntegrationDefinitions definitions;
    private final JdbcIntegrationExecutions executionStore;
    private final JdbcIntegrationCompensation compensation;
    private final JdbcIntegrationProviderEvidence providerEvidence;
    private final JdbcIntegrationRetry retryStore;
    private final JdbcIntegrationOwnerPage ownerPages;
    private final JdbcIntegrationAudit audit;
    private final JdbcIntegrationMapping mapping;
    private final JdbcIntegrationRepositorySupport support;


    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IntegrationCanonicalJson canonical;

    JdbcIntegrationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.canonical = new IntegrationCanonicalJson(json);
        this.commands = new JdbcIntegrationCommands(jdbc);
        this.definitions = new JdbcIntegrationDefinitions(this, jdbc);
        this.executionStore = new JdbcIntegrationExecutions(this, jdbc, canonical);
        this.compensation = new JdbcIntegrationCompensation(this, jdbc);
        this.providerEvidence = new JdbcIntegrationProviderEvidence(this, jdbc, canonical);
        this.retryStore = new JdbcIntegrationRetry(this, jdbc, canonical);
        this.ownerPages = new JdbcIntegrationOwnerPage(jdbc, canonical);
        this.audit = new JdbcIntegrationAudit(this, jdbc, json);
        this.mapping = new JdbcIntegrationMapping(json);
        this.support = new JdbcIntegrationRepositorySupport(jdbc);
    }

    @Override
    public OffsetDateTime databaseNow() {
        return support.databaseNow();
    }

    @Override
    public void bindTenantPrincipal(
            long tenantId, UUID subjectPrincipalPublicId) {
        support.bindTenantPrincipal(tenantId, subjectPrincipalPublicId);
    }

    @Override
    public Optional<CommandReceipt> findReplay(CommandSeal command) {
        return commands.findReplay(command);
    }

    @Override
    public CommandReservation beginCommand(
            CommandSeal command, OffsetDateTime now) {
        return commands.beginCommand(command, now);
    }

    @Override
    public CommandReceipt progressCommand(
            CommandSeal command,
            UUID receiptId,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            OffsetDateTime now) {
        return commands.progressCommand(
                command, receiptId, aggregatePublicId, aggregateRevision, resultRef, now);
    }

    @Override
    public CommandReceipt finishCommand(
            CommandSeal command,
            UUID receiptId,
            ReceiptStatus status,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            OffsetDateTime now) {
        return commands.finishCommand(
                command, receiptId, status, aggregatePublicId, aggregateRevision, resultRef, now);
    }

    @Override
    public void markOriginalReceiptOutcome(
            ExecutionRecord execution,
            ReceiptStatus status,
            OffsetDateTime now) {
        commands.markOriginalReceiptOutcome(execution, status, now);
    }

    @Override
    public CatalogPage catalog(
            long tenantId,
            OffsetDateTime asOf,
            int limit,
            String afterItemPublicId,
            String filter) {
        return definitions.catalog(tenantId, asOf, limit, afterItemPublicId, filter);
    }

    @Override
    public DefinitionRecord insertDefinition(
            long tenantId,
            UUID connectorId,
            long actorId,
            String actorScopeDigest,
            ConnectorCommand command,
            String definitionDigest,
            long endpointPolicyRevision,
            long secretRevision,
            OffsetDateTime now) {
        return definitions.insertDefinition(
                tenantId, connectorId, actorId, actorScopeDigest, command, definitionDigest,
                endpointPolicyRevision, secretRevision, now);
    }

    @Override
    public DefinitionRecord lockDefinition(
            long tenantId, UUID connectorId) {
        return definitions.lockDefinition(tenantId, connectorId);
    }

    @Override
    public DefinitionRecord transitionDefinition(
            DefinitionRecord current,
            DefinitionState target,
            long actorId,
            UUID evidenceReceiptId,
            String evidenceDigest,
            String stepUpDigest,
            OffsetDateTime now) {
        return definitions.transitionDefinition(
                current, target, actorId, evidenceReceiptId, evidenceDigest, stepUpDigest, now);
    }

    @Override
    public DefinitionRecord recordConnectionTest(
            DefinitionRecord current,
            long actorId,
            UUID commandReceiptId,
            String receiptRef,
            String receiptDigest,
            OffsetDateTime now) {
        return definitions.recordConnectionTest(
                current, actorId, commandReceiptId, receiptRef, receiptDigest, now);
    }

    @Override
    public MappingRecord insertMapping(
            DefinitionRecord definition,
            long actorId,
            String actorScopeDigest,
            MappingCommand command,
            String direction,
            String sourceSchemaDigest,
            String targetSchemaDigest,
            OffsetDateTime now) {
        return definitions.insertMapping(
                definition, actorId, actorScopeDigest, command, direction, sourceSchemaDigest,
                targetSchemaDigest, now);
    }

    @Override
    public MappingRecord lockMapping(
            long tenantId, UUID connectorId, long version) {
        return definitions.lockMapping(tenantId, connectorId, version);
    }

    @Override
    public MappingRecord transitionMapping(
            MappingRecord current,
            MappingState target,
            long actorId,
            UUID evidenceReceiptId,
            String evidenceDigest,
            String stepUpDigest,
            OffsetDateTime now) {
        return definitions.transitionMapping(
                current, target, actorId, evidenceReceiptId, evidenceDigest, stepUpDigest, now);
    }

    @Override
    public MappingRecord recordDryRun(
            MappingRecord current,
            long actorId,
            UUID commandReceiptId,
            String resultRef,
            String resultDigest,
            OffsetDateTime now) {
        return definitions.recordDryRun(current, actorId, commandReceiptId, resultRef, resultDigest, now);
    }

    @Override
    public ExecutionRecord insertExecution(
            DefinitionRecord definition,
            MappingRecord mapping,
            UUID executionPublicId,
            long actorId,
            UUID commandReceiptId,
            ExecutionCommand command,
            String providerIdempotencyKey,
            String providerRequestDigest,
            long authorizationRevision,
            String requestedScopeDigest,
            String populationKind,
            UUID legalEntityPublicId,
            List<IntegrationRuntimePorts.SourceManifestItem> sourceManifest,
            OffsetDateTime now) {
        return executionStore.insertExecution(
                definition, mapping, executionPublicId, actorId, commandReceiptId, command,
                providerIdempotencyKey, providerRequestDigest, authorizationRevision,
                requestedScopeDigest, populationKind, legalEntityPublicId, sourceManifest, now);
    }

    @Override
    public ExecutionRecord lockExecution(
            long tenantId, UUID executionId) {
        return executionStore.lockExecution(tenantId, executionId);
    }

    @Override
    public ExecutionRecord lockExecutionAuthorized(
            long tenantId,
            UUID executionId,
            String requestedScopeDigest,
            String populationKind,
            UUID legalEntityPublicId) {
        return executionStore.lockExecutionAuthorized(
                tenantId, executionId, requestedScopeDigest, populationKind, legalEntityPublicId);
    }

    @Override
    public ExecutionResponse executionResponse(
            long tenantId, UUID executionId) {
        return executionStore.executionResponse(tenantId, executionId);
    }

    @Override
    public ExecutionResponse executionResponseAuthorized(
            long tenantId,
            UUID executionId,
            String requestedScopeDigest,
            String populationKind,
            UUID legalEntityPublicId) {
        return executionStore.executionResponseAuthorized(
                tenantId, executionId, requestedScopeDigest, populationKind, legalEntityPublicId);
    }

    @Override
    public ExecutionRecord beginDispatch(
            ExecutionRecord current,
            long expectedFence,
            OffsetDateTime now) {
        return executionStore.beginDispatch(current, expectedFence, now);
    }

    @Override
    public List<AttemptRecord> currentAttempts(ExecutionRecord current) {
        return executionStore.currentAttempts(current);
    }

    @Override
    public ExecutionRecord transitionExecution(
            ExecutionRecord current,
            ExecutionState target,
            OffsetDateTime now) {
        return executionStore.transitionExecution(current, target, now);
    }

    @Override
    public CompensationClaim claimCompensation(
            ExecutionRecord current,
            String claimDigest,
            Long expectedRevision,
            OffsetDateTime now) {
        return compensation.claimCompensation(current, claimDigest, expectedRevision, now);
    }

    @Override
    public ExecutionRecord markCompensationResultUnknown(
            ExecutionRecord current,
            String operationsExceptionRef,
            OffsetDateTime now) {
        return compensation.markCompensationResultUnknown(current, operationsExceptionRef, now);
    }

    @Override
    public ExecutionRecord beginReconciliation(
            ExecutionRecord current,
            String providerReceiptRef,
            String providerReceiptDigest,
            OffsetDateTime now) {
        return compensation.beginReconciliation(current, providerReceiptRef, providerReceiptDigest, now);
    }

    @Override
    public ExecutionRecord applyProviderEvidence(
            ExecutionRecord current,
            IntegrationRuntimePorts.ProviderEvidence evidence,
            ExecutionState target,
            OffsetDateTime now) {
        return providerEvidence.applyProviderEvidence(current, evidence, target, now);
    }

    public void applyAttemptAndItemEvidence(
            ExecutionRecord current,
            IntegrationRuntimePorts.ProviderEvidence evidence,
            String evidenceDigest,
            ExecutionState target,
            OffsetDateTime now) {
        providerEvidence.applyAttemptAndItemEvidence(current, evidence, evidenceDigest, target, now);
    }

    public Map<UUID, IntegrationRuntimePorts.ProviderItemEvidence>
            normalizedItemEvidence(
                    ExecutionRecord current,
                    IntegrationRuntimePorts.ProviderEvidence evidence,
                    List<AttemptRecord> attempts,
                    ExecutionState target) {
        return providerEvidence.normalizedItemEvidence(current, evidence, attempts, target);
    }

    public void requireRelationalCounts(
            ExecutionRecord current,
            IntegrationRuntimePorts.ProviderEvidence evidence) {
        providerEvidence.requireRelationalCounts(current, evidence);
    }

    @Override
    public List<UUID> retryableItemIds(ExecutionRecord current) {
        return retryStore.retryableItemIds(current);
    }

    @Override
    public ExecutionRecord createRetryAttempts(
            ExecutionRecord current,
            List<UUID> exactFailedItemIds,
            String failedItemSetDigest,
            long actorId,
            OffsetDateTime now) {
        return retryStore.createRetryAttempts(current, exactFailedItemIds, failedItemSetDigest, actorId, now);
    }

    @Override
    public ExecutionRecord deadLetter(
            ExecutionRecord current,
            long actorId,
            OffsetDateTime now) {
        return retryStore.deadLetter(current, actorId, now);
    }

    @Override
    public ExecutionRecord cancel(
            ExecutionRecord current,
            String compensationPolicyRef,
            String compensationPolicyDigest,
            String compensationReceiptRef,
            String compensationReceiptDigest,
            long actorId,
            OffsetDateTime now) {
        return retryStore.cancel(
                current, compensationPolicyRef, compensationPolicyDigest, compensationReceiptRef,
                compensationReceiptDigest, actorId, now);
    }

    @Override
    public OwnerPage ownerPage(
            TrustedOwnerQuery query,
            String afterItemPublicId,
            List<String> allowedActions) {
        return ownerPages.ownerPage(query, afterItemPublicId, allowedActions);
    }

    @Override
    public void appendExecutionEventAndAudit(
            CommandSeal command,
            ExecutionRecord execution,
            String transition,
            OffsetDateTime now) {
        audit.appendExecutionEventAndAudit(command, execution, transition, now);
    }

    @Override
    public void appendControlAudit(
            CommandSeal command,
            String aggregateType,
            UUID aggregatePublicId,
            long aggregateRevision,
            String transition,
            Object afterState,
            OffsetDateTime now) {
        audit.appendControlAudit(
                command, aggregateType, aggregatePublicId, aggregateRevision, transition,
                afterState, now);
    }

    public int incrementDefinitionAggregate(
            DefinitionRecord current, OffsetDateTime now) {
        return definitions.incrementDefinitionAggregate(current, now);
    }

    public int incrementDefinitionAggregate(
            long tenantId, UUID definitionId, OffsetDateTime now) {
        return definitions.incrementDefinitionAggregate(tenantId, definitionId, now);
    }

    public DefinitionRecord definition(ResultSet rs) throws SQLException {
        return mapping.definition(rs);
    }

    public MappingRecord mapping(ResultSet rs) throws SQLException {
        return mapping.mapping(rs);
    }

    public ExecutionRecord execution(ResultSet rs) throws SQLException {
        return mapping.execution(rs);
    }

    public StoredReceipt storedReceipt(ResultSet rs) throws SQLException {
        return commands.storedReceipt(rs);
    }

    public String stringify(Object value) {
        return mapping.stringify(value);
    }
}
