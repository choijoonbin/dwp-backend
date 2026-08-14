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
            List<AssignmentSummary> assignments,
            List<Worker> workers) {
    }

    public record Worker(
            UUID workerId,
            String workerNumber,
            String workerType,
            String workerStatus,
            LocalDate originalHireDate,
            List<WorkRelationship> workRelationships) {
    }

    public record WorkRelationship(
            UUID workRelationshipId,
            String relationshipKey,
            String relationshipType,
            boolean primaryRelationship,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate projectedEndDate,
            String legalEmployerKey,
            String legalEmployerName,
            String legalEmployerCountryCode,
            List<WorkAssignment> assignments) {
    }

    public record WorkAssignment(
            UUID assignmentId,
            String assignmentKey,
            String assignmentStatus,
            boolean primaryAssignment,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            int effectiveSequence,
            String businessTitle,
            UUID organizationId,
            String organizationKey,
            String organizationName,
            String jobProfileName,
            String jobGradeName,
            String locationKey,
            String locationName,
            String managerAssignmentKey,
            String changeReasonCode) {
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
