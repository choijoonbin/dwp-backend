package com.dwp.services.people.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import org.springframework.stereotype.Component;

/**
 * Binds exact HCM PEP evidence to the People-owned high-risk command and
 * atomically consumes the signed challenge in the caller's transaction.
 */
@Component
public class HcmHighRiskCommandGuard {

    private static final java.util.Set<String> ACTIVATION_POLICIES = java.util.Set.of(
            "STEPUP-MGMT-HIGH-V1", "STEPUP-MGMT-CRITICAL-V1");

    private final HcmStepUpVerifier verifier;
    private final HcmStepUpReplayRepository replay;

    public HcmHighRiskCommandGuard(
            HcmStepUpVerifier verifier,
            HcmStepUpReplayRepository replay) {
        this.verifier = verifier;
        this.replay = replay;
    }

    public void require(
            String capabilityContractKey,
            String targetType,
            String targetId,
            long currentVersion,
            String publicPath,
            Object canonicalPayload,
            HcmStepUpHeaders headers) {
        HcmPepContext.Evidence current = HcmPepContext.current();
        if (current == null) return; // Baseline rollout remains on its legacy contract.
        HcmV3PepRegistry.RouteAuthority authority = current.authority();
        if (!authority.highRisk()) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The enforced HCM route has no high-risk command binding.");
        }
        HcmV3PepRegistry.StepUpBinding stepUp = authority.stepUpBinding();
        if (!capabilityContractKey.equals(authority.capabilityContractKey())
                || !ACTIVATION_POLICIES.contains(authority.activationPolicy())
                || !targetType.equals(stepUp.targetType())
                || !"people".equals(stepUp.ownerServiceKey())
                || !"dwp-people-server".equals(stepUp.audience())
                || !authority.publicPath().equals(publicPath)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The exact HCM route does not authorize this high-risk command.");
        }
        if (headers == null || blank(headers.challenge())
                || blank(headers.idempotencyKey()) || blank(headers.decisionRevision())
                || headers.expectedObjectVersion() == null) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "Command-bound People step-up headers are required.");
        }
        if (headers.idempotencyKey().length() > 200
                || headers.decisionRevision().length() > 200
                || headers.expectedObjectVersion() != currentVersion) {
            throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "People step-up headers do not match the current object version.");
        }
        if (!headers.decisionRevision().equals(current.decisionRevision())
                || current.revalidateAt() == null
                || !current.revalidateAt().isAfter(java.time.OffsetDateTime.now())
                || blank(current.contextKey()) || blank(current.scopeKey())
                || !("110".equals(current.rolloutState())
                || "111".equals(current.rolloutState()))) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                    "HCM authority changed after challenge issuance.");
        }
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (actor.userId() == null || actor.tenantId() == null
                || blank(targetId) || targetId.length() > 200 || currentVersion < 0) {
            throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "People step-up target evidence is invalid.");
        }
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding =
                new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                        actor.userId(), actor.tenantId(), authority.routeContractKey(),
                        current.contextKey(), authority.activationPolicy(),
                        capabilityContractKey, current.scopeKey(), targetType, targetId,
                        currentVersion, authority.method(), publicPath,
                        headers.idempotencyKey(), verifier.payloadSha256(canonicalPayload),
                        current.decisionRevision());
        ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge challenge =
                verifier.verify(headers.challenge(), binding);
        // The caller is @Transactional. A later mutation failure rolls this insert
        // back; a concurrent replay blocks on the unique challenge/nonce key.
        replay.consume(challenge);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
