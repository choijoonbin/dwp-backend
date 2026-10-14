package com.dwp.services.time.timecard;

import com.dwp.services.time.timecard.TimecardModels.VerifiedTimecardRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Trusted Gateway-to-owner authority seam for the five exact Timecard operations. */
public interface TimecardOwnerAuthoritySource {
    Optional<VerifiedTimecardRequest> verify(
            HttpServletRequest request, TimecardOwnerRoute route);
}
