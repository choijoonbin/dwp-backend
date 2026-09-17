package com.dwp.services.approval.auditrecords;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import com.dwp.services.approval.security.ApprovalStepUpReplayRepository;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.UUID;

@Component
final class ApprovalAuditCommandGuard {
    static final String CAPABILITY = "approvals.operations.execute";
    static final String ACTIVATION_POLICY = "STEPUP-MGMT-HIGH-V1";

    private final ApprovalStepUpVerifier verifier;
    private final ApprovalStepUpReplayRepository replay;

    ApprovalAuditCommandGuard(
            ApprovalStepUpVerifier verifier,
            ApprovalStepUpReplayRepository replay) {
        this.verifier = verifier;
        this.replay = replay;
    }

    Permit begin(
            ApprovalAuditHttpAuthority.Current current,
            Profile profile,
            UUID targetId,
            long expectedVersion,
            Object payload,
            ApprovalStepUpHeaders headers) {
        var route = ApprovalPilotAuthorizationContext.highRisk().orElseThrow(() ->
                unavailable("Exact high-risk audit route authority is unavailable."));
        if (!profile.routeContractKey().equals(route.routeContractKey())
                || !profile.routeContractKey().equals(current.decision().routeContractKey())
                || !CAPABILITY.equals(route.capabilityContractKey())
                || !ACTIVATION_POLICY.equals(route.activationPolicy())
                || !route.highRisk()
                || !current.management().opaqueScopeKey()
                        .equals(current.decision().contextScopeKey())) {
            throw forbidden("The current route does not authorize this audit command.");
        }
        requireHeaders(current, expectedVersion, headers);
        ApprovalStepUpVerifier.CommandBinding binding =
                new ApprovalStepUpVerifier.CommandBinding(
                        current.actor().userId(), current.actor().tenantId(),
                        profile.routeContractKey(), current.decision().contextKey(),
                        ACTIVATION_POLICY, CAPABILITY,
                        current.management().opaqueScopeKey(),
                        profile.targetType(), targetId.toString(), expectedVersion,
                        "POST", "/api/approvals" + profile.path(targetId),
                        headers.idempotencyKey(), verifier.payloadSha256(payload),
                        current.decision().revision());
        LinkedHashMap<String, Object> requestMaterial = new LinkedHashMap<>();
        requestMaterial.put("binding", binding);
        requestMaterial.put("resourceSetKey", current.management().resourceSetKey());
        ApprovalStepUpReplayRepository.Reservation reservation = replay.reserve(
                binding, profile.routeContractKey(),
                verifier.payloadSha256(requestMaterial));
        if (reservation.committed()) {
            return Permit.prior(reservation);
        }
        ApprovalStepUpVerifier.VerifiedChallenge challenge =
                verifier.verify(headers.challenge(), binding);
        replay.assertNotConsumed(challenge.challengeId(), challenge.nonce());
        return Permit.verified(challenge, reservation);
    }

    void complete(Permit permit) {
        if (permit.challenge() == null) {
            return;
        }
        replay.consume(permit.challenge());
        replay.commit(permit.reservation().id(), permit.challenge());
    }

    private void requireHeaders(
            ApprovalAuditHttpAuthority.Current current,
            long expectedVersion,
            ApprovalStepUpHeaders headers) {
        if (headers == null || headers.challenge() == null
                || headers.challenge().isBlank()
                || headers.idempotencyKey() == null
                || !headers.idempotencyKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")
                || !current.decision().revision().equals(headers.decisionRevision())
                || headers.expectedObjectVersion() == null
                || headers.expectedObjectVersion() != expectedVersion) {
            throw new BaseException(
                    ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "Audit command evidence does not match the current object and decision.");
        }
    }

    enum Profile {
        SAVED_VIEW_CREATE(
                "route.approvals.admin.audit-saved-view-create.action",
                "APPROVAL_AUDIT_SAVED_VIEW",
                "/v1/admin/operations/audit-records/saved-views"),
        EXPORT_CREATE(
                "route.approvals.admin.audit-export-create.action",
                "APPROVAL_AUDIT_EXPORT",
                "/v1/admin/operations/audit-records/exports"),
        EXPORT_VERIFY(
                "route.approvals.admin.audit-export-verify.action",
                "APPROVAL_AUDIT_EXPORT",
                "/v1/admin/operations/audit-records/exports/%s/verifications"),
        EXPORT_ATTESTATION(
                "route.approvals.admin.audit-export-attestation.action",
                "APPROVAL_AUDIT_EXPORT",
                "/v1/admin/operations/audit-records/exports/%s/external-attestations");

        private final String routeContractKey;
        private final String targetType;
        private final String pathTemplate;

        Profile(String routeContractKey, String targetType, String pathTemplate) {
            this.routeContractKey = routeContractKey;
            this.targetType = targetType;
            this.pathTemplate = pathTemplate;
        }

        String routeContractKey() {
            return routeContractKey;
        }

        String targetType() {
            return targetType;
        }

        String path(UUID targetId) {
            return pathTemplate.contains("%s")
                    ? pathTemplate.formatted(targetId)
                    : pathTemplate;
        }
    }

    record Permit(
            ApprovalStepUpVerifier.VerifiedChallenge challenge,
            ApprovalStepUpReplayRepository.Reservation reservation) {
        static Permit verified(
                ApprovalStepUpVerifier.VerifiedChallenge challenge,
                ApprovalStepUpReplayRepository.Reservation reservation) {
            return new Permit(challenge, reservation);
        }

        static Permit prior(ApprovalStepUpReplayRepository.Reservation reservation) {
            return new Permit(null, reservation);
        }

        boolean priorResult() {
            return reservation != null && reservation.committed();
        }
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
