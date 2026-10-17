package com.dwp.services.people.hris.workbench.hrm007;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read-only projection port over the existing HRM-008/010 and policy sources. */
interface Hrm007ProjectionSource {

    List<ProjectionRow> read(Hrm007QueryDefinition definition, SourceRequest request);

    record SourceRequest(
            long tenantId,
            Instant asOf,
            LocalDate scopeDate,
            LocalDate effectiveOn,
            Hrm007Authority.TargetPopulation population) {
    }

    sealed interface ProjectionRow
            permits PeopleChangeRow, AppointmentRow, PeopleEmploymentRow {

        String itemPublicId();

        String title();

        String statusCode();

        long revision();
    }

    record PeopleChangeRow(
            String itemPublicId,
            String title,
            String statusCode,
            int summaryItemCount,
            String limitedEmploymentCode,
            LocalDate effectiveDate,
            long revision,
            String requesterPublicId) implements ProjectionRow {
    }

    record AppointmentRow(
            String itemPublicId,
            String title,
            String statusCode,
            Instant appointmentStart,
            Instant appointmentEnd,
            String assignmentPublicId,
            String assignmentCode,
            String assignmentLabel,
            LocalDate employmentFrom,
            LocalDate employmentTo,
            long revision) implements ProjectionRow {
    }

    record PeopleEmploymentRow(
            String itemPublicId,
            String title,
            String statusCode,
            String policyMetadataKey,
            String employmentRuleKey,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            int impactItemCount,
            long revision) implements ProjectionRow {
    }
}
