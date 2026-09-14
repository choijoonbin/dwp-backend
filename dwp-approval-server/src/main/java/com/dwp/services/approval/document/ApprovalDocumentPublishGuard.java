package com.dwp.services.approval.document;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.*;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class ApprovalDocumentPublishGuard {
    private final ApprovalStepUpVerifier verifier;
    private final ApprovalStepUpReplayRepository replay;
    public ApprovalDocumentPublishGuard(ApprovalStepUpVerifier verifier, ApprovalStepUpReplayRepository replay) {
        this.verifier = verifier; this.replay = replay;
    }
    public ApprovalStepUpVerifier.VerifiedChallenge verify(ApprovalRequestContext.Actor actor, String route,
            String targetType, UUID target, long version, String path, String key, Object payload, ApprovalStepUpHeaders headers) {
        var current = ApprovalDecisionRevisionContext.current().orElseThrow(() ->
                new BaseException(ErrorCode.STEP_UP_REQUIRED, "Document policy publication requires exact high-risk authority."));
        var scope = ApprovalManagementScopeContext.current().orElseThrow(() -> ApprovalDocumentCanonical.unavailable("Management scope is missing."));
        var authority = ApprovalPilotAuthorizationContext.highRisk().orElseThrow(() -> ApprovalDocumentCanonical.forbidden());
        if (!route.equals(current.routeContractKey()) || !route.equals(authority.routeContractKey())
                || !"approvals.policy.publish".equals(authority.capabilityContractKey())
                || !"STEPUP-MGMT-HIGH-V1".equals(authority.activationPolicy())
                || !("110".equals(current.rolloutState()) || "111".equals(current.rolloutState()))
                || !current.validUntil().isAfter(OffsetDateTime.now())
                || !current.contextScopeKey().equals(scope.opaqueScopeKey())) throw ApprovalDocumentCanonical.forbidden();
        if (headers == null || headers.challenge() == null || headers.challenge().isBlank()) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED, "Auth-signed document policy challenge is required.");
        }
        if (!key.equals(headers.idempotencyKey()) || headers.expectedObjectVersion() == null
                || headers.expectedObjectVersion() != version || !current.revision().equals(headers.decisionRevision())) {
            throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH, "Document publication headers changed.");
        }
        return verifier.verify(headers.challenge(), new ApprovalStepUpVerifier.CommandBinding(actor.userId(), actor.tenantId(),
                route, current.contextKey(), authority.activationPolicy(), authority.capabilityContractKey(),
                scope.opaqueScopeKey(), targetType, target.toString(), version, "POST", path, key,
                verifier.payloadSha256(payload), current.revision()));
    }
    public void consume(ApprovalStepUpVerifier.VerifiedChallenge challenge) {
        replay.assertNotConsumed(challenge.challengeId(), challenge.nonce()); replay.consume(challenge);
    }
}
