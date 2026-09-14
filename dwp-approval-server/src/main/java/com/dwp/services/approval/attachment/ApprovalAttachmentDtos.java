package com.dwp.services.approval.attachment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApprovalAttachmentDtos {
    private ApprovalAttachmentDtos() { }
    public interface Strict { @JsonAnySetter default void reject(String key, Object value) { throw new IllegalArgumentException("Unknown attachment field: "+key); } }
    public enum State { RESERVED, UPLOADING, STORAGE_RECONCILING, QUARANTINED, SCANNING, AVAILABLE, REJECTED, CANCELLED }
    @Schema(name="ApprovalAttachmentRules", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Rules(boolean allowUpload, boolean allowDownload, @Min(1) @Max(26214400) long maxFileBytes,
            @Min(1) @Max(10) int maxFiles, @Min(1) @Max(104857600) long maxRequestBytes,
            @Min(1) @Max(2) int maxConcurrentUploads, @NotEmpty @Size(max=5) List<String> allowedMediaTypes,
            @Min(60) @Max(900) int grantTtlSeconds, @Min(1) @Max(3650) int retentionDays) implements Strict { }
    @Schema(name="ApprovalAttachmentPolicy", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Policy(UUID policyId, String resourceSetKey, long version, Rules published, Rules pending, String providerReadiness,
            int publishedRevision, Integer pendingRevision, Long pendingMakerUserId, String publishedRulesSha256,
            String pendingRulesSha256, String downloadReadiness, boolean publishEligible, String publishReason) { }
    @Schema(name="ApprovalAttachmentInitializePolicy", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record InitializePolicy(@NotNull @AssertTrue Boolean expectedAbsent,
            @NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,120}") String idempotencyKey) implements Strict { }
    @Schema(name="ApprovalAttachmentSavePolicy", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record SavePolicy(@NotNull @Min(0) Long expectedVersion, @NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,120}") String idempotencyKey, @NotNull @Valid Rules rules) implements Strict { }
    @Schema(name="ApprovalAttachmentPublishPolicy", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record PublishPolicy(@NotNull @Min(0) Long expectedVersion, @NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,120}") String idempotencyKey, @NotBlank @Size(max=1000) String reviewComment) implements Strict { }
    @Schema(name="ApprovalAttachmentReserve", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Reserve(@NotNull @Min(0) Long expectedVersion, @Min(1) int expectedPayloadRevision, @Min(0) long expectedPolicyVersion,
            @NotBlank @Size(max=160) String fileName, @NotBlank String mediaType, @Min(1) @Max(26214400) long sizeBytes,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String sha256, @NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,120}") String idempotencyKey) implements Strict { }
    @Schema(name="ApprovalAttachmentUpload", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Upload(UUID uploadId, UUID attachmentId, State state, long version, String reason, String avState, String passiveContentState, long sizeBytes, String sha256, Instant expiresAt) { }
    @Schema(name="ApprovalAttachmentCancel", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Cancel(@NotNull @Min(0) Long expectedVersion, @NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,120}") String idempotencyKey) implements Strict { }
    @Schema(name="ApprovalAttachmentSelection", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Selection(@NotNull @Min(0) Long expectedVersion, @Min(1) int expectedPayloadRevision, @Min(0) long expectedSelectionVersion,
            @Min(0) long expectedPolicyVersion, @NotNull @Size(max=10) List<UUID> attachmentIds,
            @NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,120}") String idempotencyKey) implements Strict { }
    @Schema(name="ApprovalAttachmentManifest", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Manifest(int payloadRevision, String payloadSha256, String manifestSha256, List<Item> items, long selectionVersion, boolean sealed, String providerReadiness) { }
    @Schema(name="ApprovalAttachmentItem", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Item(UUID attachmentId, String fileName, String mediaType, long sizeBytes, String sha256, String avState, String passiveContentState) { }
    @Schema(name="ApprovalAttachmentDownload", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Download(@NotNull @Min(0) Long expectedVersion, @Min(1) int expectedPayloadRevision, @Min(0) long expectedPolicyVersion,
            @NotBlank @Size(max=500) String reason, @NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,120}") String idempotencyKey) implements Strict { }
    @Schema(name="ApprovalAttachmentGrant", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Grant(UUID grantId, Instant expiresAt, String sha256, long sizeBytes) { }
    @Schema(name="ApprovalAttachmentTool", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Tool(boolean allowed,String reason) { }
    @Schema(name="ApprovalAttachmentAttachments", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Attachments(Manifest manifest,UUID policyId,long policyVersion,Tool upload,Tool download,
            long maxFileBytes,int maxFiles,long maxRequestBytes,List<String> allowedMediaTypes,Instant evaluatedAt) { }
    @Schema(name="ApprovalAttachmentPrepared", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Prepared(UUID preparationId, UUID requestId, long sourceRequestVersion, int sourcePayloadRevision, String sourcePayloadSha256, String manifestSha256, long selectionVersion, UUID policyId, long policyVersion, Instant expiresAt) { }
}
