package com.dwp.services.people.workforce;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Public, owner-projected People 360 read contract. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class People360Dtos {

    public static final int SCHEMA_VERSION = 1;
    public static final String MASK_LITERAL = "••••";

    private People360Dtos() {
    }

    public enum ProjectionState {
        READY,
        PARTIAL
    }

    public enum Archetype {
        SELF,
        MANAGER,
        HR_OPERATOR,
        AUDITOR
    }

    public enum FieldDecisionValue {
        VIEW,
        MASK,
        OMIT
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Snapshot(
            int schemaVersion,
            LocalDate asOf,
            ProjectionState state,
            String projectionRevision,
            Person person,
            Employment employment,
            PrimaryAssignment primaryAssignment,
            Access access) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(
            List<Snapshot> items,
            String nextCursor,
            int size,
            boolean hasMore,
            LocalDate asOf) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Person(
            UUID personId,
            String displayName,
            String preferredLocale,
            String timeZone,
            String lifecycleState) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Employment(
            String workerNumber,
            String workerType,
            String workerStatus,
            LocalDate originalHireDate,
            String relationshipType,
            LocalDate relationshipStartDate,
            LocalDate relationshipEndDate,
            String legalEmployerName) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PrimaryAssignment(
            String assignmentKey,
            String assignmentStatus,
            String businessTitle,
            String organizationName,
            String jobProfileName,
            String jobGradeName,
            String locationName,
            String managerDisplayName,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Access(
            Archetype archetype,
            String scope,
            String policyRevision,
            List<FieldDecision> fieldDecisions) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldDecision(
            String field,
            FieldDecisionValue decision) {
    }
}
