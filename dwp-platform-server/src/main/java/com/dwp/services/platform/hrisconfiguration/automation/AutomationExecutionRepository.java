package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationInvocationV1;
import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.AutomationItemPlan;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Tenant-fenced persistence boundary for the non-HTTP SYS-006 execution runtime. */
interface AutomationExecutionRepository {

    OffsetDateTime databaseNow();

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
            RuntimeRun run, UUID leaseToken, OffsetDateTime leasedUntil,
            OffsetDateTime now);

    LeaseGrant renewWorkerLease(
            RuntimeRun run, LeaseGrant current, OffsetDateTime leasedUntil,
            OffsetDateTime now);

    void materializeItems(
            RuntimeRun run,
            LeaseGrant worker,
            List<AutomationItemPlan> items,
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
            RuntimeRun run, UUID leaseToken, OffsetDateTime leasedUntil,
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

    record ScheduleExecution(
            long tenantId,
            UUID scheduleId,
            UUID schedulePublicId,
            UUID definitionId,
            UUID definitionPublicId,
            UUID versionId,
            long pinnedVersionNumber,
            String lifecycleState,
            String versionState,
            String expression,
            String timezone,
            String timezoneDatabaseRevision,
            String inputTemplateRef,
            String inputTemplateDigest,
            String concurrencyKeyTemplate,
            String schedulePolicyDigest,
            long approvedSubjectRevision,
            long objectRevision,
            long fireCursorRevision,
            OffsetDateTime nextFireAt,
            SignedManifest manifest,
            String definitionDigest,
            String executionPolicyDigest,
            String compatibilityBaselineDigest,
            String retryMode,
            int maxAttempts) {
    }

    record ScheduledRun(
            AutomationRepository.RunRecord run,
            long nextFireCursorRevision) {
    }

    record RuntimeRun(
            AutomationRepository.RunRecord run,
            SignedManifest manifest,
            String definitionDigest,
            String executionPolicyDigest,
            String compatibilityBaselineDigest,
            String scopeType,
            UUID scopeId,
            OffsetDateTime requestedAt,
            String requestedByActorRef,
            String idempotencyKey,
            String inputReference,
            String inputDigest,
            int expectedItemCount,
            UUID correlationId,
            int attemptNumber,
            UUID runAttemptId,
            String selectedItemDigest,
            long selectedItemCount,
            String compensationPolicyRef,
            String compensationPolicyDigest,
            long workerFenceEpoch,
            UUID workerLeaseToken,
            OffsetDateTime workerLeasedUntil,
            Long workerControlFenceSnapshot,
            long reconcilerFenceEpoch,
            UUID reconcilerLeaseToken,
            OffsetDateTime reconcilerLeasedUntil,
            Long reconcilerControlFenceSnapshot,
            long controlFenceEpoch,
            OffsetDateTime reconciliationRequiredAt,
            String reconciliationRequiredReason) {

        AutomationDomainPolicy.Lease workerLease() {
            return workerLeaseToken == null ? null : new AutomationDomainPolicy.Lease(
                    workerLeaseToken, workerFenceEpoch, workerControlFenceSnapshot,
                    workerLeasedUntil);
        }

        AutomationDomainPolicy.Lease reconcilerLease() {
            return reconcilerLeaseToken == null ? null : new AutomationDomainPolicy.Lease(
                    reconcilerLeaseToken, reconcilerFenceEpoch,
                    reconcilerControlFenceSnapshot, reconcilerLeasedUntil);
        }
    }

    record LeaseGrant(
            AutomationDomainPolicy.AuthorityKind authorityKind,
            long tenantId,
            UUID runPublicId,
            int attemptNumber,
            UUID token,
            long authorityFenceEpoch,
            long controlFenceEpoch,
            OffsetDateTime leasedUntil) {

        AutomationDomainPolicy.Lease asLease() {
            return new AutomationDomainPolicy.Lease(
                    token, authorityFenceEpoch, controlFenceEpoch, leasedUntil);
        }
    }

    record PreparedOriginal(
            AutomationInvocationV1 invocation,
            SignedManifest manifest,
            OffsetDateTime verifierFreshUntil,
            List<PreparedItem> items) {
        public PreparedOriginal {
            items = List.copyOf(items);
        }
    }

    record PreparedItem(
            UUID itemPublicId,
            long itemSequence,
            String subjectRef,
            Instant acceptedAt,
            String effectIdempotencyKey,
            String effectRequestDigest) {
    }

    record PreparedCompensation(
            AutomationInvocationV1 invocation,
            SignedManifest manifest,
            OffsetDateTime verifierFreshUntil,
            UUID itemPublicId,
            long itemSequence,
            String subjectRef,
            Instant acceptedAt,
            long runRevision,
            int attemptNumber,
            String compensationIdempotencyKey,
            String compensationRequestDigest,
            String originalEffectIdempotencyKey,
            String originalEffectReceiptDigest) {
    }

    record RecoveryResult(
            AutomationDomainPolicy.RecoveryBranch branch,
            long controlFenceEpoch,
            long workerFenceEpoch,
            long reconcilerFenceEpoch,
            boolean reconciliationRequired,
            boolean externalDispatchPerformed) {
    }
}
