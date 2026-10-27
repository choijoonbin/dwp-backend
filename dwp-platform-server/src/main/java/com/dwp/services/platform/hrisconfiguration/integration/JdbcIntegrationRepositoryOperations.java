package com.dwp.services.platform.hrisconfiguration.integration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.JdbcIntegrationRepositoryData.*;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** Internal persistence collaboration contract; implemented by the repository facade. */
interface JdbcIntegrationRepositoryOperations extends IntegrationRepository, IntegrationExecutionRepository {

    OffsetDateTime databaseNow();

    void bindTenantPrincipal(
            long tenantId, UUID subjectPrincipalPublicId);

    Optional<CommandReceipt> findReplay(CommandSeal command);

    CommandReservation beginCommand(
            CommandSeal command, OffsetDateTime now);

    CommandReceipt progressCommand(
            CommandSeal command,
            UUID receiptId,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            OffsetDateTime now);

    CommandReceipt finishCommand(
            CommandSeal command,
            UUID receiptId,
            ReceiptStatus status,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            OffsetDateTime now);

    void markOriginalReceiptOutcome(
            ExecutionRecord execution,
            ReceiptStatus status,
            OffsetDateTime now);

    CatalogPage catalog(
            long tenantId,
            OffsetDateTime asOf,
            int limit,
            String afterItemPublicId,
            String filter);

    DefinitionRecord insertDefinition(
            long tenantId,
            UUID connectorId,
            long actorId,
            String actorScopeDigest,
            ConnectorCommand command,
            String definitionDigest,
            long endpointPolicyRevision,
            long secretRevision,
            OffsetDateTime now);

    DefinitionRecord lockDefinition(
            long tenantId, UUID connectorId);

    DefinitionRecord transitionDefinition(
            DefinitionRecord current,
            DefinitionState target,
            long actorId,
            UUID evidenceReceiptId,
            String evidenceDigest,
            String stepUpDigest,
            OffsetDateTime now);

    DefinitionRecord recordConnectionTest(
            DefinitionRecord current,
            long actorId,
            UUID commandReceiptId,
            String receiptRef,
            String receiptDigest,
            OffsetDateTime now);

    MappingRecord insertMapping(
            DefinitionRecord definition,
            long actorId,
            String actorScopeDigest,
            MappingCommand command,
            String direction,
            String sourceSchemaDigest,
            String targetSchemaDigest,
            OffsetDateTime now);

    MappingRecord lockMapping(
            long tenantId, UUID connectorId, long version);

    MappingRecord transitionMapping(
            MappingRecord current,
            MappingState target,
            long actorId,
            UUID evidenceReceiptId,
            String evidenceDigest,
            String stepUpDigest,
            OffsetDateTime now);

    MappingRecord recordDryRun(
            MappingRecord current,
            long actorId,
            UUID commandReceiptId,
            String resultRef,
            String resultDigest,
            OffsetDateTime now);

    ExecutionRecord insertExecution(
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
            OffsetDateTime now);

    ExecutionRecord lockExecution(
            long tenantId, UUID executionId);

    ExecutionRecord lockExecutionAuthorized(
            long tenantId,
            UUID executionId,
            String requestedScopeDigest,
            String populationKind,
            UUID legalEntityPublicId);

    ExecutionResponse executionResponse(
            long tenantId, UUID executionId);

    ExecutionResponse executionResponseAuthorized(
            long tenantId,
            UUID executionId,
            String requestedScopeDigest,
            String populationKind,
            UUID legalEntityPublicId);

    ExecutionRecord beginDispatch(
            ExecutionRecord current,
            long expectedFence,
            OffsetDateTime now);

    List<AttemptRecord> currentAttempts(ExecutionRecord current);

    ExecutionRecord transitionExecution(
            ExecutionRecord current,
            ExecutionState target,
            OffsetDateTime now);

    CompensationClaim claimCompensation(
            ExecutionRecord current,
            String claimDigest,
            Long expectedRevision,
            OffsetDateTime now);

    ExecutionRecord markCompensationResultUnknown(
            ExecutionRecord current,
            String operationsExceptionRef,
            OffsetDateTime now);

    ExecutionRecord beginReconciliation(
            ExecutionRecord current,
            String providerReceiptRef,
            String providerReceiptDigest,
            OffsetDateTime now);

    ExecutionRecord applyProviderEvidence(
            ExecutionRecord current,
            IntegrationRuntimePorts.ProviderEvidence evidence,
            ExecutionState target,
            OffsetDateTime now);

    void applyAttemptAndItemEvidence(
            ExecutionRecord current,
            IntegrationRuntimePorts.ProviderEvidence evidence,
            String evidenceDigest,
            ExecutionState target,
            OffsetDateTime now);

    Map<UUID, IntegrationRuntimePorts.ProviderItemEvidence>
            normalizedItemEvidence(
                    ExecutionRecord current,
                    IntegrationRuntimePorts.ProviderEvidence evidence,
                    List<AttemptRecord> attempts,
                    ExecutionState target);

    void requireRelationalCounts(
            ExecutionRecord current,
            IntegrationRuntimePorts.ProviderEvidence evidence);

    List<UUID> retryableItemIds(ExecutionRecord current);

    ExecutionRecord createRetryAttempts(
            ExecutionRecord current,
            List<UUID> exactFailedItemIds,
            String failedItemSetDigest,
            long actorId,
            OffsetDateTime now);

    ExecutionRecord deadLetter(
            ExecutionRecord current,
            long actorId,
            OffsetDateTime now);

    ExecutionRecord cancel(
            ExecutionRecord current,
            String compensationPolicyRef,
            String compensationPolicyDigest,
            String compensationReceiptRef,
            String compensationReceiptDigest,
            long actorId,
            OffsetDateTime now);

    OwnerPage ownerPage(
            TrustedOwnerQuery query,
            String afterItemPublicId,
            List<String> allowedActions);

    void appendExecutionEventAndAudit(
            CommandSeal command,
            ExecutionRecord execution,
            String transition,
            OffsetDateTime now);

    void appendControlAudit(
            CommandSeal command,
            String aggregateType,
            UUID aggregatePublicId,
            long aggregateRevision,
            String transition,
            Object afterState,
            OffsetDateTime now);

    int incrementDefinitionAggregate(
            DefinitionRecord current, OffsetDateTime now);

    int incrementDefinitionAggregate(
            long tenantId, UUID definitionId, OffsetDateTime now);

    DefinitionRecord definition(ResultSet rs) throws SQLException;

    MappingRecord mapping(ResultSet rs) throws SQLException;

    ExecutionRecord execution(ResultSet rs) throws SQLException;

    StoredReceipt storedReceipt(ResultSet rs) throws SQLException;

    String stringify(Object value);
}
