package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Orchestrates exact PEP metadata, owner evidence, signed challenge, and local replay state. */
@Component
public class ApprovalHighRiskCommandGuard {

    private final ApprovalStepUpVerifier verifier;
    private final ApprovalStepUpReplayRepository replay;
    private final ApprovalOwnerPredicateEvaluator ownerPredicates;

    public ApprovalHighRiskCommandGuard(
            ApprovalStepUpVerifier verifier,
            ApprovalStepUpReplayRepository replay,
            ApprovalOwnerPredicateEvaluator ownerPredicates) {
        this.verifier = verifier;
        this.replay = replay;
        this.ownerPredicates = ownerPredicates;
    }

    public Permit begin(
            ApprovalRequestContext.Actor actor,
            String capabilityContractKey,
            String targetType,
            UUID targetId,
            long expectedVersion,
            String publicPath,
            Object canonicalPayload,
            ApprovalStepUpHeaders headers) {
        ApprovalPilotPepRegistry.RouteAuthority authority = ApprovalPilotAuthorizationContext
                .highRisk().orElse(null);
        if (authority == null) {
            if (ApprovalDecisionRevisionContext.current().isPresent()) {
                throw new BaseException(
                        ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "The enforced Approval route has no high-risk authority.");
            }
            return Permit.notGoverned();
        }
        if (!capabilityContractKey.equals(authority.capabilityContractKey())
                || !"STEPUP-MGMT-HIGH-V1".equals(authority.activationPolicy())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The resolved Approval route does not authorize this high-risk command.");
        }
        if (headers == null || headers.challenge() == null || headers.challenge().isBlank()
                || headers.idempotencyKey() == null || headers.idempotencyKey().isBlank()
                || headers.decisionRevision() == null
                || headers.decisionRevision().isBlank()
                || headers.expectedObjectVersion() == null) {
            throw new BaseException(
                    ErrorCode.STEP_UP_REQUIRED,
                    "Command-bound step-up headers are required.");
        }
        if (headers.idempotencyKey().length() > 200
                || headers.decisionRevision().length() > 200
                || headers.expectedObjectVersion() != expectedVersion) {
            throw new BaseException(
                    ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "Command-bound step-up headers do not match the target version.");
        }
        ApprovalDecisionRevisionContext.Evidence current =
                ApprovalDecisionRevisionContext.current().orElseThrow(() -> new BaseException(
                        ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Current Approval authority revision is unavailable."));
        if (!current.revision().equals(headers.decisionRevision())
                || !authority.routeContractKey().equals(current.routeContractKey())
                || !("110".equals(current.rolloutState()) || "111".equals(current.rolloutState()))
                || current.contextKey() == null || current.contextKey().isBlank()
                || current.contextScopeKey() == null || current.contextScopeKey().isBlank()
                || current.validUntil() == null
                || !current.validUntil().isAfter(java.time.OffsetDateTime.now())) {
            throw new BaseException(
                    ErrorCode.DECISION_REVISION_CONFLICT,
                    "Approval authority changed after challenge issuance.");
        }
        ApprovalManagementScopeContext.Evidence managementScope =
                ApprovalManagementScopeContext.current().orElseThrow(() -> new BaseException(
                        ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Approval management scope evidence is unavailable."));
        if (!current.contextScopeKey().equals(managementScope.opaqueScopeKey())
                || managementScope.resourceSetKey() == null
                || managementScope.resourceSetKey().isBlank()) {
            throw new BaseException(
                    ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "The step-up challenge scope does not match the managed object scope.");
        }
        ApprovalStepUpVerifier.CommandBinding binding = new ApprovalStepUpVerifier.CommandBinding(
                actor.userId(), actor.tenantId(), authority.routeContractKey(),
                current.contextKey(), authority.activationPolicy(),
                capabilityContractKey, current.contextScopeKey(), targetType,
                targetId.toString(), expectedVersion,
                "POST", publicPath, headers.idempotencyKey(),
                verifier.payloadSha256(canonicalPayload), current.revision());
        Map<String, Object> requestMaterial = new LinkedHashMap<>();
        requestMaterial.put("routeContractKey", authority.routeContractKey());
        requestMaterial.put("actorUserId", actor.userId());
        requestMaterial.put("tenantId", actor.tenantId());
        requestMaterial.put("contextKey", current.contextKey());
        requestMaterial.put("contextScopeKey", current.contextScopeKey());
        requestMaterial.put("targetType", targetType);
        requestMaterial.put("targetId", targetId.toString());
        requestMaterial.put("targetVersion", expectedVersion);
        requestMaterial.put("commandMethod", "POST");
        requestMaterial.put("commandPath", publicPath);
        requestMaterial.put("idempotencyKey", headers.idempotencyKey());
        requestMaterial.put("payloadSha256", binding.payloadSha256());
        requestMaterial.put("decisionRevision", current.revision());
        ApprovalStepUpReplayRepository.Reservation reservation = replay.reserve(
                binding, authority.routeContractKey(), verifier.payloadSha256(requestMaterial));
        if (reservation.committed()) return Permit.prior(reservation);
        ApprovalStepUpVerifier.VerifiedChallenge challenge =
                verifier.verify(headers.challenge(), binding);
        ownerPredicates.lockAndValidate(actor, targetType, targetId, expectedVersion);
        replay.assertNotConsumed(challenge.challengeId(), challenge.nonce());
        return Permit.governed(challenge, reservation);
    }

    public void complete(Permit permit) {
        if (permit != null && permit.challenge() != null) {
            replay.consume(permit.challenge());
            replay.commit(permit.reservation().id(), permit.challenge());
        }
    }

    public record Permit(
            ApprovalStepUpVerifier.VerifiedChallenge challenge,
            ApprovalStepUpReplayRepository.Reservation reservation) {
        static Permit notGoverned() {
            return new Permit(null, null);
        }

        static Permit governed(
                ApprovalStepUpVerifier.VerifiedChallenge challenge,
                ApprovalStepUpReplayRepository.Reservation reservation) {
            return new Permit(challenge, reservation);
        }

        static Permit prior(ApprovalStepUpReplayRepository.Reservation reservation) {
            return new Permit(null, reservation);
        }

        public boolean governed() {
            return reservation != null;
        }

        public boolean priorResult() {
            return reservation != null && reservation.committed();
        }
    }
}
