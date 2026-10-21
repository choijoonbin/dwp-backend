package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;

interface AutomationRepository {

    void bindTenantPrincipal(long tenantId, UUID subjectPrincipalPublicId);

    Optional<CommandReceipt> findReplay(CommandSeal command);

    CommandReceipt beginCommand(CommandSeal command, OffsetDateTime now);

    CommandReceipt completeCommand(
            CommandSeal command,
            UUID receiptId,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            OffsetDateTime now);

    CatalogPage catalog(
            long tenantId,
            OffsetDateTime asOf,
            int limit,
            java.util.Set<String> resourceTypes,
            String filter,
            String afterSortTuple);

    Optional<VersionRecord> readVersion(
            long tenantId, UUID definitionPublicId, long versionNumber);

    Optional<VersionRecord> findEligibleVersion(
            long tenantId, UUID definitionPublicId, boolean activeOnly);

    Set<String> eligibleCatalogActions(
            long tenantId, UUID subjectPrincipalPublicId, CatalogItem item);

    DefinitionRecord lockDefinition(long tenantId, UUID definitionPublicId);

    VersionRecord lockVersion(
            long tenantId, UUID definitionPublicId, long versionNumber);

    ScheduleRecord lockSchedule(long tenantId, UUID schedulePublicId);

    RunRecord lockRun(long tenantId, UUID runPublicId);

    DefinitionRecord insertDefinition(
            long tenantId,
            UUID author,
            CreateDefinitionCommand command,
            OffsetDateTime now);

    VersionRecord insertVersion(
            DefinitionRecord definition,
            UUID author,
            CreateVersionCommand command,
            VerifiedArtifact artifact,
            OffsetDateTime now);

    VersionRecord transitionVersion(
            VersionRecord version,
            VersionState target,
            UUID actor,
            UUID receiptId,
            String evidenceDigest,
            String stepUpDigest,
            VerifiedArtifact currentArtifact,
            OffsetDateTime now);

    ScheduleRecord insertSchedule(
            DefinitionRecord definition,
            VersionRecord version,
            UUID author,
            CreateScheduleCommand command,
            OffsetDateTime now);

    ScheduleRecord transitionSchedule(
            ScheduleRecord schedule,
            ScheduleState target,
            UUID actor,
            UUID receiptId,
            String evidenceDigest,
            String stepUpDigest,
            OffsetDateTime now);

    RunRecord insertRun(
            DefinitionRecord definition,
            VersionRecord version,
            UUID actor,
            UUID commandReceiptId,
            QueueRunCommand command,
            String concurrencyKeyDigest,
            OffsetDateTime now);

    RunRecord retryRun(
            RunRecord run,
            UUID actor,
            UUID commandReceiptId,
            SelectionMode selectionMode,
            long selectedItemCount,
            String selectedItemDigest,
            OffsetDateTime now);

    RetrySelection retrySelection(RunRecord run);

    RunRecord cancelRun(
            RunRecord run,
            String reason,
            OffsetDateTime now);

    RunDetailResponse runDetail(
            long tenantId,
            UUID runPublicId,
            OffsetDateTime asOf,
            int limit,
            Integer afterAttempt,
            Long afterItem,
            String afterCheckpoint,
            long authorizationRevision,
            UUID correlationId);

    void appendRunEventAndAudit(
            CommandSeal command,
            RunRecord run,
            String transition,
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
            UUID correlationId) {
    }

    record DefinitionRecord(
            UUID definitionId,
            UUID publicId,
            long tenantId,
            String automationKey,
            OwnerDomain ownerDomain,
            String lifecycleState,
            Long activeVersionNumber,
            long objectRevision,
            UUID authorActorRef) {
    }

    record VersionRecord(
            UUID versionId,
            long tenantId,
            UUID definitionId,
            UUID definitionPublicId,
            long versionNumber,
            VersionState state,
            JsonNode definitionPayload,
            String definitionDigest,
            JsonNode executionPolicy,
            String executionPolicyDigest,
            String manualConcurrencyKeyTemplate,
            String compatibilityBaselineDigest,
            SignedManifest approvedManifest,
            long subjectRevision,
            long objectRevision,
            UUID authorActorRef,
            UUID approvedByActorRef,
            long maxAttempts,
            RetryMode retryMode) {
    }

    record ScheduleRecord(
            UUID scheduleId,
            UUID publicId,
            long tenantId,
            UUID definitionId,
            UUID definitionPublicId,
            UUID versionId,
            long pinnedVersionNumber,
            ScheduleState state,
            String expression,
            String timezone,
            String timezoneDatabaseRevision,
            String schedulePolicyDigest,
            long subjectRevision,
            long objectRevision,
            long fireCursorRevision,
            OffsetDateTime nextFireAt,
            UUID authorActorRef,
            UUID approvedByActorRef) {
    }

    record RunRecord(
            UUID runId,
            UUID publicId,
            long tenantId,
            UUID definitionId,
            UUID definitionPublicId,
            UUID versionId,
            long versionNumber,
            RunState state,
            SelectionMode selectionMode,
            String concurrencyKeyDigest,
            int currentAttempt,
            int maxAttempts,
            RetryMode retryMode,
            long itemTotal,
            long itemSucceeded,
            long itemFailed,
            String outputDigest,
            long objectRevision,
            String reconciliationRequiredReason) {
    }

    record RetrySelection(
            SelectionMode mode,
            long selectedItemCount,
            String selectedItemDigest) {
    }

    record CatalogPage(List<CatalogItem> items, String nextSortTuple) {
        public CatalogPage {
            items = List.copyOf(items);
        }
    }
}
