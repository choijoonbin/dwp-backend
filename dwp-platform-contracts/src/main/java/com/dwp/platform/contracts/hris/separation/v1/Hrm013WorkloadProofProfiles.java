package com.dwp.platform.contracts.hris.separation.v1;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofViews.*;

/** Frozen workload-profile definitions, independent of the public model container. */
final class Hrm013WorkloadProofProfiles {

    private Hrm013WorkloadProofProfiles() {
    }

    static ProfileSpec apr002() {
        return businessProfile(
                "SVC-PEP-APR-002", "dwp-people-server", "dwp-approval-server",
                "HRIS_SEPARATION_TASK_WAIVER_REQUEST",
                "/internal/hris-approval/v1/separation-task-waiver-requests", 32_768,
                Map.of(
                        "approvalRequestId", "UUID",
                        "casePublicId", "UUID",
                        "taskPublicId", "UUID"),
                Map.of(), Set.of("ROOT_DELIVERY"));
    }

    static ProfileSpec hrm008(
            OwnerView owner, UUID casePublicId, UUID taskPublicId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(casePublicId, "casePublicId");
        Objects.requireNonNull(taskPublicId, "taskPublicId");
        return businessProfile(
                "SVC-PEP-HRM-008", owner.workloadId(), "dwp-people-server",
                owner.receiptPurpose(),
                "/internal/hris-people/v1/separation-cases/" + casePublicId
                        + "/tasks/" + taskPublicId + "/receipts",
                32_768,
                Map.of(
                        "callbackEventId", "UUID",
                        "casePublicId", "UUID",
                        "taskPublicId", "UUID",
                        "ownerRevision", "POSITIVE_LONG",
                        "originatingPayloadSha256", "SHA256"),
                Map.of("casePublicId", casePublicId, "taskPublicId", taskPublicId),
                Set.of("ROOT_DELIVERY",
                        "TERMINAL_CHILD"));
    }

    static ProfileSpec hrm009(OwnerView owner, UUID casePublicId) {
        Objects.requireNonNull(owner, "owner");
        if ("IDENTITY".equals(owner.name())) {
            throw new IllegalArgumentException("identity is not an HRM-009 reconciliation owner");
        }
        Objects.requireNonNull(casePublicId, "casePublicId");
        String purpose = "TIME".equals(owner.name())
                ? "HRIS_SEPARATION_TIME_RECONCILIATION"
                : "HRIS_SEPARATION_PAYROLL_RECONCILIATION";
        return businessProfile(
                "SVC-PEP-HRM-009", owner.workloadId(), "dwp-people-server", purpose,
                "/internal/hris-people/v1/separation-cases/" + casePublicId
                        + "/reconciliation-results",
                32_768,
                Map.of(
                        "callbackEventId", "UUID",
                        "casePublicId", "UUID",
                        "ownerRevision", "POSITIVE_LONG",
                        "originatingPayloadSha256", "SHA256"),
                Map.of("casePublicId", casePublicId),
                Set.of("ROOT_DELIVERY"));
    }

    static ProfileSpec hrm010(
            UUID casePublicId, UUID taskPublicId, String evidenceMode) {
        Objects.requireNonNull(casePublicId, "casePublicId");
        Objects.requireNonNull(taskPublicId, "taskPublicId");
        Objects.requireNonNull(evidenceMode, "evidenceMode");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("callbackEventId", "UUID");
        fields.put("casePublicId", "UUID");
        fields.put("taskPublicId", "UUID");
        fields.put("approvalRequestId", "UUID");
        fields.put("ownerRevision", "POSITIVE_LONG");
        fields.put("originatingPayloadSha256", "SHA256");
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("casePublicId", casePublicId);
        expected.put("taskPublicId", taskPublicId);
        if ("APPROVED".equals(evidenceMode)) {
            fields.put("stepUpEvidenceId", "UUID");
            fields.put("stepUpEvidenceAudience", "IDENTIFIER");
            fields.put("stepUpEvidencePurpose", "CODE");
            fields.put("stepUpEvidenceNonce", "UUID");
            fields.put("stepUpEvidenceDigest", "SHA256");
            fields.put("stepUpVerifiedAt", "INSTANT");
            fields.put("stepUpConsumedAt", "INSTANT");
            expected.put("stepUpEvidenceAudience", "dwp-people-server");
            expected.put("stepUpEvidencePurpose",
                    "HRIS_SEPARATION_TASK_WAIVER_DECISION");
        }
        return businessProfile(
                "SVC-PEP-HRM-010", "dwp-approval-server", "dwp-people-server",
                "HRIS_SEPARATION_TASK_WAIVER_DECISION",
                "/internal/hris-people/v1/separation-cases/" + casePublicId
                        + "/tasks/" + taskPublicId + "/waiver-decisions",
                32_768, fields, expected,
                Set.of("ROOT_DELIVERY",
                        "TERMINAL_CHILD"));
    }

    static ProfileSpec requesterIdentity() {
        return businessProfile(
                "SVC-AUT-HRM-013", "dwp-approval-server", "dwp-auth-server",
                "HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE",
                "/internal/auth/v1/hris-separation-requester-authority/resolve", 8_192,
                Map.of(
                        "approvalRequestId", "UUID",
                        "requestedByActorPublicId", "UUID"),
                Map.of(), Set.of("DEPENDENT_READ"));
    }

    static ProfileSpec taskDispatch(OwnerView owner) {
        Objects.requireNonNull(owner, "owner");
        return businessProfile(
                owner.dispatchBindingId(), "dwp-people-server", owner.workloadId(),
                owner.dispatchPurpose(), owner.dispatchPath(), 32_768,
                Map.of(
                        "dispatchEventId", "UUID",
                        "casePublicId", "UUID",
                        "taskPublicId", "UUID",
                        "taskType", "CODE",
                        "taskOwnerRevision", "POSITIVE_LONG",
                        "payloadSha256", "SHA256"),
                Map.of("taskType", owner.taskType()),
                Set.of("ROOT_DELIVERY"));
    }

    static ProfileSpec resolverBootstrap(ResolverView caller) {
        Objects.requireNonNull(caller, "caller");
        return new ProfileSpec(
                "RESOLVER_BOOTSTRAP", caller.bindingId(), caller.workloadId(),
                "dwp-auth-server", "HRIS_SERVICE_GRANT_AUTHORITY_RESOLVE",
                "/internal/auth/v1/hris-service-grant-authority/resolve", 8_192,
                Map.of(
                        "requestingWorkloadId", "IDENTIFIER",
                        "targetCallerWorkloadId", "IDENTIFIER",
                        "targetOwnerWorkloadId", "IDENTIFIER",
                        "targetBindingId", "IDENTIFIER",
                        "targetPurpose", "CODE"),
                Map.of("requestingWorkloadId", caller.workloadId()),
                Set.of("ROOT_DELIVERY", "DEPENDENT_READ", "TERMINAL_CHILD"));
    }

    static ProfileSpec businessProfile(
            String bindingId,
            String caller,
            String owner,
            String purpose,
            String path,
            int maximumBodyBytes,
            Map<String, String> fields,
            Map<String, Object> expected,
            Set<String> drainKinds) {
        return new ProfileSpec("BUSINESS", bindingId, caller, owner, purpose, path,
                maximumBodyBytes, fields, expected, drainKinds);
    }

    record ProfileSpec(
            String kind,
            String bindingId,
            String caller,
            String owner,
            String purpose,
            String path,
            int maximumBodyBytes,
            Map<String, String> fields,
            Map<String, Object> expected,
            Set<String> drainKinds) {
    }
}
