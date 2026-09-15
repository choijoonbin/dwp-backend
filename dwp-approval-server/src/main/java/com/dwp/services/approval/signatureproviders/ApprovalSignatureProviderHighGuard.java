package com.dwp.services.approval.signatureproviders;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.*;
import java.util.UUID;

final class ApprovalSignatureProviderHighGuard {
    private final ApprovalStepUpVerifier verifier;
    private final ApprovalStepUpReplayRepository replay;

    ApprovalSignatureProviderHighGuard(ApprovalStepUpVerifier verifier,
                                       ApprovalStepUpReplayRepository replay) {
        this.verifier = verifier; this.replay = replay;
    }

    ApprovalStepUpVerifier.VerifiedChallenge verify(SignatureProviderOperation operation,
            SignatureProviderCurrentAuthority.Current current, String targetType, UUID targetId,
            long version, String nativePath, String key, Object body, ApprovalStepUpHeaders headers) {
        if (!operation.highRisk() || headers == null || headers.challenge() == null
                || headers.challenge().isBlank())
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "Auth-signed native signature challenge is required.");
        if (!key.equals(headers.idempotencyKey()) || headers.expectedObjectVersion() == null
                || headers.expectedObjectVersion() != version
                || !current.decision().revision().equals(headers.decisionRevision()))
            throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "Native signature command headers changed.");
        var binding = new ApprovalStepUpVerifier.CommandBinding(
                current.actor().userId(), current.actor().tenantId(), operation.routeContractKey(),
                current.decision().contextKey(), current.route().activationPolicy(),
                current.route().capabilityContractKey(), current.scope().opaqueScopeKey(),
                targetType, targetId.toString(), version, operation.method(),
                "/api/approvals" + nativePath, key, verifier.payloadSha256(body),
                current.decision().revision());
        return verifier.verify(headers.challenge(), binding);
    }

    void consume(ApprovalStepUpVerifier.VerifiedChallenge challenge) {
        replay.assertNotConsumed(challenge.challengeId(), challenge.nonce());
        replay.consume(challenge);
    }
}
