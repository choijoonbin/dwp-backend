package com.dwp.services.approval.documentretention.management;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class ApprovalRetentionHighGuard {
    private final ApprovalStepUpVerifier verifier;
    private final ApprovalStepUpReplayRepository replay;
    public ApprovalRetentionHighGuard(ApprovalStepUpVerifier verifier,ApprovalStepUpReplayRepository replay) {
        this.verifier=verifier;this.replay=replay;
    }
    public ApprovalStepUpVerifier.VerifiedChallenge verify(ApprovalRequestContext.Actor actor,String route,
            String capability,String targetType,UUID target,long version,String nativePath,String key,Object body,
            ApprovalStepUpHeaders headers) {
        var evidence=ApprovalDecisionRevisionContext.current().orElseThrow(()->new BaseException(ErrorCode.STEP_UP_REQUIRED,"Retention destruction requires signed high-risk authority."));
        var scope=ApprovalManagementScopeContext.current().orElseThrow(ApprovalRetentionErrors::unavailable);
        var high=ApprovalPilotAuthorizationContext.highRisk().orElseThrow(ApprovalRetentionErrors::forbidden);
        if (!route.equals(evidence.routeContractKey()) || !route.equals(high.routeContractKey())
                || !capability.equals(high.capabilityContractKey()) || !"STEPUP-MGMT-HIGH-V1".equals(high.activationPolicy())
                || !("110".equals(evidence.rolloutState()) || "111".equals(evidence.rolloutState()))
                || !evidence.validUntil().isAfter(OffsetDateTime.now())
                || !evidence.contextScopeKey().equals(scope.opaqueScopeKey())) throw ApprovalRetentionErrors.forbidden();
        if (headers==null || headers.challenge()==null || headers.challenge().isBlank())
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,"Auth-signed retention challenge is required.");
        if (!key.equals(headers.idempotencyKey()) || headers.expectedObjectVersion()==null
                || headers.expectedObjectVersion()!=version || !evidence.revision().equals(headers.decisionRevision()))
            throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH,"Retention command headers changed.");
        return verifier.verify(headers.challenge(),new ApprovalStepUpVerifier.CommandBinding(actor.userId(),actor.tenantId(),
                route,evidence.contextKey(),high.activationPolicy(),capability,scope.opaqueScopeKey(),targetType,target.toString(),
                version,"POST","/api/approvals"+nativePath,key,verifier.payloadSha256(body),evidence.revision()));
    }
    public void consume(ApprovalStepUpVerifier.VerifiedChallenge challenge) {
        replay.assertNotConsumed(challenge.challengeId(),challenge.nonce()); replay.consume(challenge);
    }
}
