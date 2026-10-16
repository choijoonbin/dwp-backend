package com.dwp.services.people.hris.home;

import java.time.Instant;
import java.util.Optional;

/** Closed People data projection; no person rows or free-form attributes leave this port. */
interface PeopleHomeSourcePort {

    Snapshot snapshot(PeopleHomeAuthorityPort.CurrentAuthority authority);

    record Snapshot(
            Optional<String> employmentStatus,
            Optional<String> organizationLabel,
            Optional<Long> pendingActionCount,
            long sourceVersion,
            Instant asOf) {
        public Snapshot {
            employmentStatus = employmentStatus == null ? Optional.empty() : employmentStatus;
            organizationLabel = organizationLabel == null ? Optional.empty() : organizationLabel;
            pendingActionCount = pendingActionCount == null
                    ? Optional.empty() : pendingActionCount;
            if (sourceVersion < 1 || asOf == null
                    || pendingActionCount.filter(value -> value >= 0 && value <= 999).isEmpty()
                    && pendingActionCount.isPresent()) {
                throw new IllegalArgumentException("People Home source projection is invalid.");
            }
        }
    }
}
