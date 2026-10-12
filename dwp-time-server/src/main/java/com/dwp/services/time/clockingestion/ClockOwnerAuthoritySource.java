package com.dwp.services.time.clockingestion;

import com.dwp.services.time.clockingestion.ClockIngestionModels.VerifiedClockRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Control-owned bridge from generated product authority and TIM target projections.
 *
 * <p>The bridge must resolve authority through {@link ClockOwnerRoute#authorityContractKey()} so
 * PAGE reads cannot fall back to legacy DATA routes and ACTION writes retain their canonical
 * route contracts. The production adapter accepts only the gateway's sealed decision projection;
 * enabling the feature without an exact projection resolver therefore fails closed rather than
 * trusting caller-supplied identity.
 */
public interface ClockOwnerAuthoritySource {

    Optional<VerifiedClockRequest> verify(HttpServletRequest request, ClockOwnerRoute route);
}
