package com.dwp.services.time.clockingestion;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockStepUpHeaders;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VerifiedClockRequest;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VoidClockCommand;
import java.time.Instant;
import java.util.UUID;

/** Deterministic owner-bound proof fixture; cryptographic verification has a dedicated test. */
final class ClockStepUpTestVerifier implements ClockStepUpVerifier {

    @Override
    public ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge verifyVoid(
            VerifiedClockRequest authority,
            UUID eventId,
            long eventVersion,
            UUID idempotencyKey,
            VoidClockCommand command,
            ClockStepUpHeaders headers) {
        if (headers == null || headers.challenge() == null || headers.challenge().isBlank()) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED);
        }
        if (headers.expectedObjectVersion() == null
                || headers.expectedObjectVersion() != eventVersion) {
            throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        }
        if (!authority.decisionRevision().equals(headers.decisionRevision())) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT);
        }
        var binding = new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                authority.actorId(),
                authority.tenantId(),
                AuthSignedClockStepUpVerifier.ROUTE_CONTRACT_KEY,
                authority.contextKey(),
                AuthSignedClockStepUpVerifier.ACTIVATION_POLICY,
                AuthSignedClockStepUpVerifier.CAPABILITY_CONTRACT_KEY,
                authority.gatewayScopeKey(),
                AuthSignedClockStepUpVerifier.TARGET_TYPE,
                eventId.toString(),
                eventVersion,
                AuthSignedClockStepUpVerifier.COMMAND_METHOD,
                AuthSignedClockStepUpVerifier.PUBLIC_PATH_PREFIX + eventId + "/voids",
                idempotencyKey.toString(),
                ClockDigests.sha256("ClockVoidStepUpTest.v1", command.reasonCode()),
                authority.decisionRevision());
        return new ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge(
                headers.challenge(),
                headers.challenge() + "-nonce",
                "https://auth.test",
                binding,
                Instant.parse("2030-01-01T00:00:00Z"));
    }
}
