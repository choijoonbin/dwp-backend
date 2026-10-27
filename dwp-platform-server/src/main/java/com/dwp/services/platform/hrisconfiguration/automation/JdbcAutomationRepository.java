package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.JdbcAutomationRepositoryData.*;

@Repository
@ConditionalOnProperty(
        name = "dwp.hris.automation-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcAutomationRepository implements JdbcAutomationRepositoryOperations {
    private final JdbcAutomationCatalog catalogStore;
    private final JdbcAutomationDefinitions definitions;
    private final JdbcAutomationRuns runs;
    private final JdbcAutomationSchedules schedules;
    private final JdbcAutomationWorker worker;
    private final JdbcAutomationReconciliation reconciliation;
    private final JdbcAutomationCompensation compensation;
    private final JdbcAutomationEvidence evidenceStore;
    private final JdbcAutomationMapping mapping;
    private final JdbcAutomationRepositorySupport support;


    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AutomationCanonicalJson canonical;

    JdbcAutomationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.canonical = new AutomationCanonicalJson(json);
        this.catalogStore = new JdbcAutomationCatalog(this, jdbc);
        this.definitions = new JdbcAutomationDefinitions(this, jdbc);
        this.runs = new JdbcAutomationRuns(this, jdbc);
        this.schedules = new JdbcAutomationSchedules(this, jdbc);
        this.worker = new JdbcAutomationWorker(this, jdbc, canonical);
        this.reconciliation = new JdbcAutomationReconciliation(this, jdbc);
        this.compensation = new JdbcAutomationCompensation(this, jdbc, canonical);
        this.evidenceStore = new JdbcAutomationEvidence(this, jdbc, json, canonical);
        this.mapping = new JdbcAutomationMapping(json);
        this.support = new JdbcAutomationRepositorySupport(this, jdbc);
    }

    @Override
    public OffsetDateTime databaseNow() {
        return support.databaseNow();
    }

    @Override
    public void bindTenantPrincipal(long tenantId, UUID subjectPrincipalPublicId) {
        support.bindTenantPrincipal(tenantId, subjectPrincipalPublicId);
    }

    @Override
    public Optional<CommandReceipt> findReplay(CommandSeal command) {
        return support.findReplay(command);
    }

    @Override
    public CommandReceipt beginCommand(CommandSeal command, OffsetDateTime now) {
        return support.beginCommand(command, now);
    }

    @Override
    public CommandReceipt completeCommand(
            CommandSeal command,
            UUID receiptId,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            OffsetDateTime now) {
        return support.completeCommand(
                command, receiptId, aggregatePublicId, aggregateRevision, resultRef, now);
    }

    @Override
    public CatalogPage catalog(
            long tenantId,
            OffsetDateTime asOf,
            int limit,
            java.util.Set<String> resourceTypes,
            String filter,
            String afterSortTuple) {
        return catalogStore.catalog(tenantId, asOf, limit, resourceTypes, filter, afterSortTuple);
    }

    @Override
    public Optional<VersionRecord> readVersion(
            long tenantId, UUID definitionPublicId, long versionNumber) {
        return catalogStore.readVersion(tenantId, definitionPublicId, versionNumber);
    }

    @Override
    public Optional<VersionRecord> findEligibleVersion(
            long tenantId, UUID definitionPublicId, boolean activeOnly) {
        return catalogStore.findEligibleVersion(tenantId, definitionPublicId, activeOnly);
    }

    @Override
    public Set<String> eligibleCatalogActions(
            long tenantId, UUID subjectPrincipalPublicId, CatalogItem item) {
        return catalogStore.eligibleCatalogActions(tenantId, subjectPrincipalPublicId, item);
    }

    @Override
    public DefinitionRecord lockDefinition(long tenantId, UUID definitionPublicId) {
        return definitions.lockDefinition(tenantId, definitionPublicId);
    }

    @Override
    public VersionRecord lockVersion(
            long tenantId, UUID definitionPublicId, long versionNumber) {
        return definitions.lockVersion(tenantId, definitionPublicId, versionNumber);
    }

    @Override
    public ScheduleRecord lockSchedule(long tenantId, UUID schedulePublicId) {
        return definitions.lockSchedule(tenantId, schedulePublicId);
    }

    @Override
    public RunRecord lockRun(long tenantId, UUID runPublicId) {
        return runs.lockRun(tenantId, runPublicId);
    }

    public RunRecord readRun(long tenantId, UUID runPublicId) {
        return runs.readRun(tenantId, runPublicId);
    }

    @Override
    public DefinitionRecord insertDefinition(
            long tenantId,
            UUID author,
            CreateDefinitionCommand command,
            OffsetDateTime now) {
        return definitions.insertDefinition(tenantId, author, command, now);
    }

    @Override
    public VersionRecord insertVersion(
            DefinitionRecord definition,
            UUID author,
            CreateVersionCommand command,
            VerifiedArtifact artifact,
            OffsetDateTime now) {
        return definitions.insertVersion(definition, author, command, artifact, now);
    }

    @Override
    public VersionRecord transitionVersion(
            VersionRecord version,
            VersionState target,
            UUID actor,
            UUID receiptId,
            String evidenceDigest,
            String stepUpDigest,
            VerifiedArtifact currentArtifact,
            OffsetDateTime now) {
        return definitions.transitionVersion(
                version, target, actor, receiptId, evidenceDigest, stepUpDigest, currentArtifact,
                now);
    }

    @Override
    public ScheduleRecord insertSchedule(
            DefinitionRecord definition,
            VersionRecord version,
            UUID author,
            CreateScheduleCommand command,
            OffsetDateTime now) {
        return definitions.insertSchedule(definition, version, author, command, now);
    }

    @Override
    public ScheduleRecord transitionSchedule(
            ScheduleRecord schedule,
            ScheduleState target,
            UUID actor,
            UUID receiptId,
            String evidenceDigest,
            String stepUpDigest,
            OffsetDateTime now) {
        return definitions.transitionSchedule(
                schedule, target, actor, receiptId, evidenceDigest, stepUpDigest, now);
    }

    @Override
    public RunRecord insertRun(
            DefinitionRecord definition,
            VersionRecord version,
            UUID actor,
            UUID commandReceiptId,
            QueueRunCommand command,
            String concurrencyKeyDigest,
            OffsetDateTime now) {
        return runs.insertRun(
                definition, version, actor, commandReceiptId, command, concurrencyKeyDigest, now);
    }

    @Override
    public RunRecord retryRun(
            RunRecord run,
            UUID actor,
            UUID commandReceiptId,
            SelectionMode selectionMode,
            long selectedItemCount,
            String selectedItemDigest,
            OffsetDateTime now) {
        return runs.retryRun(
                run, actor, commandReceiptId, selectionMode, selectedItemCount, selectedItemDigest,
                now);
    }

    @Override
    public RetrySelection retrySelection(RunRecord run) {
        return runs.retrySelection(run);
    }

    @Override
    public RunRecord cancelRun(
            RunRecord run, String reason, OffsetDateTime now) {
        return runs.cancelRun(run, reason, now);
    }

    @Override
    public ScheduleExecution lockScheduleForFire(
            long tenantId, UUID schedulePublicId, OffsetDateTime databaseNow) {
        return schedules.lockScheduleForFire(tenantId, schedulePublicId, databaseNow);
    }

    @Override
    public ScheduledRun fireSchedule(
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
            OffsetDateTime now) {
        return schedules.fireSchedule(
                schedule, command, receiptId, fireKey, scheduledInstantUtc, nextFireAt,
                inputReference, inputDigest, scopeType, scopeId, expectedItemCount,
                concurrencyKeyDigest, technicalActor, now);
    }

    @Override
    public void skipMissedFire(
            ScheduleExecution schedule,
            String fireKey,
            OffsetDateTime missedInstantUtc,
            OffsetDateTime nextFireAt,
            UUID correlationId,
            OffsetDateTime now) {
        schedules.skipMissedFire(schedule, fireKey, missedInstantUtc, nextFireAt, correlationId, now);
    }

    @Override
    public RuntimeRun lockRuntimeRun(long tenantId, UUID runPublicId) {
        return worker.lockRuntimeRun(tenantId, runPublicId);
    }

    @Override
    public void assertLiveOriginalAuthority(
            RuntimeRun run, LeaseGrant authority, PreparedItem item) {
        worker.assertLiveOriginalAuthority(run, authority, item);
    }

    @Override
    public void assertLiveCompensationAuthority(
            RuntimeRun run, LeaseGrant authority,
            PreparedCompensation compensation) {
        this.compensation.assertLiveCompensationAuthority(run, authority, compensation);
    }

    @Override
    public LeaseGrant acquireWorkerLease(
            RuntimeRun run,
            UUID leaseToken,
            OffsetDateTime leasedUntil,
            OffsetDateTime now) {
        return worker.acquireWorkerLease(run, leaseToken, leasedUntil, now);
    }

    @Override
    public LeaseGrant renewWorkerLease(
            RuntimeRun run,
            LeaseGrant current,
            OffsetDateTime leasedUntil,
            OffsetDateTime now) {
        return worker.renewWorkerLease(run, current, leasedUntil, now);
    }

    @Override
    public void materializeItems(
            RuntimeRun run,
            LeaseGrant worker,
            List<AutomationRuntimePorts.AutomationItemPlan> items,
            OffsetDateTime now) {
        this.worker.materializeItems(run, worker, items, now);
    }

    @Override
    public PreparedOriginal prepareOriginal(
            RuntimeRun run,
            LeaseGrant worker,
            String requestDigest,
            OffsetDateTime now) {
        return this.worker.prepareOriginal(run, worker, requestDigest, now);
    }

    @Override
    public RuntimeRun finalizeOriginal(
            RuntimeRun run,
            LeaseGrant worker,
            PreparedOriginal prepared,
            List<AutomationRunItemReceiptV1> receipts,
            OffsetDateTime now) {
        return this.worker.finalizeOriginal(run, worker, prepared, receipts, now);
    }

    @Override
    public LeaseGrant acquireReconcilerLease(
            RuntimeRun run,
            UUID leaseToken,
            OffsetDateTime leasedUntil,
            OffsetDateTime now) {
        return reconciliation.acquireReconcilerLease(run, leaseToken, leasedUntil, now);
    }

    @Override
    public RecoveryResult recoverAuthority(
            RuntimeRun run,
            AutomationDomainPolicy.RecoveryDecision decision,
            UUID suppliedToken,
            boolean explicitlyRevoked,
            String reason,
            OffsetDateTime now) {
        return reconciliation.recoverAuthority(run, decision, suppliedToken, explicitlyRevoked, reason, now);
    }

    @Override
    public RuntimeRun reconcileOriginal(
            RuntimeRun run,
            LeaseGrant reconciler,
            UUID itemPublicId,
            String effectIdempotencyKey,
            String effectRequestDigest,
            AutomationRuntimePorts.ReconciliationEvidence evidence,
            OffsetDateTime now) {
        return reconciliation.reconcileOriginal(
                run, reconciler, itemPublicId, effectIdempotencyKey, effectRequestDigest, evidence,
                now);
    }

    @Override
    public PreparedCompensation prepareCompensation(
            RuntimeRun run,
            LeaseGrant reconciler,
            UUID itemPublicId,
            String compensationKey,
            String requestDigest,
            OffsetDateTime now) {
        return compensation.prepareCompensation(
                run, reconciler, itemPublicId, compensationKey, requestDigest, now);
    }

    @Override
    public AutomationRunItemReceiptV1 loadCommittedOriginalReceipt(
            RuntimeRun run, UUID itemPublicId) {
        return compensation.loadCommittedOriginalReceipt(run, itemPublicId);
    }

    public AutomationRunItemReceiptV1 loadOriginalReceipt(
            RuntimeRun run, UUID itemPublicId, int attemptNumber) {
        return compensation.loadOriginalReceipt(run, itemPublicId, attemptNumber);
    }

    @Override
    public RuntimeRun finalizeCompensation(
            RuntimeRun run,
            LeaseGrant reconciler,
            PreparedCompensation prepared,
            String outcome,
            String receiptDigest,
            AutomationRuntimePorts.ReconciliationEvidence trustedEvidence,
            OffsetDateTime now) {
        return compensation.finalizeCompensation(
                run, reconciler, prepared, outcome, receiptDigest, trustedEvidence, now);
    }

    @Override
    public RuntimeRun appendCheckpointAndRecalculate(
            RuntimeRun run,
            LeaseGrant authority,
            String processedItemDigest,
            String trustedReceiptDigest,
            OffsetDateTime now) {
        return compensation.appendCheckpointAndRecalculate(
                run, authority, processedItemDigest, trustedReceiptDigest, now);
    }

    @Override
    public RunDetailResponse runDetail(
            long tenantId,
            UUID runPublicId,
            OffsetDateTime asOf,
            int limit,
            Integer afterAttempt,
            Long afterItem,
            String afterCheckpoint,
            long authorizationRevision,
            UUID correlationId) {
        return catalogStore.runDetail(
                tenantId, runPublicId, asOf, limit, afterAttempt, afterItem, afterCheckpoint,
                authorizationRevision, correlationId);
    }

    public RunActionEligibility runActionEligibility(RunRecord run) {
        return catalogStore.runActionEligibility(run);
    }

    @Override
    public void appendRunEventAndAudit(
            CommandSeal command,
            RunRecord run,
            String transition,
            OffsetDateTime now) {
        evidenceStore.appendRunEventAndAudit(command, run, transition, now);
    }

    public void validateRunEvent(RunRecord run) {
        evidenceStore.validateRunEvent(run);
    }

    public VersionRecord version(ResultSet rs, int row) throws SQLException {
        return mapping.version(rs, row);
    }

    public RuntimeRun runtimeRun(ResultSet rs) throws SQLException {
        return mapping.runtimeRun(rs);
    }

    public SignedManifest signedManifest(ResultSet rs) throws SQLException {
        return mapping.signedManifest(rs);
    }

    public StoredReceipt storedReceipt(ResultSet rs) throws SQLException {
        return mapping.storedReceipt(rs);
    }

    public CommandReceipt receipt(
            CommandSeal command,
            UUID receiptId,
            ReceiptStatus status,
            OffsetDateTime createdAt,
            Long revision,
            String resultRef) {
        return mapping.receipt(command, receiptId, status, createdAt, revision, resultRef);
    }

    public JsonNode readJson(String value) {
        return mapping.readJson(value);
    }

    public String stringify(Object value) {
        return mapping.stringify(value);
    }

    public ReceiptSet currentReceiptSet(
            long tenantId,
            UUID runId,
            UUID runPublicId,
            long runRevision) {
        return evidenceStore.currentReceiptSet(tenantId, runId, runPublicId, runRevision);
    }

    public ReceiptSet currentAttemptReceiptSet(
            long tenantId,
            UUID runId,
            UUID runPublicId,
            int attemptNumber,
            long runRevision) {
        return evidenceStore.currentAttemptReceiptSet(
                tenantId, runId, runPublicId, attemptNumber, runRevision);
    }
}
