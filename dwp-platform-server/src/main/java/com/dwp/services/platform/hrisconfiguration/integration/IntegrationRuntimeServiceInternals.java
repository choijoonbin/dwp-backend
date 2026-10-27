package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.AttemptRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.ExecutionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderEvidence;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderItemEvidence;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;

/** Shared constants, internal evidence and pure command rules. */
final class IntegrationRuntimeServiceInternals {

    static final UUID DISPATCH_WORKER_PRINCIPAL = UUID.fromString(
            "00000000-0000-4000-8000-000000000008");
    static final Duration EFFECT_CLAIM_RECOVERY_AFTER =
            Duration.ofMinutes(5);

    static boolean exactItemEvidence(
            List<AttemptRecord> attempts,
            List<ProviderItemEvidence> evidenceItems,
            ProviderEvidence.Outcome aggregateOutcome,
            boolean reconciliation) {
        if (evidenceItems == null || evidenceItems.size() != attempts.size()) {
            return false;
        }
        Map<UUID, AttemptRecord> expected = new HashMap<>();
        attempts.forEach(attempt -> expected.put(attempt.itemPublicId(), attempt));
        Set<UUID> observed = new HashSet<>();
        for (ProviderItemEvidence item : evidenceItems) {
            AttemptRecord attempt = expected.get(item.itemPublicId());
            if (attempt == null || !observed.add(item.itemPublicId())
                    || !constant(attempt.providerIdempotencyKey(),
                            item.providerIdempotencyKey())
                    || !constant(attempt.requestDigest(), item.requestDigest())
                    || !itemOutcomeAllowed(
                            aggregateOutcome, item.outcome(), reconciliation)
                    || (item.outcome() != ProviderEvidence.Outcome.RESULT_UNKNOWN
                        && !paired(item.receiptRef(), item.receiptDigest()))
                    || (reconciliation
                        && item.outcome()
                            == ProviderEvidence.Outcome.RECONCILED
                        && (item.reconciliationDigest() == null
                        || !item.reconciliationDigest()
                            .matches("^[a-f0-9]{64}$")))
                    || (item.outcome() == ProviderEvidence.Outcome.FAILED
                        && (item.failureClass() == null
                        || item.failureClass().isBlank()))
                    || (item.quarantined()
                        && item.outcome()
                            != ProviderEvidence.Outcome.FAILED)) {
                return false;
            }
        }
        return observed.size() == expected.size();
    }

    static boolean itemOutcomeAllowed(
            ProviderEvidence.Outcome aggregate,
            ProviderEvidence.Outcome item,
            boolean reconciliation) {
        if (aggregate == ProviderEvidence.Outcome.RESULT_UNKNOWN) {
            return item == ProviderEvidence.Outcome.RESULT_UNKNOWN;
        }
        if (!reconciliation) {
            return aggregate == item
                    && Set.of(ProviderEvidence.Outcome.ACKNOWLEDGED,
                    ProviderEvidence.Outcome.FAILED).contains(item);
        }
        return switch (aggregate) {
            case RECONCILED -> item == ProviderEvidence.Outcome.RECONCILED;
            case FAILED -> item == ProviderEvidence.Outcome.FAILED;
            case PARTIAL -> item == ProviderEvidence.Outcome.RECONCILED
                    || item == ProviderEvidence.Outcome.FAILED;
            default -> false;
        };
    }

    static boolean validCompensationEvidence(
            ProviderEvidence result,
            String compensationKey,
            String commandDigest) {
        return result != null
                && result.outcome() == ProviderEvidence.Outcome.CANCELLED
                && constant(compensationKey, result.providerIdempotencyKey())
                && constant(commandDigest, result.requestDigest())
                && paired(result.receiptRef(), result.receiptDigest());
    }

    static boolean terminal(ReceiptStatus status) {
        return status != ReceiptStatus.ACCEPTED
                && status != ReceiptStatus.RUNNING;
    }

