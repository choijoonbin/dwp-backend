package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Closed route/profile/body binding after cryptographic proof admission. */
public final class Hrm013AdmissionControl {
    private static final Pattern TASK = Pattern.compile(
            "^/internal/hris-people/v1/separation-cases/([0-9a-f-]{36})/tasks/([0-9a-f-]{36})/(receipts|waiver-decisions)$");
    private static final Pattern RECONCILIATION = Pattern.compile(
            "^/internal/hris-people/v1/separation-cases/([0-9a-f-]{36})/reconciliation-results$");

    public Hrm013CallbackRequestContext.Route route(
            String method, String path, String callerWorkloadId, String purpose,
            String taskType, UUID correlationId, boolean approvedWaiver) {
        if (!"POST".equals(method) || path == null || path.indexOf('?') >= 0
                || path.indexOf('#') >= 0) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Matcher task = TASK.matcher(path);
        Matcher reconciliation = RECONCILIATION.matcher(path);
        try {
            if (task.matches()) {
                UUID caseId = canonicalUuid(task.group(1));
                UUID taskId = canonicalUuid(task.group(2));
                if ("receipts".equals(task.group(3))) {
                    Hrm013WorkloadProofV1.TaskOwner owner = owner(callerWorkloadId);
                    var binding = switch (owner) {
                        case TIME -> Hrm013SeparationBridgeV1.HRM_008_TIME;
                        case PAYROLL -> Hrm013SeparationBridgeV1.HRM_008_PAYROLL;
                        case IDENTITY -> Hrm013SeparationBridgeV1.HRM_008_IDENTITY;
                    };
                    requireSelector(binding, callerWorkloadId, purpose, taskType);
                    return new Hrm013CallbackRequestContext.Route(
                            Hrm013CallbackRequestContext.Kind.TASK_RECEIPT, method, path,
                            caseId, taskId, correlationId, binding,
                            Hrm013WorkloadProofV1.hrm008(owner, caseId, taskId));
                }
                if (!"dwp-approval-server".equals(callerWorkloadId)) {
                    throw Hrm013SafeErrorV1.authorityDenied();
                }
                var binding = Hrm013SeparationBridgeV1.HRM_010;
                requireSelector(binding, callerWorkloadId, purpose, taskType);
                return new Hrm013CallbackRequestContext.Route(
                        Hrm013CallbackRequestContext.Kind.WAIVER_DECISION, method, path,
                        caseId, taskId, correlationId, binding,
                        Hrm013WorkloadProofV1.hrm010(caseId, taskId,
                                approvedWaiver
                                        ? Hrm013WorkloadProofV1.WaiverEvidenceMode.APPROVED
                                        : Hrm013WorkloadProofV1.WaiverEvidenceMode.REJECTED));
            }
            if (reconciliation.matches()) {
                UUID caseId = canonicalUuid(reconciliation.group(1));
                Hrm013WorkloadProofV1.TaskOwner owner = owner(callerWorkloadId);
                if (owner == Hrm013WorkloadProofV1.TaskOwner.IDENTITY) {
                    throw Hrm013SafeErrorV1.authorityDenied();
                }
                var binding = owner == Hrm013WorkloadProofV1.TaskOwner.TIME
                        ? Hrm013SeparationBridgeV1.HRM_009_TIME
                        : Hrm013SeparationBridgeV1.HRM_009_PAYROLL;
                requireSelector(binding, callerWorkloadId, purpose, null);
                return new Hrm013CallbackRequestContext.Route(
                        Hrm013CallbackRequestContext.Kind.RECONCILIATION_RESULT,
                        method, path, caseId, null, correlationId, binding,
                        Hrm013WorkloadProofV1.hrm009(owner, caseId));
            }
        } catch (IllegalArgumentException failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        throw Hrm013SafeErrorV1.authorityDenied();
    }

    public Hrm013SeparationBridgeV1.PayloadBody decodeAndBind(
            Hrm013CallbackRequestContext.Route route,
            Hrm013WorkloadProofVerifier.VerifiedCallback verified) {
        byte[] bytes = verified.exactBody();
        Hrm013SeparationBridgeV1.PayloadBody body = switch (route.kind()) {
            case TASK_RECEIPT -> Hrm013SeparationBridgeV1.decodeTaskReceipt(bytes);
            case RECONCILIATION_RESULT ->
                    Hrm013SeparationBridgeV1.decodeReconciliationResult(bytes);
            case WAIVER_DECISION -> Hrm013SeparationBridgeV1.decodeWaiverDecision(bytes);
        };
        Hrm013SeparationBridgeV1.requireBodyBinding(route.bindingProfile(), body);
        Hrm013SeparationBridgeV1.requirePayloadSha256(body);
        Map<String, Object> claims = verified.proof().claims().profileClaims();
        boolean matches = body.correlationId().equals(route.correlationId());
        if (body instanceof Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 value) {
            matches &= value.casePublicId().equals(route.casePublicId())
                    && value.taskPublicId().equals(route.taskPublicId())
                    && value.callbackEventId().equals(claims.get("callbackEventId"))
                    && value.ownerRevision() == ((Number) claims.get("ownerRevision")).longValue()
                    && value.originatingPayloadSha256().equals(
                    claims.get("originatingPayloadSha256"));
        } else if (body instanceof Hrm013SeparationBridgeV1.SeparationReconciliationResultV1 value) {
            matches &= value.casePublicId().equals(route.casePublicId())
                    && value.callbackEventId().equals(claims.get("callbackEventId"))
                    && value.ownerRevision() == ((Number) claims.get("ownerRevision")).longValue()
                    && value.originatingPayloadSha256().equals(
                    claims.get("originatingPayloadSha256"));
        } else if (body instanceof Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2 value) {
            matches &= value.casePublicId().equals(route.casePublicId())
                    && value.taskPublicId().equals(route.taskPublicId())
                    && value.callbackEventId().equals(claims.get("callbackEventId"))
                    && value.approvalRequestId().equals(claims.get("approvalRequestId"))
                    && value.ownerRevision() == ((Number) claims.get("ownerRevision")).longValue()
                    && value.originatingPayloadSha256().equals(
                    claims.get("originatingPayloadSha256"));
            if ("APPROVED".equals(value.decision())) {
                matches &= Objects.equals(value.stepUpEvidenceId(),
                        claims.get("stepUpEvidenceId"))
                        && Objects.equals(value.stepUpEvidenceAudience(),
                        claims.get("stepUpEvidenceAudience"))
                        && Objects.equals(value.stepUpEvidencePurpose(),
                        claims.get("stepUpEvidencePurpose"))
                        && Objects.equals(value.stepUpEvidenceNonce(),
                        claims.get("stepUpEvidenceNonce"))
                        && Objects.equals(value.stepUpEvidenceDigest(),
                        claims.get("stepUpEvidenceDigest"))
                        && Objects.equals(value.stepUpVerifiedAt(),
                        claims.get("stepUpVerifiedAt"))
                        && Objects.equals(value.stepUpConsumedAt(),
                        claims.get("stepUpConsumedAt"));
                Hrm013SeparationBridgeV1.requireStepUpEvidenceSha256(
                        verified.proof().claims().tenantId(), value);
            }
        }
        if (!matches) throw Hrm013SafeErrorV1.authorityDenied();
        return body;
    }

    public static boolean claimsApprovedWaiver(Map<String, Object> untrustedClaims) {
        return untrustedClaims != null && untrustedClaims.containsKey("stepUpEvidenceId");
    }

    private static Hrm013WorkloadProofV1.TaskOwner owner(String caller) {
        return java.util.Arrays.stream(Hrm013WorkloadProofV1.TaskOwner.values())
                .filter(value -> value.workloadId().equals(caller))
                .findFirst().orElseThrow(Hrm013SafeErrorV1::authorityDenied);
    }

    private static void requireSelector(
            Hrm013SeparationBridgeV1.BindingProfile profile,
            String caller, String purpose, String taskType) {
        if (!profile.callerWorkloadId().equals(caller)
                || !profile.purpose().equals(purpose)
                || taskType != null && !profile.allowedTaskTypes().contains(taskType)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static UUID canonicalUuid(String value) {
        UUID parsed = UUID.fromString(Objects.requireNonNull(value));
        if (!parsed.toString().equals(value)) throw new IllegalArgumentException("UUID");
        return parsed;
    }
}
