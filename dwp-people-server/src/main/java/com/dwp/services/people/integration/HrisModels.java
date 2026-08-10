package com.dwp.services.people.integration;

import java.time.LocalDate;
import java.util.List;

public final class HrisModels {

    private HrisModels() {
    }

    public record WorkforceBatch(
            String sourceKey,
            String sourceType,
            String sourceSchemaVersion,
            String watermark,
            boolean synthetic,
            List<WorkerRecord> workers) {
    }

    public record WorkerRecord(
            String externalId,
            String sourceVersion,
            String workerNumber,
            String workerType,
            String workerStatus,
            String displayName,
            String givenName,
            String familyName,
            String preferredLocale,
            String timeZone,
            String workEmail,
            LocalDate originalHireDate,
            Employer employer,
            List<Assignment> assignments) {
    }

    public record Employer(String key, String legalName, String countryCode) {
    }

    public record Assignment(
            String externalId,
            String sourceVersion,
            String assignmentKey,
            String assignmentStatus,
            boolean primary,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            String businessTitle,
            String managerAssignmentKey,
            String costCenterKey,
            String changeReasonCode,
            Organization organization,
            JobProfile jobProfile,
            JobGrade jobGrade,
            Location location,
            Position position) {
    }

    public record Organization(
            String key,
            String name,
            String type,
            String parentKey) {
    }

    public record JobProfile(
            String key,
            String name,
            String familyKey,
            String managementLevel) {
    }

    public record JobGrade(
            String key,
            String name,
            int levelOrder,
            String careerTrack) {
    }

    public record Location(
            String key,
            String name,
            String countryCode,
            String timeZone) {
    }

    public record Position(String key, String title) {
    }
}
