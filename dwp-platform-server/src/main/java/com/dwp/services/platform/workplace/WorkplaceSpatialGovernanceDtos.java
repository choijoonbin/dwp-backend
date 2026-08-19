package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkplaceSpatialGovernanceDtos {

    private WorkplaceSpatialGovernanceDtos() {
    }

    public enum CampusState { ACTIVE, MAINTENANCE, CLOSED }
    public enum ZoneType { GENERAL, WORK_AREA, COLLABORATION, QUIET, SERVICE, RESTRICTED }
    public enum SpatialState { ACTIVE, MAINTENANCE, CLOSED }
    public enum AccessSubjectType { USER, GROUP_REF }
    public enum AccessPermission { VIEW, BOOK, MANAGE }
    public enum AccessEffect { ALLOW, DENY }
    public enum RuleState { ACTIVE, INACTIVE }
    public enum PolicyScopeType { TENANT, CAMPUS, SITE, FLOOR, ZONE, RESOURCE }
    public enum RevisionState { DRAFT, REVIEW, PUBLISHED, ARCHIVED }
    public enum DelegateType { USER, GROUP_REF }
    public enum DelegatedScopeType { SITE, GROUP_REF }
    public enum DelegatedPermission {
        CATALOG_VIEW,
        CATALOG_MANAGE,
        ACCESS_MANAGE,
        POLICY_MANAGE,
        FLOOR_PLAN_MANAGE,
        DELEGATION_VIEW
    }
    public enum DelegationState { ACTIVE, REVOKED }

    @Schema(name = "WorkplaceGovernanceCampus")
    public record Campus(
            UUID campusId,
            String code,
            String nameKo,
            String nameEn,
            CampusState state,
            long buildingCount,
            long version) {
    }

    public record CampusRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,79}") String code,
            @NotBlank @Size(max = 160) String nameKo,
            @NotBlank @Size(max = 160) String nameEn,
            @NotNull CampusState state,
            @Min(0) Long version) {
    }

    public record SiteCampusAssignment(
            UUID siteId,
            UUID campusId,
            long siteVersion) {
    }

    public record SiteCampusAssignmentRequest(
            @NotNull UUID campusId,
            @NotNull @Min(0) Long siteVersion) {
    }

    @Schema(name = "WorkplaceGovernanceZone")
    public record Zone(
            UUID zoneId,
            UUID floorId,
            String code,
            String nameKo,
            String nameEn,
            ZoneType type,
            JsonNode boundary,
            SpatialState state,
            long sectionCount,
            long resourceCount,
            long version) {
    }

    public record ZoneRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,79}") String code,
            @NotBlank @Size(max = 160) String nameKo,
            @NotBlank @Size(max = 160) String nameEn,
            @NotNull ZoneType type,
            @NotNull JsonNode boundary,
            @NotNull SpatialState state,
            @Min(0) Long version) {
    }

    @Schema(name = "WorkplaceGovernanceSection")
    public record Section(
            UUID sectionId,
            UUID floorId,
            UUID zoneId,
            String code,
            String nameKo,
            String nameEn,
            JsonNode boundary,
            SpatialState state,
            long resourceCount,
            long version) {
    }

    public record SectionRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,79}") String code,
            @NotBlank @Size(max = 160) String nameKo,
            @NotBlank @Size(max = 160) String nameEn,
            @NotNull JsonNode boundary,
            @NotNull SpatialState state,
            @Min(0) Long version) {
    }

    @Schema(name = "WorkplaceSiteAccessRule")
    public record SiteAccessRule(
            UUID accessRuleId,
            UUID siteId,
            AccessSubjectType subjectType,
            Long subjectUserId,
            UUID subjectGroupRef,
            AccessPermission permission,
            AccessEffect effect,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil,
            RuleState state,
            long version) {
    }

    public record SiteAccessRuleRequest(
            @NotNull AccessSubjectType subjectType,
            @Min(1) Long subjectUserId,
            UUID subjectGroupRef,
            @NotNull AccessPermission permission,
            @NotNull AccessEffect effect,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil,
            @NotNull RuleState state,
            @Min(0) Long version) {
    }

    public record SiteAccessDecision(
            UUID siteId,
            Long userId,
            AccessPermission requestedPermission,
            boolean allowed,
            String decision,
            List<UUID> matchedRuleIds,
            OffsetDateTime evaluatedAt) {
    }

    @Schema(name = "WorkplacePolicyOverride")
    public record PolicyOverride(
            UUID policyOverrideId,
            PolicyScopeType scopeType,
            UUID scopeId,
            JsonNode policyPatch,
            RuleState state,
            long version) {
    }

    public record PolicyOverrideRequest(
            @NotNull PolicyScopeType scopeType,
            UUID scopeId,
            @NotNull JsonNode policyPatch,
            @NotNull RuleState state,
            @Min(0) Long version) {
    }

    public record PolicyFieldSource(
            PolicyScopeType scopeType,
            UUID scopeId,
            UUID policyOverrideId,
            long version) {
    }

    public record EffectivePolicyPreview(
            PolicyScopeType targetScopeType,
            UUID targetScopeId,
            JsonNode effectivePolicy,
            Map<String, PolicyFieldSource> fieldSources,
            List<UUID> appliedOverrideIds,
            OffsetDateTime generatedAt) {
    }

    @Schema(name = "WorkplaceFloorPlanPlacement")
    public record FloorPlanPlacement(
            UUID placementId,
            UUID resourceId,
            long resourceVersion,
            UUID zoneId,
            UUID sectionId,
            BigDecimal positionX,
            BigDecimal positionY,
            BigDecimal widthPercent,
            BigDecimal heightPercent,
            int rotationDegrees,
            JsonNode metadata,
            long version) {
    }

    public record FloorPlanPlacementRequest(
            @NotNull UUID resourceId,
            @NotNull @Min(0) Long resourceVersion,
            @NotNull UUID zoneId,
            UUID sectionId,
            @NotNull @DecimalMin("0") @DecimalMax("99.99") BigDecimal positionX,
            @NotNull @DecimalMin("0") @DecimalMax("99.99") BigDecimal positionY,
            @NotNull @DecimalMin("1") @DecimalMax("100") BigDecimal widthPercent,
            @NotNull @DecimalMin("1") @DecimalMax("100") BigDecimal heightPercent,
            @Min(-359) @Max(359) int rotationDegrees,
            @NotNull JsonNode metadata) {
    }

    @Schema(name = "WorkplaceFloorPlanRevision")
    public record FloorPlanRevision(
            UUID revisionId,
            UUID floorId,
            long revisionNumber,
            UUID basedOnRevisionId,
            UUID restoreSourceRevisionId,
            RevisionState state,
            int planWidth,
            int planHeight,
            String backgroundAssetPath,
            String backgroundAssetKey,
            String backgroundContentType,
            Long backgroundSizeBytes,
            String backgroundSha256,
            String changeSummary,
            String contentHash,
            int placementCount,
            OffsetDateTime submittedAt,
            Long submittedBy,
            OffsetDateTime publishedAt,
            Long publishedBy,
            long version) {
    }

    public record FloorPlanRevisionSnapshot(
            FloorPlanRevision revision,
            List<FloorPlanPlacement> placements) {
    }

    public record CreateFloorPlanRevisionRequest(
            UUID basedOnRevisionId,
            @NotBlank @Size(max = 500) String changeSummary) {
    }

    public record FloorPlanSnapshotRequest(
            @Min(400) @Max(5000) int planWidth,
            @Min(300) @Max(5000) int planHeight,
            @Size(max = 1000) String backgroundAssetPath,
            @Size(max = 320) String backgroundAssetKey,
            @Size(max = 80) String backgroundContentType,
            @Min(1) Long backgroundSizeBytes,
            @Pattern(regexp = "[0-9a-f]{64}") String backgroundSha256,
            @NotBlank @Size(max = 500) String changeSummary,
            @NotNull @Size(max = 5000)
            List<@Valid FloorPlanPlacementRequest> placements,
            @NotNull @Min(0) Long version) {
    }

    public record RevisionTransitionRequest(
            @NotNull @Min(0) Long version,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record FloorPlanProjection(
            UUID floorId,
            UUID publishedRevisionId,
            long revisionNumber,
            int planWidth,
            int planHeight,
            String backgroundAssetPath,
            List<FloorPlanPlacement> placements,
            OffsetDateTime publishedAt) {
    }

    @Schema(name = "WorkplaceDelegatedAdminScope")
    public record DelegatedAdminScope(
            UUID delegationId,
            DelegateType delegateType,
            Long delegateUserId,
            UUID delegateGroupRef,
            DelegatedScopeType scopeType,
            UUID siteId,
            UUID managedGroupRef,
            List<DelegatedPermission> permissions,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil,
            DelegationState state,
            long version) {
    }

    public record DelegatedAdminScopeRequest(
            @NotNull DelegateType delegateType,
            @Min(1) Long delegateUserId,
            UUID delegateGroupRef,
            @NotNull DelegatedScopeType scopeType,
            UUID siteId,
            UUID managedGroupRef,
            @NotNull @NotEmpty @Size(max = 6) List<@NotNull DelegatedPermission> permissions,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil,
            @NotNull DelegationState state,
            @Min(0) Long version) {
    }

    public record EffectiveDelegatedScope(
            UUID delegationId,
            DelegatedScopeType scopeType,
            UUID scopeId,
            List<DelegatedPermission> permissions,
            OffsetDateTime validUntil) {
    }
}
