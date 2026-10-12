package com.dwp.services.time.clockingestion;

import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockStepUpHeaders;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VerifiedClockRequest;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VoidClockCommand;
import java.util.UUID;

/** Verifies one command-bound Auth challenge for the exact TIM corrective-void target. */
interface ClockStepUpVerifier {

    ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge verifyVoid(
            VerifiedClockRequest authority,
            UUID eventId,
            long eventVersion,
            UUID idempotencyKey,
            VoidClockCommand command,
            ClockStepUpHeaders headers);
}
