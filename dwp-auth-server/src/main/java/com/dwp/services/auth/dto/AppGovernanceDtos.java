package com.dwp.services.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AppGovernanceDtos {

    private AppGovernanceDtos() {
    }

    public record Dashboard(
            Metrics metrics,
            List<Responsibility> responsibilities,
            List<Principal> principals,
            List<ResourceSet> resourceSets,
            List<Assignment> assignments,
            List<AppAdminPreset> presetCatalog,
            List<AppAdminPresetAssignment> presetAssignments,
            List<AppAdminPresetReview> presetReviews) {

        public Dashboard(
                Metrics metrics,
                List<Responsibility> responsibilities,
                List<Principal> principals,
                List<ResourceSet> resourceSets,
                List<Assignment> assignments) {
            this(metrics, responsibilities, principals, resourceSets, assignments,
                    List.of(), List.of(), List.of());
        }
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
            OffsetDateTime updatedAt,
            @Schema(description = "Actor-specific, non-authoritative hint that the current "
                    + "dashboard viewer may decide this assignment through the one-time "
                    + "first APP_ACCESS_APPROVER bootstrap path. The decision endpoint always "
                    + "revalidates authority, independence, state, scope, and version.")
            boolean firstApproverBootstrapEligible) {

        public Assignment withFirstApproverBootstrapEligible(boolean eligible) {
            return new Assignment(
                    assignmentId, principalType, principalRef, principalName,
                    responsibilityCode, resourceSetId, resourceSetKey, resourceSetName,
                    assignmentSource, lifecycleState, validFrom, validTo, reviewDueAt,
                    justification, requestedBy, requestedByName, approvedBy, approvedByName,
                    approvedAt, decisionReason, version, createdAt, updatedAt, eligible);
        }
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

    /** Product-specific minimum package; intentionally not an all-admin role. */
    public record AppAdminPreset(
            String presetCode,
            String productKey,
            String appResourceKey,
            String displayName,
            String description,
            String responsibilityCode,
            String riskTier,
            long catalogVersion,
            boolean requestable,
            String unavailableReason,
            List<AppAdminPresetDuty> duties) {
    }

    public record AppAdminPresetDuty(
            String dutyCode,
            String legacyRoleCode,
            String resourceKey,
            String riskTier,
            boolean auditPolicyException,
            List<String> capabilityContractKeys) {
    }

    public record AppAdminPresetAssignment(
            UUID presetAssignmentId,
            String presetCode,
            String productKey,
            String presetName,
            String principalType,
            String principalRef,
            String principalName,
            UUID resourceSetId,
            String resourceSetKey,
            String resourceSetName,
            UUID responsibilityAssignmentId,
            String assignmentSource,
            String requestChannel,
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
            Long activatedBy,
            String activatedByName,
            OffsetDateTime activatedAt,
            String activationReason,
            Long revokedBy,
            String revokedByName,
            OffsetDateTime revokedAt,
            String revocationReason,
            long version,
            long catalogVersion,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            List<AppAdminPresetDutyAssignment> duties) {
    }

    public record AppAdminPresetDutyAssignment(
            UUID assignmentId,
            String dutyCode,
            String lifecycleState,
            long version) {
    }

    public record AppAdminPresetReview(
            UUID reviewId,
            Long userId,
            String userName,
            String sourceRoleCode,
            String dutyCode,
            String reasonCode,
            UUID resourceSetId,
            String resourceSetName,
            Map<String, Object> evidence,
            String lifecycleState,
            Long resolvedBy,
            OffsetDateTime resolvedAt,
            String resolutionReason,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    public record CreateAppAdminPresetAssignmentRequest(
            @NotBlank @Pattern(regexp = "USER|GROUP") String principalType,
            @NotBlank @Size(max = 160) String principalRef,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,79}") String presetCode,
            @NotNull UUID resourceSetId,
            @NotNull @Future OffsetDateTime validTo,
            @NotNull @Future OffsetDateTime reviewDueAt,
            @NotBlank @Size(min = 10, max = 1000) String justification) {
    }

    public record AppAdminPresetResourceSetOption(
            UUID resourceSetId,
            String resourceSetKey,
            String resourceSetName) {
    }

    public record AppAdminPresetSelfServiceOption(
            AppAdminPreset preset,
            List<AppAdminPresetResourceSetOption> resourceSets) {
    }

    public record CreateSelfServicePresetRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,79}") String presetCode,
            @NotNull UUID resourceSetId,
            @NotNull @Future OffsetDateTime validTo,
            @NotNull @Future OffsetDateTime reviewDueAt,
            @NotBlank @Size(min = 10, max = 1000) String justification) {
    }

    public record AppAdminPresetDecisionRequest(
            @NotBlank @Pattern(regexp = "APPROVED|DENIED") String decision,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotNull @Min(0) Long version) {
    }

    public record RevokeAppAdminPresetRequest(
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotNull @Min(0) Long version) {
    }

    public record ActivateAppAdminPresetRequest(
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotNull @Min(0) Long version) {
    }

    public record AppAdminPresetReviewDecisionRequest(
            @NotBlank @Pattern(regexp = "RESOLVED|DISMISSED") String decision,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotNull @Min(0) Long version) {
    }
}