    static void requireExecutor(Authority authority) {
        if (authority == null || !authority.executor()
                || !IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE
                .equals(authority.purposeCode())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "SYS008_EXECUTOR_AUTHORITY_REQUIRED");
        }
    }

    static void requireRevision(
            ExecutionRecord execution, Authority authority) {
        if (authority.expectedObjectRevision() == null
                || authority.expectedObjectRevision()
                != execution.aggregateRevision()) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "SYS008_EXECUTION_REVISION_CONFLICT");
        }
    }

    static void requireStepUp(Authority authority) {
        if (authority.stepUpEvidenceDigest() == null) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "SYS008_TARGET_BOUND_STEP_UP_REQUIRED");
        }
    }

    static void requireExecutionTransition(
            ExecutionState from, ExecutionState to) {
        try {
            IntegrationDomainPolicy.requireTransition(from, to);
        } catch (IllegalStateException invalid) {
            throw conflict("SYS008_EXECUTION_STATE_INVALID");
        }
    }

    static void requireCancellationEvidence(
            boolean dispatched, String policyRef, String receiptDigest) {
        try {
            IntegrationDomainPolicy.requireCancellationEvidence(
                    dispatched, policyRef, receiptDigest);
        } catch (IllegalStateException invalid) {
            throw conflict("SYS008_COMPENSATION_EVIDENCE_INVALID");
        }
    }

    static boolean paired(String reference, String digest) {
        return reference != null && !reference.isBlank()
                && digest != null && digest.matches("^[a-f0-9]{64}$");
    }

    static boolean constant(String left, String right) {
        return left != null && right != null
                && java.security.MessageDigest.isEqual(
                        left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String executionRef(UUID id) {
        return "/v1/admin/hris/connector-executions/" + id;
    }

    static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    static BaseException notFound(String message) {
        return new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE, message);
    }

    record DispatchClaim(
            ExecutionRecord execution,
            List<AttemptRecord> attempts,
            OffsetDateTime claimedAt,
            boolean recovery) {
        DispatchClaim {
            attempts = List.copyOf(attempts);
        }
    }

    record EffectEvidence(
            ProviderEvidence evidence,
            String ambiguityCategory) {
        EffectEvidence {
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    record CompensationEffect(
            ProviderEvidence evidence,
            boolean ambiguous) {}

    record ReconcileClaim(
            CommandReceipt receipt,
            ExecutionRecord execution,
            List<AttemptRecord> attempts,
            OffsetDateTime claimedAt,
            NonExportableProviderHandle providerReceiptHandle,
            boolean recovery,
            boolean replay) {
        ReconcileClaim {
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }

        static ReconcileClaim replay(CommandReceipt receipt) {
            return new ReconcileClaim(
                    receipt, null, List.of(), null, null, false, true);
        }
    }

    record CancelClaim(
            CommandReceipt receipt,
            ExecutionRecord execution,
            List<AttemptRecord> attempts,
            OffsetDateTime claimedAt,
            NonExportableProviderHandle compensationPolicyHandle,
            String compensationPolicyDigest,
            String compensationKey,
            String compensationRequestDigest,
            boolean recovery,
            boolean replay,
            boolean completed) {
        CancelClaim {
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }

        static CancelClaim replay(CommandReceipt receipt) {
            return new CancelClaim(receipt, null, List.of(), null,
                    null, null, null, null, false, true, false);
        }

        static CancelClaim completed(CommandReceipt receipt) {
            return new CancelClaim(receipt, null, List.of(), null,
                    null, null, null, null, false, false, true);
        }

        static CancelClaim recovery(
                CommandReceipt receipt,
                ExecutionRecord execution,
                List<AttemptRecord> attempts,
                OffsetDateTime claimedAt,
                NonExportableProviderHandle policyHandle,
                String policyDigest,
                String compensationKey,
                String compensationRequestDigest) {
            return new CancelClaim(receipt, execution, attempts, claimedAt,
                    policyHandle, policyDigest, compensationKey,
                    compensationRequestDigest, true, false, false);
        }
    }

    record ResolvedCompensation(
            NonExportableProviderHandle policyHandle,
            String policyDigest) {}
}
