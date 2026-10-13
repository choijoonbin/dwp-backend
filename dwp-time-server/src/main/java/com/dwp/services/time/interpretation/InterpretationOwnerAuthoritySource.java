package com.dwp.services.time.interpretation;

import com.dwp.services.time.interpretation.InterpretationModels.VerifiedInterpretationRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Trusted Gateway-to-owner authority seam for exact interpretation routes. */
public interface InterpretationOwnerAuthoritySource {

    Optional<VerifiedInterpretationRequest> verify(
            HttpServletRequest request, InterpretationOwnerRoute route);
}
