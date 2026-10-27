package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationInvocationV1;
import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.LeaseGrant;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.PreparedCompensation;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.PreparedOriginal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.RuntimeRun;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.ScheduleExecution;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.HandlerIdentity;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.AutomationItemPlan;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.SignedArtifactVerifierPort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.TypedAutomationHandlerPort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;

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

/** Internal automation runtime evidence operations shared by the owner facade. */
final class AutomationRuntimeEvidence {



    static final Duration MIN_LEASE = Duration.ofSeconds(5);
    static final Duration MAX_LEASE = Duration.ofMinutes(5);
    private final AutomationExecutionRepository execution;
    private final AutomationHandlerRegistryPort registry;
    private final SignedArtifactVerifierPort verifier;
    private final Map<HandlerIdentity, TypedAutomationHandlerPort> handlers;
    private final AutomationCanonicalJson canonical;

    AutomationRuntimeEvidence(
            AutomationExecutionRepository execution,
            AutomationHandlerRegistryPort registry,
            SignedArtifactVerifierPort verifier,
            Map<HandlerIdentity, TypedAutomationHandlerPort> handlers,
            AutomationCanonicalJson canonical) {
        this.execution = execution;
        this.registry = registry;
        this.verifier = verifier;
        this.handlers = handlers;
        this.canonical = canonical;
    }


    VerifiedArtifact revalidate(RuntimeRun run, OffsetDateTime now) {
        return revalidate(run.manifest(), run.definitionDigest(),
                run.executionPolicyDigest(), run.compatibilityBaselineDigest(), now);
    }

    VerifiedArtifact revalidate(
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

    void requireWorker(
            RuntimeRun run, LeaseGrant worker, OffsetDateTime now,
            boolean externalDispatch) {
        AutomationDomainPolicy.requireActiveFence(
                AutomationDomainPolicy.AuthorityKind.WORKER,
                run.workerLease(), run.reconcilerLease(), worker.token(),
                worker.authorityFenceEpoch(), worker.controlFenceEpoch(), now,
                externalDispatch, "ORIGINAL");
    }

    void requireReconciler(
            RuntimeRun run, LeaseGrant reconciler, OffsetDateTime now,
            boolean externalDispatch) {
        AutomationDomainPolicy.requireActiveFence(
                AutomationDomainPolicy.AuthorityKind.RECONCILER,
                run.workerLease(), run.reconcilerLease(), reconciler.token(),
                reconciler.authorityFenceEpoch(), reconciler.controlFenceEpoch(), now,
                externalDispatch, "COMPENSATION");
    }

    static Duration validLease(Duration duration) {
        if (duration == null || duration.compareTo(MIN_LEASE) < 0
                || duration.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("AUTOMATION_LEASE_DURATION_INVALID");
        }
        return duration;
    }

    static void requireRevision(RuntimeRun run, long expected) {
        if (expected < 1 || run.run().objectRevision() != expected) {
            throw new IllegalStateException("AUTOMATION_RUN_CAS_CONFLICT");
        }
    }

    static OffsetDateTime nextFire(
            ScheduleExecution schedule, OffsetDateTime after) {
        return AutomationDomainPolicy.nextScheduleFire(
                schedule.expression(), schedule.timezone(),
                schedule.timezoneDatabaseRevision(), after);
    }

    TypedAutomationHandlerPort handler(HandlerIdentity identity) {
        TypedAutomationHandlerPort handler = handlers.get(identity);
        if (handler == null) {
            throw new IllegalStateException("AUTOMATION_TYPED_HANDLER_UNAVAILABLE");
        }
        return handler;
    }

    static AutomationInvocationV1 invocation(RuntimeRun run) {
        var manifest = run.manifest().manifest();
        return new AutomationInvocationV1(
                run.run().publicId(), run.run().definitionPublicId(),
                run.run().versionNumber(), manifest.handlerKey(),
                manifest.contractVersion(), run.scopeType(), run.scopeId(),
                run.requestedAt().toInstant(), run.requestedByActorRef(),
                run.idempotencyKey(), run.inputReference(), run.inputDigest(),
                run.expectedItemCount(), run.correlationId());
    }

    static void validateMaterialization(
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

    static void requireCompensationContext(
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

    AutomationRunItemReceiptV1 requireCommittedOriginal(
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

    static List<AutomationRunItemReceiptV1> validateAndNormalizeReceipts(
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

    static void requireSafeTerminalDisposition(
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

    static AutomationRunItemReceiptV1 normalizeReceipt(
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

    String receiptSetDigest(
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

    static String mapReceiptState(AutomationRunItemReceiptV1 receipt) {
        if ("FAILED".equals(receipt.state())) {
            return receipt.retryable() ? "FAILED_RETRYABLE" : "FAILED_FINAL";
        }
        return switch (receipt.state()) {
            case "CANCELED" -> "CANCELLED";
            case "DEAD_LETTERED" -> "DEAD_LETTER";
            default -> receipt.state();
        };
    }

    OffsetDateTime now() {
        return execution.databaseNow();
    }
}
