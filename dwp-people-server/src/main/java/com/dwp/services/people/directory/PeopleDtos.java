package com.dwp.services.people.directory;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PeopleDtos {

    private PeopleDtos() {
    }

    public record PersonSummary(
            UUID personId,
            String displayName,
            String preferredLocale,
            String timeZone,
            String lifecycleState,
            String workerNumber,
            String workerType,
            String workerStatus,
            String assignmentKey,
            String businessTitle,
            UUID organizationId,
            String organizationKey,
            String organizationName,
            String jobProfileName,
            String managementLevel,
            String jobGradeKey,
            String jobGradeName,
            String locationKey,
            String locationName,
            String workEmail,
            String profileImageKey,
            LocalDate assignmentEffectiveFrom,
            UUID managerPersonId,
            String managerDisplayName,
            int directReportCount,
            DataAccess dataAccess) {
    }

    public record PersonDetail(
            PersonSummary person,
            LocalDate originalHireDate,
            String legalEmployerName,
            String managerAssignmentKey,
            List<AssignmentSummary> assignments) {
    }

    public record AssignmentSummary(
            String assignmentKey,
            String assignmentStatus,
            boolean primaryAssignment,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            String businessTitle,
            String organizationName,
            String jobProfileName,
            String jobGradeName,
            String locationName,
            String managerAssignmentKey,
            String changeReasonCode) {
    }

    public record DataAccess(
            String classification,
            boolean workerNumberMasked,
            List<String> excludedFieldGroups) {
    }

    public record CursorPage<T>(
            List<T> items,
            String nextCursor,
            int size,
            boolean hasMore,
            LocalDate asOf) {
    }
}
