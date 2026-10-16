package com.dwp.services.time.home;

import com.dwp.services.time.home.TimeHomeCurrentAuthorityPort.CurrentAuthority;
import java.time.Instant;
import java.util.Objects;

/** Reads only the closed, browser-safe Time values used by the Home profile. */
@FunctionalInterface
interface TimeHomeProjectionRepository {

    Projection project(CurrentAuthority authority, Instant cutoff);

    record Projection(
            String clockState,
            long openTimecardCount,
            long pendingApprovalCount,
            long sourceVersion,
            Instant asOf) {
        public Projection {
            if (clockState == null || !java.util.Set.of(
                    "NOT_CLOCKED_IN", "CLOCKED_IN", "ON_BREAK", "CLOCKED_OUT",
                    "NOT_APPLICABLE").contains(clockState)
                    || openTimecardCount < 0 || pendingApprovalCount < 0
                    || sourceVersion < 1 || asOf == null) {
                throw new IllegalArgumentException("Time Home projection is invalid");
            }
        }

        long boundedOpenCount() {
            return Math.min(999L, openTimecardCount);
        }

        long boundedPendingCount() {
            return Math.min(999L, pendingApprovalCount);
        }
    }
}
