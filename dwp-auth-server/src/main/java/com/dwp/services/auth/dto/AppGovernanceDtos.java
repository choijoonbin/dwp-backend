package com.dwp.services.auth.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AppGovernanceDtos {

    private AppGovernanceDtos() {
    }

    public record Dashboard(
            Metrics metrics,
            List<Responsibility> responsibilities,
            List<Principal> principals,
            List<ResourceSet> resourceSets,
            List<Assignment> assignments) {
    }

    public record Metrics(
            long activeAssignments,
            long pendingApprovals,
            long reviewsDueSoon,
            long resourcesWithoutOwner) {
    }

    public record Responsibility(
            String code,
            String displayName,
            String description,
            String riskTier,
            int sortOrder) {
    }

    public record Principal(String type, String ref, String displayName, String detail) {
    }

    public record ResourceSet(
            UUID resourceSetId,
            String key,
            String name,
            String description,
            String lifecycleState,
            long version,
            List<ResourceMember> resources) {
    }

    public record ResourceMember(String resourceType, String resourceKey, String resourceName) {
    }

    public record Assignment(
            UUID assignmentId,
            String principalType,
            String principalRef,
            String principalName,
            String responsibilityCode,
            UUID resourceSetId,
            String resourceSetKey,
            String resourceSetName,
            String assignmentSource,
            String lifecycleState,
            OffsetDateTime validFrom,
            OffsetDateTime validTo,
            OffsetDateTime reviewDueAt,
            String justification,
            Long requestedBy,
            String requestedByName,
            Long approvedBy,
            String approvedByName,
            OffsetDateTime approvedAt,
            String decisionReason,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    public record ResourceRole(
            String responsibilityCode,
            String resourceType,
            String resourceKey,
            UUID resourceSetId,
            String resourceSetKey,
            OffsetDateTime validTo) {
    }

    public record CreateResourceSetRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,79}") String key,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @NotEmpty @Size(max = 100) List<@NotBlank String> resourceKeys) {
    }

    public record UpdateResourceSetRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @NotEmpty @Size(max = 100) List<@NotBlank String> resourceKeys,
            @NotNull @Min(0) Long version) {
    }

    public record CreateAssignmentRequest(
            @NotBlank @Pattern(regexp = "USER|GROUP") String principalType,
            @NotBlank @Size(max = 160) String principalRef,
            @NotBlank @Pattern(regexp = "APP_[A-Z_]{3,45}") String responsibilityCode,
            @NotNull UUID resourceSetId,
            @Future OffsetDateTime validTo,
            @NotBlank @Size(min = 10, max = 1000) String justification) {
    }

    public record AssignmentDecisionRequest(
            @NotBlank @Pattern(regexp = "APPROVED|DENIED") String decision,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotNull @Min(0) Long version) {
    }

    public record RevokeAssignmentRequest(
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotNull @Min(0) Long version) {
    }
}
