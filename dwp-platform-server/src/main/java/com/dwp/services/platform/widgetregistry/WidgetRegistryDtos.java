package com.dwp.services.platform.widgetregistry;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WidgetRegistryDtos {
    private WidgetRegistryDtos() {}

    public static final int SCHEMA_VERSION = 1;

    /** Closed public vocabulary. Internal policy and safety causes never cross this API boundary. */
    public enum EffectiveCatalogState {
        AVAILABLE,
        ALREADY_ADDED,
        DEPRECATED,
        DENY
    }

    /** Stable, non-sensitive reason codes understood by member-facing catalog clients. */
    public enum EffectiveCatalogReason {
        NOT_AVAILABLE,
        DISABLED_BY_ORGANIZATION,
        APP_ACCESS_REQUIRED,
        INCOMPATIBLE,
        TEMPORARILY_UNAVAILABLE,
        DEPRECATED,
        AVAILABLE,
        ALREADY_ADDED
    }

    public record ReadinessResponse(
            int schemaVersion,
            @Schema(allowableValues = {"STATIC", "SHADOW", "AUTHORITATIVE"}) String migrationMode,
            boolean controlPlaneReady,
            boolean runtimeActivationReady,
            List<String> capabilities,
            long registryRevision,
            long policyRevision,
            long safetyRevision) {}

    public record DefinitionResponse(
            UUID definitionId,
            String definitionKey,
            String legacyWidgetKey,
            String ownerProductKey,
            String ownerTeamKey,
            @Schema(allowableValues = {"LOW", "MEDIUM", "HIGH"})
            String riskTier,
            @Schema(allowableValues = {"PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED"})
            String dataClassification,
            @Schema(allowableValues = {"ACTIVE", "RETIRED"})
            String definitionState,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            List<String> allowedTransitions) {}

    public record DefinitionPage(
            List<DefinitionResponse> items,
            int page,
            int size,
            long totalElements,
            boolean hasNext,
            String readRevision) {}

    public record DefinitionCreateRequest(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
            @Size(max = 160) String definitionKey,
            @Size(max = 80) String legacyWidgetKey,
            @NotBlank @Size(max = 120) String ownerProductKey,
            @NotBlank @Size(max = 120) String ownerTeamKey,
            @NotBlank @Pattern(regexp = "LOW|MEDIUM|HIGH") String riskTier,
            @NotBlank @Pattern(regexp = "PUBLIC|INTERNAL|CONFIDENTIAL|RESTRICTED")
            String dataClassification,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record DefinitionRetireRequest(
            UUID replacementDefinitionId,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String impactRevision,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record VersionCreateRequest(
            @NotBlank @Pattern(regexp = "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-((?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?")
            String semanticVersion,
            @NotNull JsonNode manifest,
            UUID predecessorVersionId,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record VersionUpdateRequest(
            @NotNull JsonNode manifest,
            UUID predecessorVersionId,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record TransitionRequest(
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record ValidateRequest(
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String manifestHash,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record ReviewDecisionRequest(
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotNull UUID validationRunId,
            @NotNull List<UUID> evidenceIds,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record PublishRequest(
            @NotBlank @Pattern(regexp = "STABLE|PREVIEW") String channel,
            @NotNull UUID validationRunId,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String manifestHash,
            @NotNull List<UUID> evidenceIds,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedImpactRevision,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record SafetyTransitionRequest(
            @NotBlank @Size(max = 64) String publicReasonCode,
            @NotBlank @Size(max = 128) String internalIncidentRef,
            UUID replacementVersionId,
            OffsetDateTime expiresAt,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedImpactRevision,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record DeprecateRequest(
            @NotNull UUID replacementVersionId,
            @NotNull OffsetDateTime deprecationEndsAt,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record VersionResponse(
            UUID versionId,
            UUID definitionId,
            String semanticVersion,
            JsonNode manifest,
            String manifestHash,
            @Schema(allowableValues = {"DRAFT", "VALIDATED", "SUBMITTED", "APPROVED", "REJECTED"})
            String workflowState,
            @Schema(allowableValues = {"UNPUBLISHED", "PUBLISHED", "BLOCKED", "DEPRECATED"})
            String releaseState,
            @Schema(allowableValues = {"CLEAR", "QUARANTINED", "REVOKED"})
            String safetyState,
            JsonNode attestation,
            @Schema(allowableValues = {"NOT_RUN", "PASS", "FAIL", "EXPIRED", "WAIVED"})
            String certificationStatus,
            UUID predecessorVersionId,
            UUID replacementVersionId,
            OffsetDateTime deprecationEndsAt,
            UUID validationRunId,
            String bindingCatalogRevision,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            List<String> allowedTransitions) {}

    public record VersionPage(
            List<VersionResponse> items,
            int page,
            int size,
            long totalElements,
            boolean hasNext,
            String readRevision) {}

    public record ValidationResponse(
            UUID validationRunId,
            UUID versionId,
            String manifestHash,
            @Schema(allowableValues = {"PASS", "FAIL"}) String status,
            String bindingCatalogRevision,
            List<ValidationError> errors,
            OffsetDateTime validatedAt) {}

    public record ValidationError(String code, String jsonPointer) {}

    public record EvidenceCreateRequest(
            @NotBlank @Pattern(regexp = "MANIFEST|SECURITY|PRIVACY|A11Y|PERFORMANCE|LOCALIZATION")
            String evidenceType,
            @NotBlank @Pattern(regexp = "PASS|FAIL") String decision,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String manifestHash,
            @NotBlank @Size(max = 256) String evidenceRef,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String evidenceSha256,
            OffsetDateTime expiresAt,
            @Size(max = 500) String reviewNote,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record EvidenceResponse(
            UUID evidenceId,
            UUID versionId,
            String evidenceType,
            @Schema(allowableValues = {"PASS", "FAIL", "EXPIRED", "WAIVED"}) String status,
            String manifestHash,
            String evidenceRef,
            String evidenceSha256,
            OffsetDateTime expiresAt,
            long decisionRevision,
            UUID waivedEvidenceId,
            String trackingTicketRef,
            String reviewedBy,
            OffsetDateTime createdAt) {}

    public record EvidencePage(
            List<EvidenceResponse> items,
            int page,
            int size,
            long totalElements,
            boolean hasNext,
            String readRevision) {}

    public record ImpactResponse(
            UUID definitionId,
            UUID versionId,
            String operation,
            long activeChannelCount,
            long tenantPolicyReferenceCount,
            long instanceReferenceCount,
            long affectedTenantCount,
            boolean operationAllowed,
            String impactRevision,
            OffsetDateTime calculatedAt) {}

    public record ChannelTransitionRequest(
            @NotNull UUID versionId,
            @NotNull UUID validationRunId,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String manifestHash,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedImpactRevision,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record ChannelRollbackRequest(
            @NotNull UUID restoreVersionId,
            @NotNull UUID expectedCurrentVersionId,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedImpactRevision,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record ReleaseChannelResponse(
            UUID definitionId,
            String channel,
            UUID currentVersionId,
            UUID previousVersionId,
            long version,
            OffsetDateTime updatedAt,
            List<String> allowedTransitions) {}

    public record TenantPolicyRevisionRequest(
            boolean enabled,
            @NotBlank @Pattern(regexp = "CHANNEL|PINNED") String selector,
            @Pattern(regexp = "STABLE|PREVIEW") String channel,
            UUID versionId,
            @NotNull @Size(min = 1, max = 8) List<@Pattern(regexp = "[a-z][a-z0-9-]{1,79}") String> supportedSurfaceKeys,
            @NotNull @Schema(implementation = AudienceSelectorV1.class) JsonNode audienceSelector,
            boolean required,
            @NotNull JsonNode lockedConfiguration,
            @NotBlank @Pattern(regexp = "PRIVATE|TENANT|DISABLED") String sharingPolicy,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    @Schema(requiredProperties = {"schemaVersion", "mode", "roleCodes", "groupRefs"})
    public record AudienceSelectorV1(
            @Min(1) @Max(1) int schemaVersion,
            @Pattern(regexp = "ALL_ENTITLED|ANY_OF|ALL_OF") String mode,
            List<@Pattern(regexp = "[A-Z][A-Z0-9_.-]{0,63}") String> roleCodes,
            List<@Size(min = 1, max = 128) String> groupRefs) {}

    public record TenantPolicyPublishRequest(
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedImpactRevision,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText) {}

    public record TenantPolicyRollbackRequest(
            @NotNull UUID restoreRevisionId,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedImpactRevision,
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText) {}

    public record TenantPolicyRevokeRequest(
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedImpactRevision,
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText) {}

    public record TenantPolicyRevisionResponse(
            UUID policyRevisionId,
            long tenantId,
            UUID definitionId,
            long revisionNumber,
            @Schema(allowableValues = {"DRAFT", "PUBLISHED", "SUPERSEDED", "REVOKED"})
            String policyState,
            boolean enabled,
            String selector,
            String channel,
            UUID versionId,
            JsonNode supportedSurfaceKeys,
            JsonNode audienceSelector,
            boolean required,
            JsonNode lockedConfiguration,
            String sharingPolicy,
            String impactRevision,
            UUID predecessorRevisionId,
            long version,
            OffsetDateTime createdAt) {}

    public record TenantPolicyResponse(
            UUID definitionId,
            UUID currentRevisionId,
            TenantPolicyRevisionResponse current,
            long version,
            List<String> allowedTransitions) {}

    public record TenantPolicyRevisionPage(
            List<TenantPolicyRevisionResponse> items,
            int page,
            int size,
            long totalElements,
            boolean hasNext,
            String readRevision) {}

    public record RuntimeDisableRequest(
            @NotBlank @Pattern(regexp = "CATALOG_MUTATIONS|CATALOG_DISCOVERY|RUNTIME_RENDER|RUNTIME_ACTION") String scope,
            @NotBlank @Pattern(regexp = "GLOBAL|PROVIDER|TENANT|DEFINITION|VERSION") String targetType,
            @Size(max = 160) String targetId,
            Long tenantId,
            @Size(max = 120) String providerProductKey,
            OffsetDateTime expiresAt,
            @NotBlank @Size(max = 64) String publicReasonCode,
            @NotBlank @Size(max = 128) String internalIncidentRef,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record RuntimeControlResponse(
            UUID controlId,
            Long tenantId,
            String providerProductKey,
            String scope,
            String targetType,
            String targetId,
            @Schema(allowableValues = {"DISABLED", "ENABLED", "EXPIRED"}) String state,
            long controlRevision,
            String reasonCode,
            OffsetDateTime expiresAt,
            long version,
            OffsetDateTime createdAt) {}

    public record RuntimeEnableApprovalRequest(
            @NotNull @Min(1) Long controlRevision,
            @NotNull @Size(min = 1, max = 20) List<@Size(max = 128) String> evidenceRefs,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record RuntimeEnableRequest(
            @NotNull UUID enableApprovalId,
            @NotNull @Min(1) Long controlRevision,
            @NotBlank @Size(max = 64) String reasonCode,
            @NotBlank @Size(max = 500) String reasonText,
            @NotNull @Min(0) Long expectedVersion) {}

    public record RuntimeEnableApprovalResponse(
            UUID approvalId,
            UUID controlId,
            long controlRevision,
            String state,
            JsonNode evidenceRefs,
            OffsetDateTime expiresAt,
            OffsetDateTime consumedAt,
            String approvedBy,
            OffsetDateTime createdAt) {}

    public record RuntimeControlPage(
            List<RuntimeControlResponse> items,
            int page,
            int size,
            long totalElements,
            boolean hasNext,
            String readRevision) {}

    public record RegistryEventResponse(
            UUID eventId,
            long registryRevision,
            Long tenantId,
            String aggregateType,
            String aggregateId,
            String eventType,
            UUID commandId,
            String actorRef,
            String correlationId,
            JsonNode before,
            JsonNode after,
            JsonNode evidenceRefs,
            OffsetDateTime occurredAt) {}

    public record RegistryEventPage(
            List<RegistryEventResponse> items,
            int page,
            int size,
            long totalElements,
            boolean hasNext,
            String readRevision) {}

    public record EffectiveCatalogResponse(
            int schemaVersion,
            String mode,
            String catalogRevision,
            String bindingCatalogRevision,
            String policyRevision,
            String safetyRevision,
            HostContext hostContext,
            List<PlacementContext> contexts) {}

    public record HostContext(
            String surfaceKey,
            String resolvedHostMode,
            long homeExperienceVersion,
            int compositionSchemaVersion,
            String layoutSource,
            String activeViewRef,
            long layoutRevision,
            String hostConfigurationRevision,
            int hostCapabilityVersion,
            String decisionRevision) {}

    public record PlacementContext(
            String placementContext,
            CatalogCapabilities capabilities,
            List<EffectiveItem> items) {}

    public record CatalogCapabilities(
            boolean libraryRead,
            boolean legacyPlacementWrite,
            boolean instanceV6Write,
            boolean brokerRead,
            boolean presetCreate,
            boolean presetShare) {}

    public record EffectiveItem(
            UUID definitionId,
            String definitionKey,
            String legacyWidgetKey,
            UUID resolvedVersionId,
            String semanticVersion,
            EffectiveCatalogState effectiveState,
            List<EffectiveCatalogReason> reasonCodes,
            PlacementCapabilities placementCapabilities,
            long addedInstanceCount) {}

    public record PlacementCapabilities(
            boolean canAdd,
            boolean canHide,
            boolean canMove,
            boolean canResize) {}

    public record Pagination(
            @Min(0) int page,
            @Min(1) @Max(100) int size) {}
}
