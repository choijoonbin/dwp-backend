package com.dwp.services.time.clockingestion;

import com.dwp.services.time.clockingestion.ClockIngestionModels.VerifiedClockRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Control-owned bridge from generated product authority and TIM target projections.
 *
 * <p>The bridge must resolve authority through {@link ClockOwnerRoute#authorityContractKey()} so
 * PAGE reads cannot fall back to legacy DATA routes and ACTION writes retain their canonical
 * route contracts. No permissive module-local implementation exists. Enabling the feature
 * without an exact bridge therefore fails application startup rather than trusting
 * caller-supplied identity.
 */
public interface ClockOwnerAuthoritySource {

    Optional<VerifiedClockRequest> verify(HttpServletRequest request, ClockOwnerRoute route);
}
