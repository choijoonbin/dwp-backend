package com.dwp.services.people.hris.employment;

import java.time.LocalDate;
import java.util.UUID;

/** Read-only views required by employment publication evidence. */
final class EmploymentEventPublicationContract {

    private EmploymentEventPublicationContract() {
    }

    interface EventView {
        UUID publicId();

        String eventType();

        LocalDate effectiveDate();

        String lifecycleState();

        String contentDigest();

        long version();

        UUID originatingCorrelationId();
    }

    interface PreflightView {
        ReferenceView location();
    }

    interface ReferenceView {
        UUID publicId();

        String businessKey();

        String timeZone();

        UUID calendarPublicId();
    }

    interface WorkerView {
        UUID publicId();

        long version();
    }

    interface RelationshipView {
        UUID publicId();

        long version();
    }

    interface AssignmentView {
        UUID publicId();

        long version();
    }
}
