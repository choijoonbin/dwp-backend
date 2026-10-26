package com.dwp.services.platform.hrisconfiguration.integration;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;

/** Persistence boundary for the tenant-owned SYS-008 control plane. */
interface IntegrationRepository {

    OffsetDateTime databaseNow();

    void bindTenantPrincipal(long tenantId, UUID subjectPrincipalPublicId);

    Optional<CommandReceipt> findReplay(CommandSeal command);

    CommandReservation beginCommand(CommandSeal command, OffsetDateTime now);

    CommandReceipt finishCommand(
            CommandSeal command,
            UUID receiptId,
            ReceiptStatus status,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            OffsetDateTime now);

    CommandReceipt progressCommand(
            CommandSeal command,
            UUID receiptId,
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

    DefinitionRecord lockDefinition(long tenantId, UUID connectorId);

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

    MappingRecord lockMapping(long tenantId, UUID connectorId, long version);

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

    ExecutionRecord lockExecution(long tenantId, UUID executionId);

    ExecutionResponse executionResponse(
            long tenantId,
            UUID executionId);

    ExecutionResponse executionResponseAuthorized(
            long tenantId,
            UUID executionId,
            String requestedScopeDigest,
            String populationKind,
            UUID legalEntityPublicId);

    OwnerPage ownerPage(
            Sys008ProjectionContract.TrustedOwnerQuery query,
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

    record CommandSeal(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String commandType,
            String originatingAction,
            String routeContractKey,
            String contextKey,
            String contextScopeKey,
            String decisionRevision,
            String populationScopeDigest,
            long fieldPolicyRevision,
            long authorizationRevision,
            String purposeCode,
            String idempotencyKey,
            String requestDigest,
            UUID correlationId) {}

    record CommandReservation(CommandReceipt receipt, boolean owner) {}

    record DefinitionRecord(
            UUID definitionId,
            UUID definitionVersionId,
            UUID connectorId,
            long tenantId,
            String connectorKey,
            ConnectorType connectorType,
            URI endpoint,
            String secretRef,
            String egressPolicyKey,
            long definitionVersion,
            long activeMappingVersion,
            DefinitionState state,
            String definitionDigest,
            long endpointPolicyRevision,
            long secretRevision,
            long aggregateRevision,
            long subjectRevision,
            long objectRevision,
            long authorActorId,
            String authorScopeDigest,
            Long approvedByActorId,
            UUID approvalReceiptId,
            String connectionTestReceiptRef,
            String connectionTestReceiptDigest) {}

    record MappingRecord(
            UUID mappingId,
            long tenantId,
            UUID definitionId,
            UUID connectorId,
            long version,
            MappingState state,
            long sourceSchemaVersion,
            long targetSchemaVersion,
            String sourceSchemaDigest,
            String targetSchemaDigest,
            String mappingRef,
            String mappingDigest,
            CompatibilityMode compatibilityMode,
            String direction,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            long objectRevision,
            long subjectRevision,
            long authorActorId,
            String authorScopeDigest,
            Long approvedByActorId,
            UUID approvalReceiptId,
            String dryRunResultRef,
            String dryRunResultDigest) {}

    record ExecutionRecord(
            UUID executionId,
            UUID internalId,
            long tenantId,
            UUID definitionId,
            UUID connectorId,
            UUID mappingId,
            long definitionVersion,
            long mappingVersion,
            String mappingRef,
            String mappingDigest,
            String mappingDirection,
            String sourceSchemaDigest,
            String targetSchemaDigest,
            URI endpoint,
            String egressPolicyKey,
            String secretRef,
            long endpointPolicyRevision,
            long secretRevision,
            ExecutionState state,
            Operation operation,
            String payloadRef,
            String payloadDigest,
            String providerIdempotencyKey,
            String providerRequestDigest,
            long sourceCount,
            long acceptedCount,
            long rejectedCount,
            long quarantinedCount,
            long selectedItemCount,
            String selectedItemDigest,
            String reconciliationDigest,
            long retryGeneration,
            long aggregateRevision,
            long attemptFence,
            boolean providerDispatchOccurred,
            UUID originalCommandReceiptId,
            long authorizationRevision,
            String requestedScopeDigest,
            String populationKind,
            UUID legalEntityPublicId,
            OffsetDateTime requestedAt,
            long requestedByActorId,
            UUID correlationId,
            String ownerIdempotencyKey,
            String providerReceiptRef,
            String providerReceiptDigest,
            String compensationPolicyRef,
            String compensationReceiptDigest) {}

    record CatalogPage(List<ConnectorSummary> items, String nextItemPublicId) {
        public CatalogPage {
            items = List.copyOf(items);
        }
    }

    record OwnerPage(
            Sys008ProjectionContract.QueryState state,
            boolean evidenceCurrent,
            List<Sys008ProjectionContract.OwnerItem> items,
            String ownerReceipt,
            String nextItemPublicId) {
        public OwnerPage {
            items = List.copyOf(items);
        }
    }
}
