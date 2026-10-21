package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationInvocationV1;
import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.LeaseGrant;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.PreparedCompensation;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.PreparedOriginal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.RuntimeRun;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.ScheduleExecution;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.HandlerIdentity;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.AutomationItemPlan;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.ExternalEffectEvidencePort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.SignedArtifactVerifierPort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.TypedAutomationHandlerPort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transaction-capable SYS-006 execution owner. There is deliberately no
 * scheduler annotation and no production handler/evidence provider in this slice.
 */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.automation-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class AutomationRuntimeService {

    private static final Duration MIN_LEASE = Duration.ofSeconds(5);
    private static final Duration MAX_LEASE = Duration.ofMinutes(5);

    private final AutomationRepository control;
    private final AutomationExecutionRepository execution;
    private final AutomationHandlerRegistryPort registry;
    private final SignedArtifactVerifierPort verifier;
    private final ExternalEffectEvidencePort evidence;
    private final Map<HandlerIdentity, TypedAutomationHandlerPort> handlers;
    private final AutomationCanonicalJson canonical;
    AutomationRuntimeService(
            AutomationRepository control,
            AutomationExecutionRepository execution,
            AutomationHandlerRegistryPort registry,
            SignedArtifactVerifierPort verifier,
            List<TypedAutomationHandlerPort> handlers,
            List<ExternalEffectEvidencePort> evidenceProviders,
            ObjectMapper json) {
        this.control = control;
        this.execution = execution;
        this.registry = registry;
        this.verifier = verifier;
        this.canonical = new AutomationCanonicalJson(json);
        Map<HandlerIdentity, TypedAutomationHandlerPort> indexed = new HashMap<>();
        for (TypedAutomationHandlerPort handler : handlers) {
            if (indexed.putIfAbsent(handler.identity(), handler) != null) {
                throw new IllegalStateException("AUTOMATION_HANDLER_IDENTITY_DUPLICATE");
            }
        }
        this.handlers = Map.copyOf(indexed);
        if (evidenceProviders.size() > 1) {
            throw new IllegalStateException("AUTOMATION_EVIDENCE_PROVIDER_AMBIGUOUS");
        }
        this.evidence = evidenceProviders.isEmpty() ? null : evidenceProviders.getFirst();
    }

    @Transactional
    ScheduleFireOutcome fireSchedule(SchedulerAuthority authority, FireRequest request) {
        bind(authority.tenantId(), authority.subjectPrincipalPublicId());
        OffsetDateTime now = now();
        ScheduleExecution schedule = execution.lockScheduleForFire(
                authority.tenantId(), request.schedulePublicId(), now);
        boolean missed = request.scheduledInstantUtc()
                .isBefore(request.schedulerWindowStart());
        String fireKey = AutomationDomainPolicy.scheduleFireKey(
                schedule.tenantId(), schedule.schedulePublicId(),
                schedule.objectRevision(), schedule.approvedSubjectRevision(),
                schedule.schedulePolicyDigest(), request.scheduledInstantUtc());
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("schedulePublicId", schedule.schedulePublicId());
        target.put("scheduleObjectRevision", schedule.objectRevision());
        target.put("approvedSubjectRevision", schedule.approvedSubjectRevision());
        target.put("schedulePolicyDigest", schedule.schedulePolicyDigest());
        target.put("scheduledInstantUtc", request.scheduledInstantUtc());
        target.put("definitionPublicId", schedule.definitionPublicId());
        target.put("pinnedVersionNumber", schedule.pinnedVersionNumber());
        target.put("inputDigest", request.inputDigest());
        String concurrencyDigest = AutomationDomainPolicy.renderConcurrencyKey(
                schedule.concurrencyKeyTemplate(), schedule.tenantId(),
                schedule.definitionPublicId(), request.scopeType(), request.scopeId());
        target.put("concurrencyKeyDigest", concurrencyDigest);
        CommandSeal seal = new CommandSeal(
                authority.tenantId(), authority.subjectPrincipalPublicId(),
                "QUEUE_AUTOMATION_RUN", AutomationService.QUEUE_RUN,
                authority.routeContractKey(), authority.contextKey(),
                authority.contextScopeKey(), authority.decisionRevision(),
                authority.populationScopeDigest(), authority.fieldPolicyRevision(),
                authority.authorizationRevision(), authority.purposeCode(), fireKey,
                canonical.digest(target), authority.correlationId());
        var replay = control.findReplay(seal);
        if (replay.isPresent()) {
            return new ScheduleFireOutcome(false, fireKey, schedule.nextFireAt(),
                    replay.get());
        }
        requireScheduleCas(schedule, request);
        VerifiedArtifact current = revalidate(schedule.manifest(),
                schedule.definitionDigest(), schedule.executionPolicyDigest(),
                schedule.compatibilityBaselineDigest(), now);
        if (!current.signedManifest().manifest().allowedScopeTypes()
                .contains(request.scopeType())) {
            throw new IllegalStateException("AUTOMATION_SCOPE_NOT_ALLOWED");
        }
        OffsetDateTime next = nextFire(schedule, missed
                ? request.schedulerWindowStart() : request.scheduledInstantUtc());
        if (missed) {
            execution.skipMissedFire(schedule, fireKey,
                    request.scheduledInstantUtc(), next, authority.correlationId(), now);
            return new ScheduleFireOutcome(true, fireKey, next, null);
        }
        var accepted = control.beginCommand(seal, now);
        var fired = execution.fireSchedule(
                schedule, seal, accepted.receiptId(), fireKey,
                request.scheduledInstantUtc(), next, request.inputReference(),
                request.inputDigest(), request.scopeType(), request.scopeId(),
                request.expectedItemCount(), concurrencyDigest,
                authority.subjectPrincipalPublicId(), now);
        control.appendRunEventAndAudit(seal, fired.run(), "SCHEDULE_FIRED", now);
        var completed = control.completeCommand(
                seal, accepted.receiptId(), fired.run().publicId(),
                fired.run().objectRevision(),
                "/v1/admin/hris/automation-runs/" + fired.run().publicId(), now);
        return new ScheduleFireOutcome(false, fireKey, next, completed);
    }

    @Transactional
    LeaseGrant acquireWorker(
            long tenantId, UUID subject, UUID runPublicId,
            long expectedRunRevision, Duration duration) {
        bind(tenantId, subject);
        OffsetDateTime now = now();
        RuntimeRun run = execution.lockRuntimeRun(tenantId, runPublicId);
        requireRevision(run, expectedRunRevision);
        revalidate(run, now);
        LeaseGrant granted = execution.acquireWorkerLease(
                run, UUID.randomUUID(), now.plus(validLease(duration)), now);
        RuntimeRun changed = execution.lockRuntimeRun(tenantId, runPublicId);
        appendRuntimeEvent(subject, changed, "WORKER_LEASED", now);
        return granted;
    }

    @Transactional
    LeaseGrant renewWorker(
            long tenantId, UUID subject, UUID runPublicId,
            LeaseGrant current, Duration duration) {
        bind(tenantId, subject);
        OffsetDateTime now = now();
        RuntimeRun run = execution.lockRuntimeRun(tenantId, runPublicId);
        AutomationDomainPolicy.requireActiveFence(
                AutomationDomainPolicy.AuthorityKind.WORKER,
                run.workerLease(), run.reconcilerLease(), current.token(),
                current.authorityFenceEpoch(), current.controlFenceEpoch(), now,
                false, "ORIGINAL");
        revalidate(run, now);
        return execution.renewWorkerLease(
                run, current, now.plus(validLease(duration)), now);
    }

    /** Materialization is side-effect free and precedes every PREPARED receipt. */
    @Transactional
    void materialize(
            long tenantId, UUID subject, UUID runPublicId, LeaseGrant worker) {
        bind(tenantId, subject);
        OffsetDateTime now = now();
        RuntimeRun run = execution.lockRuntimeRun(tenantId, runPublicId);
        requireWorker(run, worker, now, false);
        VerifiedArtifact current = revalidate(run, now);
        AutomationInvocationV1 invocation = invocation(run);
        TypedAutomationHandlerPort handler = handler(current.signedManifest().identity());
        List<AutomationItemPlan> items = List.copyOf(handler.materialize(invocation));
        validateMaterialization(run, items);
        execution.materializeItems(run, worker, items, now);
        RuntimeRun changed = execution.lockRuntimeRun(tenantId, runPublicId);
        appendRuntimeEvent(subject, changed, "ITEMS_MATERIALIZED", now);
    }

    @Transactional
    PreparedOriginal prepareOriginal(
            long tenantId, UUID subject, UUID runPublicId, LeaseGrant worker) {
        bind(tenantId, subject);
        OffsetDateTime now = now();
        RuntimeRun run = execution.lockRuntimeRun(tenantId, runPublicId);
        requireWorker(run, worker, now, true);
        VerifiedArtifact current = revalidate(run, now);
        String digest = canonical.digest(Map.of(
                "runPublicId", run.run().publicId(),
                "runRevisionDecimal", Long.toString(run.run().objectRevision()),
                "attemptNumber", run.attemptNumber(),
                "handler", current.signedManifest().identity()));
        PreparedOriginal persisted = execution.prepareOriginal(
                run, worker, digest, now);
        return new PreparedOriginal(persisted.invocation(),
                current.signedManifest(), current.freshUntil(), persisted.items());
    }

    @Transactional
    List<AutomationRunItemReceiptV1> invokeOriginal(
            long tenantId,
            UUID subject,
            UUID runPublicId,
            LeaseGrant worker,
            PreparedOriginal prepared) {
        bind(tenantId, subject);
        OffsetDateTime now = now();
        RuntimeRun run = execution.lockRuntimeRun(tenantId, runPublicId);
        requireWorker(run, worker, now, true);
        VerifiedArtifact current = revalidate(run, now);
        if (!current.signedManifest().equals(prepared.manifest())
                || !prepared.invocation().equals(invocation(run))) {
            throw new IllegalStateException("AUTOMATION_DISPATCH_MANIFEST_CHANGED");
        }
        for (var item : prepared.items()) {
            execution.assertLiveOriginalAuthority(run, worker, item);
        }
        List<AutomationRuntimePorts.AutomationEffectRequest> requests = prepared.items()
                .stream().map(item -> new AutomationRuntimePorts.AutomationEffectRequest(
                        item.itemPublicId(), item.itemSequence(), item.subjectRef(),
                        run.run().objectRevision(), run.attemptNumber(),
                        item.acceptedAt(),
                        item.effectIdempotencyKey(), item.effectRequestDigest()))
                .toList();
        return List.copyOf(handler(prepared.manifest().identity())
                .invoke(prepared.invocation(), requests));
    }

    @Transactional
    RuntimeRun finalizeOriginal(
            long tenantId,
            UUID subject,
            UUID runPublicId,
            LeaseGrant worker,
            PreparedOriginal prepared,
            List<AutomationRunItemReceiptV1> receipts) {
        bind(tenantId, subject);
        OffsetDateTime now = now();
        RuntimeRun run = execution.lockRuntimeRun(tenantId, runPublicId);
        requireWorker(run, worker, now, true);
        VerifiedArtifact current = revalidate(run, now);
        if (!current.signedManifest().equals(prepared.manifest())
                || !prepared.invocation().equals(invocation(run))) {
            throw new IllegalStateException("AUTOMATION_DISPATCH_CONTEXT_CHANGED");
        }
        List<AutomationRunItemReceiptV1> normalized =
                validateAndNormalizeReceipts(run, prepared, receipts);
        RuntimeRun changed = execution.finalizeOriginal(
                run, worker, prepared, normalized, now);
        RuntimeRun checkpointed = execution.appendCheckpointAndRecalculate(
                changed, worker, receiptSetDigest(changed, normalized), null, now);
        appendRuntimeEvent(subject, checkpointed, "ORIGINAL_EFFECTS_CHECKPOINTED", now);
        return checkpointed;
    }

    @Transactional
    AutomationExecutionRepository.RecoveryResult recover(
            long tenantId,
            UUID subject,
            UUID runPublicId,
            long expectedRunRevision,
            UUID suppliedLeaseToken,
            boolean explicitlyRevoked,
            String reason) {
        bind(tenantId, subject);
        OffsetDateTime now = now();
        RuntimeRun run = execution.lockRuntimeRun(tenantId, runPublicId);
        requireRevision(run, expectedRunRevision);
        AutomationDomainPolicy.RecoveryDecision decision =
                AutomationDomainPolicy.ownerRecovery(
                        run.controlFenceEpoch(), run.workerFenceEpoch(),
                        run.reconcilerFenceEpoch(), run.workerLease(),
                        run.reconcilerLease(), suppliedLeaseToken, now,
                        explicitlyRevoked);
        if (decision.externalDispatchAuthorized()) {
            throw new IllegalStateException("OWNER_RECOVERY_EXTERNAL_DISPATCH_FORBIDDEN");
        }
        if (!java.util.Set.of(
                "CANCEL_EFFECT_EVIDENCE_REQUIRED",
                "CANCEL_COMPENSATION_REQUIRED",
                "LEASE_EXPIRED_EFFECT_EVIDENCE_REQUIRED",
                "RESULT_UNKNOWN_EVIDENCE_REQUIRED").contains(reason)) {
            throw new IllegalArgumentException("AUTOMATION_RECOVERY_REASON_INVALID");
        }
        AutomationExecutionRepository.RecoveryResult recovered = execution.recoverAuthority(
                run, decision, suppliedLeaseToken, explicitlyRevoked, reason, now);
        RuntimeRun changed = execution.lockRuntimeRun(tenantId, runPublicId);
        appendRuntimeEvent(subject, changed, "AUTHORITY_RECOVERED", now);
        return recovered;
    }

    @Transactional
    LeaseGrant acquireReconciler(
            long tenantId, UUID subject, UUID runPublicId,
            long expectedRunRevision, Duration duration) {
        bind(tenantId, subject);
        OffsetDateTime now = now();
        RuntimeRun run = execution.lockRuntimeRun(tenantId, runPublicId);
        requireRevision(run, expectedRunRevision);
        revalidate(run, now);
        if (run.reconciliationRequiredAt() == null
                || run.reconciliationRequiredReason() == null) {
            throw new IllegalStateException("AUTOMATION_RECONCILIATION_NOT_REQUIRED");
        }
        LeaseGrant granted = execution.acquireReconcilerLease(
                run, UUID.randomUUID(), now.plus(validLease(duration)), now);
        RuntimeRun changed = execution.lockRuntimeRun(tenantId, runPublicId);
        appendRuntimeEvent(subject, changed, "RECONCILER_LEASED", now);
        return granted;
    }

    @Transactional
    RuntimeRun reconcileOriginal(
            long tenantId,
            UUID subject,
            UUID runPublicId,
            UUID itemPublicId,
            LeaseGrant reconciler,
            String effectKey,
            String requestDigest) {
        bind(tenantId, subject);
        OffsetDateTime now = now();
        RuntimeRun run = execution.lockRuntimeRun(tenantId, runPublicId);
        requireReconciler(run, reconciler, now, false);
        revalidate(run, now);
        if (evidence == null) {
            throw new IllegalStateException("AUTOMATION_EFFECT_EVIDENCE_UNAVAILABLE");
        }
        var resolved = evidence.resolve(
                tenantId, runPublicId, itemPublicId, effectKey, requestDigest);
        if (!resolved.freshUntil().isAfter(now)) {
            throw new IllegalStateException("AUTOMATION_EFFECT_EVIDENCE_STALE");
        }
        RuntimeRun reconciled = execution.reconcileOriginal(
                run, reconciler, itemPublicId, effectKey, requestDigest,
                resolved, now);
        RuntimeRun checkpointed = execution.appendCheckpointAndRecalculate(
                reconciled, reconciler, resolved.evidenceDigest(),
                resolved.evidenceDigest(), now);
        appendRuntimeEvent(subject, checkpointed, "ORIGINAL_EFFECT_RECONCILED", now);
        return checkpointed;
    }

    @Transactional
    PreparedCompensation prepareCompensation(
            long tenantId,
            UUID subject,
            UUID runPublicId,
            UUID itemPublicId,
            LeaseGrant reconciler) {
        bind(tenantId, subject);
        OffsetDateTime now = now();
        RuntimeRun run = execution.lockRuntimeRun(tenantId, runPublicId);
        requireReconciler(run, reconciler, now, true);
        VerifiedArtifact current = revalidate(run, now);
        if (run.compensationPolicyRef() == null) {
            throw new IllegalStateException("AUTOMATION_COMPENSATION_NOT_DECLARED");
        }
        String key = AutomationDomainPolicy.sha256(("compensation\u001f"
                + runPublicId + "\u001f" + itemPublicId + "\u001f"
                + run.run().objectRevision()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String digest = canonical.digest(Map.of(
                "compensationPolicyRef", run.compensationPolicyRef(),
                "compensationPolicyDigest", run.compensationPolicyDigest(),
                "itemPublicId", itemPublicId,
                "runPublicId", runPublicId,
                "runRevisionDecimal", Long.toString(run.run().objectRevision())));
        PreparedCompensation persisted = execution.prepareCompensation(
                run, reconciler, itemPublicId, key, digest, now);
        return new PreparedCompensation(persisted.invocation(),
                current.signedManifest(), current.freshUntil(), itemPublicId,
                persisted.itemSequence(), persisted.subjectRef(),
                persisted.acceptedAt(), persisted.runRevision(),
                persisted.attemptNumber(),
                persisted.compensationIdempotencyKey(),
                persisted.compensationRequestDigest(),
                persisted.originalEffectIdempotencyKey(),
                persisted.originalEffectReceiptDigest());
    }

    @Transactional
    AutomationRunItemReceiptV1 invokeCompensation(
            long tenantId,
            UUID subject,
            UUID runPublicId,
            LeaseGrant reconciler,
            PreparedCompensation prepared) {
        bind(tenantId, subject);
        OffsetDateTime now = now();
        RuntimeRun run = execution.lockRuntimeRun(tenantId, runPublicId);
        requireReconciler(run, reconciler, now, true);
        VerifiedArtifact current = revalidate(run, now);
        if (!current.signedManifest().equals(prepared.manifest())
                || !prepared.invocation().equals(invocation(run))
                || prepared.runRevision() != run.run().objectRevision()
                || prepared.attemptNumber() != run.attemptNumber()) {
            throw new IllegalStateException("AUTOMATION_DISPATCH_MANIFEST_CHANGED");
        }
        execution.assertLiveCompensationAuthority(run, reconciler, prepared);
        AutomationRunItemReceiptV1 original = requireCommittedOriginal(run, prepared);
        requireCompensationContext(prepared, original);
        List<AutomationRunItemReceiptV1> returned = List.copyOf(
                handler(prepared.manifest().identity()).compensate(
                        prepared.invocation(), List.of(original),
                        new AutomationRuntimePorts.AutomationCompensationRequest(
                                prepared.itemPublicId(),
                                prepared.runRevision(), prepared.attemptNumber(),
                                prepared.compensationIdempotencyKey(),
                                prepared.compensationRequestDigest(),
                                prepared.originalEffectIdempotencyKey(),
                                prepared.originalEffectReceiptDigest())));
        if (returned.size() != 1) {
            throw new IllegalStateException(
                    "AUTOMATION_COMPENSATION_RECEIPT_SET_MISMATCH");
        }
        AutomationRunItemReceiptV1 receipt =
                Sys006TypedArtifactContract.requireInteroperable(
                        returned.getFirst());
        if (!receipt.runId().equals(runPublicId)
                || receipt.runRevision() != run.run().objectRevision()
                || !receipt.itemId().equals(prepared.itemPublicId())
                || receipt.itemSequence() != original.itemSequence()
                || !receipt.subjectRef().equals(original.subjectRef())
                || !receipt.acceptedAt().equals(original.acceptedAt())
                || receipt.attemptNumber() != run.attemptNumber()
                || !receipt.correlationId().equals(run.correlationId())
                || receipt.completedAt() == null
                || receipt.completedAt().isBefore(receipt.acceptedAt())
                || !java.util.Set.of("SUCCEEDED", "FAILED", "CANCELED",
                "DEAD_LETTERED").contains(receipt.state())) {
            throw new IllegalStateException(
                    "AUTOMATION_COMPENSATION_RECEIPT_MISMATCH");
        }
        requireSafeTerminalDisposition(receipt);
        return normalizeReceipt(run, receipt);
    }

    @Transactional
    RuntimeRun finalizeCompensation(
            long tenantId,
            UUID subject,
            UUID runPublicId,
            LeaseGrant reconciler,
            PreparedCompensation prepared,
            AutomationRunItemReceiptV1 receipt) {
        bind(tenantId, subject);
        OffsetDateTime now = now();
        RuntimeRun run = execution.lockRuntimeRun(tenantId, runPublicId);
        requireReconciler(run, reconciler, now, true);
        VerifiedArtifact current = revalidate(run, now);
        if (!current.signedManifest().equals(prepared.manifest())
                || !prepared.invocation().equals(invocation(run))
                || prepared.runRevision() != run.run().objectRevision()
                || prepared.attemptNumber() != run.attemptNumber()) {
            throw new IllegalStateException("AUTOMATION_DISPATCH_CONTEXT_CHANGED");
        }
        AutomationRunItemReceiptV1 original = requireCommittedOriginal(run, prepared);
        requireCompensationContext(prepared, original);
        AutomationRunItemReceiptV1 checked =
                Sys006TypedArtifactContract.requireInteroperable(receipt);
        if (!checked.runId().equals(runPublicId)
                || checked.runRevision() != run.run().objectRevision()
                || !checked.itemId().equals(prepared.itemPublicId())
                || checked.itemSequence() != original.itemSequence()
                || !checked.subjectRef().equals(original.subjectRef())
                || !checked.acceptedAt().equals(original.acceptedAt())
                || checked.attemptNumber() != run.attemptNumber()
                || !checked.correlationId().equals(run.correlationId())
                || checked.completedAt() == null
                || checked.completedAt().isBefore(checked.acceptedAt())
                || !java.util.Set.of("SUCCEEDED", "FAILED", "CANCELED",
                "DEAD_LETTERED").contains(checked.state())) {
            throw new IllegalStateException(
                    "AUTOMATION_COMPENSATION_RECEIPT_MISMATCH");
        }
        requireSafeTerminalDisposition(checked);
        AutomationRunItemReceiptV1 normalized = normalizeReceipt(run, checked);
        String outcome = "SUCCEEDED".equals(normalized.state())
                ? "COMMITTED" : "RESULT_UNKNOWN";
        String receiptDigest = canonical.digest(normalized);
        RuntimeRun finalized = execution.finalizeCompensation(
                run, reconciler, prepared, outcome, receiptDigest, null, now);
        RuntimeRun checkpointed = execution.appendCheckpointAndRecalculate(
                finalized, reconciler, receiptDigest, receiptDigest, now);
        appendRuntimeEvent(subject, checkpointed, "COMPENSATION_CHECKPOINTED", now);
        return checkpointed;
    }

    @Transactional
    RuntimeRun reconcilePreparedCompensation(
            long tenantId,
            UUID subject,
            UUID runPublicId,
            LeaseGrant newReconciler,
            PreparedCompensation prepared) {
        bind(tenantId, subject);
        OffsetDateTime now = now();
        RuntimeRun run = execution.lockRuntimeRun(tenantId, runPublicId);
        requireReconciler(run, newReconciler, now, false);
        revalidate(run, now);
        if (evidence == null) {
            throw new IllegalStateException("AUTOMATION_EFFECT_EVIDENCE_UNAVAILABLE");
        }
        var resolved = evidence.resolve(
                tenantId, runPublicId, prepared.itemPublicId(),
                prepared.compensationIdempotencyKey(),
                prepared.compensationRequestDigest());
        if (!resolved.freshUntil().isAfter(now)) {
            throw new IllegalStateException("AUTOMATION_EFFECT_EVIDENCE_STALE");
        }
        RuntimeRun finalized = execution.finalizeCompensation(
                run, newReconciler, prepared, resolved.outcome(),
                resolved.receiptDigest(), resolved, now);
        RuntimeRun checkpointed = execution.appendCheckpointAndRecalculate(
                finalized, newReconciler, resolved.evidenceDigest(),
                resolved.evidenceDigest(), now);
        appendRuntimeEvent(subject, checkpointed,
                "COMPENSATION_EVIDENCE_RECONCILED", now);
        return checkpointed;
    }

    private void appendRuntimeEvent(
            UUID subject, RuntimeRun changed, String transition,
            OffsetDateTime now) {
        Map<String, Object> sealDocument = new LinkedHashMap<>();
        sealDocument.put("runPublicId", changed.run().publicId());
        sealDocument.put("runRevisionDecimal",
                Long.toString(changed.run().objectRevision()));
        sealDocument.put("transition", transition);
        CommandSeal seal = new CommandSeal(
                changed.run().tenantId(), subject, "SYS006_RUNTIME_TRANSITION",
                "hris.internal.sys006.runtime-transition",
                "internal.hris.sys006.runtime", "TENANT",
                Long.toString(changed.run().tenantId()),
                "runtime:" + changed.run().objectRevision(),
                AutomationDomainPolicy.sha256(("tenant\u001f"
                        + changed.run().tenantId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                changed.run().objectRevision(), changed.run().objectRevision(),
                "HRIS_SYSTEM_ADMIN", "runtime:" + changed.run().publicId()
                        + ":" + changed.run().objectRevision(),
                canonical.digest(sealDocument), changed.correlationId());
        control.appendRunEventAndAudit(seal, changed.run(), transition, now);
    }

    private VerifiedArtifact revalidate(RuntimeRun run, OffsetDateTime now) {
        return revalidate(run.manifest(), run.definitionDigest(),
                run.executionPolicyDigest(), run.compatibilityBaselineDigest(), now);
    }

    private VerifiedArtifact revalidate(
            AutomationHandlerRegistryPort.SignedManifest manifest,
            String definitionDigest,
            String executionPolicyDigest,
            String compatibilityDigest,
            OffsetDateTime now) {
        VerifiedArtifact current = verifier.revalidate(
                manifest, definitionDigest, executionPolicyDigest, compatibilityDigest);
        AutomationRuntimePorts.requireFresh(
                registry, current.signedManifest(), current.freshUntil(), now);
        if (!current.signedManifest().equals(manifest)) {
            throw new IllegalStateException("AUTOMATION_HANDLER_EVIDENCE_MISMATCH");
        }
        return current;
    }

    private void requireWorker(
            RuntimeRun run, LeaseGrant worker, OffsetDateTime now,
            boolean externalDispatch) {
        AutomationDomainPolicy.requireActiveFence(
                AutomationDomainPolicy.AuthorityKind.WORKER,
                run.workerLease(), run.reconcilerLease(), worker.token(),
                worker.authorityFenceEpoch(), worker.controlFenceEpoch(), now,
                externalDispatch, "ORIGINAL");
    }

    private void requireReconciler(
            RuntimeRun run, LeaseGrant reconciler, OffsetDateTime now,
            boolean externalDispatch) {
        AutomationDomainPolicy.requireActiveFence(
                AutomationDomainPolicy.AuthorityKind.RECONCILER,
                run.workerLease(), run.reconcilerLease(), reconciler.token(),
                reconciler.authorityFenceEpoch(), reconciler.controlFenceEpoch(), now,
                externalDispatch, "COMPENSATION");
    }

    private void bind(long tenantId, UUID subject) {
        if (tenantId < 1 || subject == null) {
            throw new IllegalArgumentException("AUTOMATION_RUNTIME_AUTHORITY_REQUIRED");
        }
        control.bindTenantPrincipal(tenantId, subject);
    }

    private static Duration validLease(Duration duration) {
        if (duration == null || duration.compareTo(MIN_LEASE) < 0
                || duration.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("AUTOMATION_LEASE_DURATION_INVALID");
        }
        return duration;
    }

    private static void requireRevision(RuntimeRun run, long expected) {
        if (expected < 1 || run.run().objectRevision() != expected) {
            throw new IllegalStateException("AUTOMATION_RUN_CAS_CONFLICT");
        }
    }

    private static void requireScheduleCas(
            ScheduleExecution schedule, FireRequest request) {
        if (!"ACTIVE".equals(schedule.lifecycleState())
                || !"ACTIVE".equals(schedule.versionState())
                || schedule.approvedSubjectRevision() < 1
                || schedule.objectRevision() != request.expectedObjectRevision()
                || schedule.fireCursorRevision() != request.expectedFireCursorRevision()
                || schedule.nextFireAt() == null
                || !schedule.nextFireAt().isEqual(request.scheduledInstantUtc())) {
            throw new IllegalStateException("AUTOMATION_SCHEDULE_FIRE_CAS_CONFLICT");
        }
    }

    private static OffsetDateTime nextFire(
            ScheduleExecution schedule, OffsetDateTime after) {
        return AutomationDomainPolicy.nextScheduleFire(
                schedule.expression(), schedule.timezone(),
                schedule.timezoneDatabaseRevision(), after);
    }

    private TypedAutomationHandlerPort handler(HandlerIdentity identity) {
        TypedAutomationHandlerPort handler = handlers.get(identity);
        if (handler == null) {
            throw new IllegalStateException("AUTOMATION_TYPED_HANDLER_UNAVAILABLE");
        }
        return handler;
    }

    private static AutomationInvocationV1 invocation(RuntimeRun run) {
        var manifest = run.manifest().manifest();
        return new AutomationInvocationV1(
                run.run().publicId(), run.run().definitionPublicId(),
                run.run().versionNumber(), manifest.handlerKey(),
                manifest.contractVersion(), run.scopeType(), run.scopeId(),
                run.requestedAt().toInstant(), run.requestedByActorRef(),
                run.idempotencyKey(), run.inputReference(), run.inputDigest(),
                run.expectedItemCount(), run.correlationId());
    }

    private static void validateMaterialization(
            RuntimeRun run, List<AutomationItemPlan> items) {
        if (items.size() != run.expectedItemCount()) {
            throw new IllegalStateException("AUTOMATION_ITEM_COUNT_MISMATCH");
        }
        HashSet<UUID> ids = new HashSet<>();
        HashSet<Long> sequences = new HashSet<>();
        for (AutomationItemPlan item : items) {
            if (!ids.add(item.itemId()) || !sequences.add(item.itemSequence())) {
                throw new IllegalStateException("AUTOMATION_ITEM_IDENTITY_DUPLICATE");
            }
        }
        for (long expected = 1; expected <= items.size(); expected++) {
            if (!sequences.contains(expected)) {
                throw new IllegalStateException(
                        "AUTOMATION_ITEM_SEQUENCE_NOT_CONTIGUOUS");
            }
        }
    }

    private static void requireCompensationContext(
            PreparedCompensation prepared,
            AutomationRunItemReceiptV1 original) {
        if (!original.itemId().equals(prepared.itemPublicId())
                || original.itemSequence() != prepared.itemSequence()
                || !original.subjectRef().equals(prepared.subjectRef())
                || !original.acceptedAt().equals(prepared.acceptedAt())) {
            throw new IllegalStateException(
                    "AUTOMATION_COMPENSATION_CONTEXT_SUBSTITUTED");
        }
    }

    private AutomationRunItemReceiptV1 requireCommittedOriginal(
            RuntimeRun run, PreparedCompensation prepared) {
        AutomationRunItemReceiptV1 original =
                Sys006TypedArtifactContract.requireInteroperable(
                        execution.loadCommittedOriginalReceipt(
                                run, prepared.itemPublicId()));
        if (original.completedAt() == null
                || original.completedAt().isBefore(original.acceptedAt())
                || !java.util.Set.of("SUCCEEDED", "FAILED", "CANCELED",
                "DEAD_LETTERED").contains(original.state())
                || !canonical.digest(original).equals(
                prepared.originalEffectReceiptDigest())) {
            throw new IllegalStateException(
                    "AUTOMATION_COMPENSATION_ORIGINAL_RECEIPT_INVALID");
        }
        return original;
    }

    private static List<AutomationRunItemReceiptV1> validateAndNormalizeReceipts(
            RuntimeRun run,
            PreparedOriginal prepared,
            List<AutomationRunItemReceiptV1> receipts) {
        Map<UUID, AutomationExecutionRepository.PreparedItem> preparedById =
                prepared.items().stream().collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                AutomationExecutionRepository.PreparedItem::itemPublicId,
                                item -> item));
        Map<UUID, AutomationRunItemReceiptV1> indexed = new HashMap<>();
        for (AutomationRunItemReceiptV1 raw : receipts) {
            AutomationRunItemReceiptV1 receipt =
                    Sys006TypedArtifactContract.requireInteroperable(raw);
            AutomationExecutionRepository.PreparedItem preparedItem =
                    preparedById.get(receipt.itemId());
            if (!receipt.runId().equals(run.run().publicId())
                    || receipt.runRevision() != run.run().objectRevision()
                    || receipt.attemptNumber() != run.attemptNumber()
                    || !receipt.correlationId().equals(run.correlationId())
                    || preparedItem == null
                    || receipt.itemSequence() != preparedItem.itemSequence()
                    || !receipt.subjectRef().equals(preparedItem.subjectRef())
                    || !receipt.acceptedAt().equals(preparedItem.acceptedAt())) {
                throw new IllegalStateException("AUTOMATION_HANDLER_RECEIPT_MISMATCH");
            }
            if (!java.util.Set.of("SUCCEEDED", "FAILED", "CANCELED",
                    "DEAD_LETTERED").contains(receipt.state())
                    || receipt.completedAt() == null
                    || receipt.completedAt().isBefore(receipt.acceptedAt())) {
                throw new IllegalStateException(
                        "AUTOMATION_HANDLER_RECEIPT_NOT_TERMINAL");
            }
            requireSafeTerminalDisposition(receipt);
            AutomationRunItemReceiptV1 normalized = normalizeReceipt(run, receipt);
            if (indexed.putIfAbsent(normalized.itemId(), normalized) != null) {
                throw new IllegalStateException("AUTOMATION_HANDLER_RECEIPT_MISMATCH");
            }
        }
        if (!indexed.keySet().equals(preparedById.keySet())) {
            throw new IllegalStateException("AUTOMATION_HANDLER_RECEIPT_SET_MISMATCH");
        }
        return List.copyOf(indexed.values());
    }

    private static void requireSafeTerminalDisposition(
            AutomationRunItemReceiptV1 receipt) {
        boolean retryableFailure = "FAILED".equals(receipt.state())
                && receipt.retryable();
        if (("SUCCEEDED".equals(receipt.state())
                && (receipt.resultDigest() == null || receipt.retryable()
                || receipt.nextAttemptAt() != null))
                || ("FAILED".equals(receipt.state())
                && (receipt.errorCode() == null
                || retryableFailure != (receipt.nextAttemptAt() != null)))
                || (retryableFailure
                && !receipt.nextAttemptAt().isAfter(receipt.completedAt()))
                || (java.util.Set.of("CANCELED", "DEAD_LETTERED")
                .contains(receipt.state())
                && (receipt.retryable() || receipt.nextAttemptAt() != null))) {
            throw new IllegalStateException(
                    "AUTOMATION_HANDLER_RECEIPT_DISPOSITION_UNSAFE");
        }
    }

    private static AutomationRunItemReceiptV1 normalizeReceipt(
            RuntimeRun run, AutomationRunItemReceiptV1 receipt) {
        boolean deadLetter = "FAILED".equals(receipt.state())
                && (run.run().retryMode() == AutomationDtos.RetryMode.NONE
                || !receipt.retryable()
                || run.attemptNumber() >= run.run().maxAttempts());
        String state = deadLetter ? "DEAD_LETTERED" : receipt.state();
        boolean retryable = "FAILED".equals(state) && receipt.retryable();
        Instant completedAt = receipt.completedAt().truncatedTo(ChronoUnit.MICROS);
        Instant nextAttemptAt = retryable
                ? receipt.nextAttemptAt().truncatedTo(ChronoUnit.MICROS) : null;
        return new AutomationRunItemReceiptV1(
                receipt.runId(), receipt.runRevision(), receipt.itemId(),
                receipt.itemSequence(), receipt.subjectRef(), state,
                receipt.attemptNumber(),
                receipt.acceptedAt().truncatedTo(ChronoUnit.MICROS), completedAt,
                receipt.resultRef(), receipt.resultDigest(), receipt.errorCode(),
                retryable, nextAttemptAt, receipt.correlationId());
    }

    private String receiptSetDigest(
            RuntimeRun run, List<AutomationRunItemReceiptV1> receipts) {
        List<Map<String, Object>> ordered = receipts.stream()
                .sorted(java.util.Comparator.comparingLong(
                        AutomationRunItemReceiptV1::itemSequence)
                        .thenComparing(receipt -> receipt.itemId().toString()))
                .map(receipt -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("itemPublicId", receipt.itemId());
                    item.put("itemSequence", Long.toString(receipt.itemSequence()));
                    item.put("receiptState", mapReceiptState(receipt));
                    item.put("attemptNumber", receipt.attemptNumber());
                    item.put("resultDigest", receipt.resultDigest());
                    item.put("errorCode", receipt.errorCode());
                    item.put("compensationReceiptDigest", null);
                    return item;
                }).toList();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("runPublicId", run.run().publicId());
        document.put("runRevision", Long.toString(run.run().objectRevision()));
        document.put("items", ordered);
        return canonical.digest(document);
    }

    private static String mapReceiptState(AutomationRunItemReceiptV1 receipt) {
        if ("FAILED".equals(receipt.state())) {
            return receipt.retryable() ? "FAILED_RETRYABLE" : "FAILED_FINAL";
        }
        return switch (receipt.state()) {
            case "CANCELED" -> "CANCELLED";
            case "DEAD_LETTERED" -> "DEAD_LETTER";
            default -> receipt.state();
        };
    }

    private OffsetDateTime now() {
        return execution.databaseNow();
    }

    record SchedulerAuthority(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String routeContractKey,
            String contextKey,
            String contextScopeKey,
            String decisionRevision,
            String populationScopeDigest,
            long fieldPolicyRevision,
            long authorizationRevision,
            String purposeCode,
            UUID correlationId) {
    }

    record FireRequest(
            UUID schedulePublicId,
            long expectedObjectRevision,
            long expectedFireCursorRevision,
            OffsetDateTime scheduledInstantUtc,
            OffsetDateTime schedulerWindowStart,
            String scopeType,
            UUID scopeId,
            String inputReference,
            String inputDigest,
            long expectedItemCount) {
    }

    record ScheduleFireOutcome(
            boolean skippedMissed,
            String fireKey,
            OffsetDateTime nextFireAt,
            AutomationDtos.CommandReceipt commandReceipt) {
    }
}
