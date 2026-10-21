package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.platform.contracts.hris.generated.AutomationInvocationV1;
import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Security-sensitive owner ports. SYS-006 deliberately supplies no production binding. */
public final class AutomationRuntimePorts {

    private AutomationRuntimePorts() {
    }

    /**
     * Verifies trusted-key status, signature, RFC-8785/JCS digests, closed schemas and
     * compatibility. Implementations must resolve authority freshly on every call.
     */
    public interface SignedArtifactVerifierPort {
        VerifiedArtifact verifyDraft(AutomationDtos.CreateVersionCommand command);

        VerifiedArtifact revalidate(
                SignedManifest approvedEvidence,
                String definitionDigest,
                String executionPolicyDigest,
                String compatibilityBaselineDigest);
    }

    public record VerifiedArtifact(
            SignedManifest signedManifest,
            String trustedKeyRegistryRevision,
            String canonicalDocumentDigest,
            String compatibilityBaselineVersion,
            String compatibilityBaselineDigest,
            String verificationEvidenceDigest,
            OffsetDateTime freshUntil) {

        public VerifiedArtifact {
            if (signedManifest == null
                    || trustedKeyRegistryRevision == null
                    || trustedKeyRegistryRevision.isBlank()
                    || !digest(canonicalDocumentDigest)
                    || compatibilityBaselineVersion == null
                    || compatibilityBaselineVersion.isBlank()
                    || !digest(compatibilityBaselineDigest)
                    || !digest(verificationEvidenceDigest)
                    || freshUntil == null) {
                throw new IllegalArgumentException(
                        "Complete signed-artifact verification evidence is required");
            }
            Sys006TypedArtifactContract.requireInteroperable(
                    signedManifest.manifest());
        }
    }

    /** Domain-owned typed execution only; there is no class/method/SQL dispatch escape hatch. */
    public interface TypedAutomationHandlerPort {
        AutomationHandlerRegistryPort.HandlerIdentity identity();

        /**
         * Resolves the immutable invocation item set without causing an external
         * effect. The owner persists this set before any PREPARED effect receipt.
         */
        List<AutomationItemPlan> materialize(AutomationInvocationV1 invocation);

        List<AutomationRunItemReceiptV1> invoke(
                AutomationInvocationV1 invocation,
                List<AutomationEffectRequest> selectedItems);

        default List<AutomationRunItemReceiptV1> compensate(
                AutomationInvocationV1 invocation,
                List<AutomationRunItemReceiptV1> committedReceipts,
                AutomationCompensationRequest compensation) {
            throw new UnsupportedOperationException(
                    "Compensation is not declared by this typed handler");
        }
    }

    public record AutomationEffectRequest(
            UUID itemId,
            long itemSequence,
            String subjectRef,
            long runRevision,
            int attemptNumber,
            Instant acceptedAt,
            String effectIdempotencyKey,
            String effectRequestDigest) {
        public AutomationEffectRequest {
            if (itemId == null || itemSequence < 1
                    || itemSequence > Sys006TypedArtifactContract.MAX_ITEM_SEQUENCE
                    || subjectRef == null || runRevision < 1
                    || runRevision
                    > Sys006TypedArtifactContract.MAX_JSON_SAFE_INTEGER
                    || attemptNumber < 1
                    || attemptNumber > Sys006TypedArtifactContract.MAX_ATTEMPTS
                    || acceptedAt == null) {
                throw new IllegalArgumentException(
                        "Closed automation effect receipt context is required");
            }
        }
    }

    public record AutomationCompensationRequest(
            UUID itemId,
            long runRevision,
            int attemptNumber,
            String compensationIdempotencyKey,
            String compensationRequestDigest,
            String originalEffectIdempotencyKey,
            String originalEffectReceiptDigest) {
        public AutomationCompensationRequest {
            if (itemId == null || runRevision < 1
                    || runRevision > Sys006TypedArtifactContract.MAX_JSON_SAFE_INTEGER
                    || attemptNumber < 1
                    || attemptNumber > Sys006TypedArtifactContract.MAX_ATTEMPTS) {
                throw new IllegalArgumentException(
                        "Closed compensation receipt context is required");
            }
        }
    }

    public record AutomationItemPlan(
            UUID itemId,
            long itemSequence,
            String subjectRef) {
        public AutomationItemPlan {
            if (itemId == null || itemSequence < 1 || itemSequence > 1_000_000_000L
                    || subjectRef == null
                    || !subjectRef.matches("^[A-Za-z0-9][A-Za-z0-9:/._-]{0,511}$")) {
                throw new IllegalArgumentException(
                        "Closed automation item identity is required");
            }
        }
    }

    /** Trusted external receipt evidence used only by a separately fenced reconciler. */
    public interface ExternalEffectEvidencePort {
        ReconciliationEvidence resolve(
                long tenantId,
                UUID runPublicId,
                UUID itemPublicId,
                String effectIdempotencyKey,
                String requestDigest);
    }

    public record ReconciliationEvidence(
            String outcome,
            String receiptDigest,
            String evidenceDigest,
            OffsetDateTime observedAt,
            OffsetDateTime freshUntil) {
        public ReconciliationEvidence {
            if (!List.of("NO_EFFECT_CONFIRMED", "COMMITTED", "RESULT_UNKNOWN")
                    .contains(outcome)
                    || ("COMMITTED".equals(outcome) && !digest(receiptDigest))
                    || (receiptDigest != null && !digest(receiptDigest))
                    || !digest(evidenceDigest)
                    || observedAt == null || freshUntil == null
                    || !freshUntil.isAfter(observedAt)) {
                throw new IllegalArgumentException(
                        "Closed trusted reconciliation evidence is required");
            }
        }
    }

    public static AutomationHandlerManifestV1 requireFresh(
            AutomationHandlerRegistryPort registry,
            SignedManifest approved,
            OffsetDateTime freshUntil,
            OffsetDateTime now) {
        if (registry == null || approved == null || freshUntil == null
                || now == null || !freshUntil.isAfter(now)) {
            throw new IllegalStateException("AUTOMATION_HANDLER_AUTHORITY_UNAVAILABLE");
        }
        return registry.verifyCurrentActive(approved)
                .map(Sys006TypedArtifactContract::requireInteroperable)
                .orElseThrow(() -> new IllegalStateException(
                        "AUTOMATION_HANDLER_MANIFEST_NOT_CURRENT_ACTIVE"));
    }

    private static boolean digest(String value) {
        return value != null && value.matches("^[a-f0-9]{64}$");
    }
}
