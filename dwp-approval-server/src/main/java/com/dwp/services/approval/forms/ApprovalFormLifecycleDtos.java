package com.dwp.services.approval.forms;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApprovalFormLifecycleDtos {
    private ApprovalFormLifecycleDtos() { }
    public enum CatalogAvailability { ACTIVE, RETIRED }
    @Schema(name="ApprovalFormLifecycleMetadataInput", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record MetadataInput(@NotNull UUID categoryId, @NotBlank @Size(max=200) String nameKo,
            @NotBlank @Size(max=200) String nameEn, @NotBlank @Size(max=1000) String descriptionKo,
            @NotBlank @Size(max=1000) String descriptionEn, @NotBlank @Size(max=160) String ownerGroupRef,
            @NotBlank @Pattern(regexp="REQUEST|DOCUMENT|SIGNATURE") String formKind) { }
    @Schema(name="ApprovalFormLifecycleBranch", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Branch(@JsonDeserialize(using=ApprovalFormExactRevision.class) @NotNull @Min(0) @Max(9007199254740991L) Long expectedFormRevision,
            @JsonDeserialize(using=ApprovalFormExactRevision.class) @Min(0) @Max(9007199254740991L) Long expectedWorkspaceRevision) { }
    @Schema(name="ApprovalFormLifecycleUpdateWorkingDraft", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record UpdateWorkingDraft(@NotNull UUID draftFormVersionId,
            @JsonDeserialize(using=ApprovalFormExactRevision.class) @NotNull @Min(0) @Max(9007199254740991L) Long expectedFormRevision,
            @JsonDeserialize(using=ApprovalFormExactRevision.class) @Min(0) @Max(9007199254740991L) Long expectedWorkspaceRevision,
            @NotNull Map<String,Object> schema, @NotNull @Valid MetadataInput metadata,
            @NotNull UUID defaultWorkflowId) { }
    @Schema(name="ApprovalFormLifecycleAvailabilityChange", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record AvailabilityChange(@JsonDeserialize(using=ApprovalFormExactRevision.class) @NotNull @Min(0) @Max(9007199254740991L) Long expectedFormRevision,
            @JsonDeserialize(using=ApprovalFormExactRevision.class) @Min(0) @Max(9007199254740991L) Long expectedWorkspaceRevision) { }
    @Schema(name="ApprovalFormLifecyclePublishReviewed", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record PublishReviewed(@NotNull UUID draftFormVersionId, UUID basePublishedVersionId,
            @JsonDeserialize(using=ApprovalFormExactRevision.class) @NotNull @Min(0) @Max(9007199254740991L) Long expectedFormRevision,
            @JsonDeserialize(using=ApprovalFormExactRevision.class) @NotNull @Min(0) @Max(9007199254740991L) Long expectedWorkspaceRevision,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String schemaSha256,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String reviewContentDigest) { }
    @Schema(name="ApprovalFormLifecycleVersion", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Version(UUID formVersionId, int versionNumber, String lifecycleState,
            UUID sourceVersionId, UUID basePublishedVersionId, Map<String,Object> schema,
            String schemaSha256, Map<String,Object> metadata, Map<String,Object> route,
            String materialDigest, String metadataProvenance, OffsetDateTime capturedAt,
            Long capturedBy, OffsetDateTime createdAt, Long createdBy,
            OffsetDateTime publishedAt, Long publishedBy) { }
    @Schema(name="ApprovalFormLifecycleWorkspace", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Workspace(UUID formId, long formRevision, Long workspaceRevision,
            CatalogAvailability catalogAvailability, Version published, Version workingDraft,
            Long lastEditorUserId, boolean catalogPolicyEligible, OffsetDateTime observedAt) { }
    @Schema(name="ApprovalFormLifecycleReview", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Review(UUID formId, long formRevision, long workspaceRevision,
            UUID draftFormVersionId, UUID basePublishedVersionId, String schemaSha256,
            String reviewContentDigest, Long makerUserId, Long lastEditorUserId,
            boolean independentCheckerEligible, OffsetDateTime authorityValidUntil) { }
    @Schema(name="ApprovalFormLifecycleChange", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Change(String path, Object before, Object after) { }
    @Schema(name="ApprovalFormLifecycleDiff", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Diff(UUID fromVersionId, UUID toVersionId, String fromSchemaSha256,
            String toSchemaSha256, List<Change> changes, boolean complete,
            String fromMetadataProvenance, String toMetadataProvenance) { }
    @Schema(name="ApprovalFormLifecycleHistory", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record History(List<Version> versions, boolean mayBeTruncated) { }
}
