package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.JdbcAutomationRepositoryData.*;

/** Internal persistence collaboration contract; implemented by the repository facade. */
interface JdbcAutomationRepositoryOperations extends AutomationRepository, AutomationExecutionRepository {

    OffsetDateTime databaseNow();

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

    RunRecord readRun(long tenantId, UUID runPublicId);

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
            RunRecord run, String reason, OffsetDateTime now);

    ScheduleExecution lockScheduleForFire(
            long tenantId, UUID schedulePublicId, OffsetDateTime databaseNow);

    ScheduledRun fireSchedule(
            ScheduleExecution schedule,
            CommandSeal command,
            UUID receiptId,
            String fireKey,
            OffsetDateTime scheduledInstantUtc,
            OffsetDateTime nextFireAt,
            String inputReference,
            String inputDigest,
            String scopeType,
            UUID scopeId,
            long expectedItemCount,
            String concurrencyKeyDigest,
            UUID technicalActor,
            OffsetDateTime now);

    void skipMissedFire(
            ScheduleExecution schedule,
            String fireKey,
            OffsetDateTime missedInstantUtc,
            OffsetDateTime nextFireAt,
            UUID correlationId,
            OffsetDateTime now);

    RuntimeRun lockRuntimeRun(long tenantId, UUID runPublicId);

    void assertLiveOriginalAuthority(
            RuntimeRun run, LeaseGrant authority, PreparedItem item);

    void assertLiveCompensationAuthority(
            RuntimeRun run, LeaseGrant authority,
            PreparedCompensation compensation);

    LeaseGrant acquireWorkerLease(
            RuntimeRun run,
            UUID leaseToken,
            OffsetDateTime leasedUntil,
            OffsetDateTime now);

    LeaseGrant renewWorkerLease(
            RuntimeRun run,
            LeaseGrant current,
            OffsetDateTime leasedUntil,
            OffsetDateTime now);

    void materializeItems(
            RuntimeRun run,
            LeaseGrant worker,
            List<AutomationRuntimePorts.AutomationItemPlan> items,
            OffsetDateTime now);

    PreparedOriginal prepareOriginal(
            RuntimeRun run,
            LeaseGrant worker,
            String requestDigest,
            OffsetDateTime now);

    RuntimeRun finalizeOriginal(
            RuntimeRun run,
            LeaseGrant worker,
            PreparedOriginal prepared,
            List<AutomationRunItemReceiptV1> receipts,
            OffsetDateTime now);

    LeaseGrant acquireReconcilerLease(
            RuntimeRun run,
            UUID leaseToken,
            OffsetDateTime leasedUntil,
            OffsetDateTime now);

    RecoveryResult recoverAuthority(
            RuntimeRun run,
            AutomationDomainPolicy.RecoveryDecision decision,
            UUID suppliedToken,
            boolean explicitlyRevoked,
            String reason,
            OffsetDateTime now);

    RuntimeRun reconcileOriginal(
            RuntimeRun run,
            LeaseGrant reconciler,
            UUID itemPublicId,
            String effectIdempotencyKey,
            String effectRequestDigest,
            AutomationRuntimePorts.ReconciliationEvidence evidence,
            OffsetDateTime now);

    PreparedCompensation prepareCompensation(
            RuntimeRun run,
            LeaseGrant reconciler,
            UUID itemPublicId,
            String compensationKey,
            String requestDigest,
            OffsetDateTime now);

    AutomationRunItemReceiptV1 loadCommittedOriginalReceipt(
            RuntimeRun run, UUID itemPublicId);

    AutomationRunItemReceiptV1 loadOriginalReceipt(
            RuntimeRun run, UUID itemPublicId, int attemptNumber);

    RuntimeRun finalizeCompensation(
            RuntimeRun run,
            LeaseGrant reconciler,
            PreparedCompensation prepared,
            String outcome,
            String receiptDigest,
            AutomationRuntimePorts.ReconciliationEvidence trustedEvidence,
            OffsetDateTime now);

    RuntimeRun appendCheckpointAndRecalculate(
            RuntimeRun run,
            LeaseGrant authority,
            String processedItemDigest,
            String trustedReceiptDigest,
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

    RunActionEligibility runActionEligibility(RunRecord run);

    void appendRunEventAndAudit(
            CommandSeal command,
            RunRecord run,
            String transition,
            OffsetDateTime now);

    void validateRunEvent(RunRecord run);

    VersionRecord version(ResultSet rs, int row) throws SQLException;

    RuntimeRun runtimeRun(ResultSet rs) throws SQLException;

    SignedManifest signedManifest(ResultSet rs) throws SQLException;

    StoredReceipt storedReceipt(ResultSet rs) throws SQLException;

    CommandReceipt receipt(
            CommandSeal command,
            UUID receiptId,
            ReceiptStatus status,
            OffsetDateTime createdAt,
            Long revision,
            String resultRef);

    JsonNode readJson(String value);

    String stringify(Object value);

    ReceiptSet currentReceiptSet(
            long tenantId,
            UUID runId,
            UUID runPublicId,
            long runRevision);

    ReceiptSet currentAttemptReceiptSet(
            long tenantId,
            UUID runId,
            UUID runPublicId,
            int attemptNumber,
            long runRevision);
}
