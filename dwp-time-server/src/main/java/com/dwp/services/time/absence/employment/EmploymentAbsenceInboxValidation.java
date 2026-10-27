package com.dwp.services.time.absence.employment;

import com.dwp.core.event.DomainEventInboxStrictValidationSupport;
import java.util.UUID;

/** Canonicalizes stored inbox identifiers and terminal reasons for reconciliation. */
final class EmploymentAbsenceInboxValidation {

    private EmploymentAbsenceInboxValidation() {
    }

    static UUID canonicalRelationshipId(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    static String canonicalDeadReason(String storedReason) {
        if (DomainEventInboxStrictValidationSupport.PAYLOAD_CONFLICT_REASON
                .equals(storedReason)) {
            return "PAYLOAD_CONFLICT";
        }
        if (storedReason != null && storedReason.startsWith(
                DomainEventInboxStrictValidationSupport.INVALID_EVENT_REASON)) {
            return "EVENT_CONTRACT_VIOLATION";
        }
        try {
            EmploymentAbsenceContract.code(storedReason, "storedReason");
            return storedReason;
        } catch (RuntimeException ignored) {
            return "INBOX_DEAD_BLOCKER";
        }
    }

}
