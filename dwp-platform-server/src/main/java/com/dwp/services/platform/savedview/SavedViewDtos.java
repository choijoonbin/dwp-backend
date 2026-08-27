package com.dwp.services.platform.savedview;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SavedViewDtos {
    private SavedViewDtos() { }

    public record SavedView(
            UUID savedViewId,
            String surfaceKey,
            String name,
            String scope,
            Long ownerUserId,
            UUID ownerGroupRef,
            String lifecycleState,
            OffsetDateTime retentionUntil,
            boolean editable,
            boolean favorite,
            boolean defaultView,
            Map<String, Object> configuration,
            long version,
            OffsetDateTime lastUsedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) { }

    public record CreateRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 16) String scope,
            UUID ownerGroupRef,
            @NotNull Map<String, Object> configuration,
            boolean favorite,
            boolean defaultView) { }

    public record UpdateRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 16) String scope,
            UUID ownerGroupRef,
            @NotNull Map<String, Object> configuration,
            @PositiveOrZero long version) { }

    public record PreferenceRequest(boolean favorite, boolean defaultView) { }

    public record OwnershipCandidate(
            UUID savedViewId,
            String surfaceKey,
            String name,
            String scope,
            UUID ownerGroupRef,
            long version,
            OffsetDateTime updatedAt) { }

    public record OwnershipNameConflict(
            UUID incomingSavedViewId,
            String incomingName,
            String surfaceKey,
            UUID existingTargetSavedViewId,
            String existingTargetName) { }

    /** Privacy-bounded, plan-aware successor evaluation for the custody picker. */
    public record CustodyCandidate(
            Long tenantId,
            Long userId,
            UUID publicId,
            UUID personPublicId,
            String displayName,
            String email,
            String jobTitle,
            String status,
            String identityPlane,
            String eligibilityStatus,
            List<String> ineligibilityReasons) { }

    public record OwnershipPlanRequest(
            @NotNull Long sourceOwnerUserId,
            @NotBlank @Size(max = 20) String disposition,
            Long targetOwnerUserId,
            @NotBlank @Size(max = 40) String reasonCode,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotBlank @Size(min = 3, max = 240) String sourceReference,
            OffsetDateTime retentionUntil) { }

    public record OwnershipPreview(
            Long sourceOwnerUserId,
            String disposition,
            Long targetOwnerUserId,
            OffsetDateTime retentionUntil,
            int affectedCount,
            String ownershipFingerprint,
            List<OwnershipCandidate> views,
            List<OwnershipNameConflict> nameConflicts,
            OffsetDateTime evaluatedAt) { }

    public record OwnershipTransferRequest(
            @NotBlank @Size(max = 120) String idempotencyKey,
            @NotNull Long sourceOwnerUserId,
            @NotBlank @Size(max = 20) String disposition,
            Long targetOwnerUserId,
            @NotBlank @Size(max = 40) String reasonCode,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotBlank @Size(min = 3, max = 240) String sourceReference,
            OffsetDateTime retentionUntil,
            @Positive int expectedCount,
            @NotBlank @Size(min = 64, max = 64) String ownershipFingerprint) { }

    public record OwnershipTransfer(
            UUID transferBatchId,
            String idempotencyKey,
            Long sourceOwnerUserId,
            String sourceOwnerDisplayName,
            Long targetOwnerUserId,
            String targetOwnerDisplayName,
            String disposition,
            String reasonCode,
            String reason,
            String sourceReference,
            OffsetDateTime retentionUntil,
            int transferredCount,
            String ownershipFingerprint,
            String requestFingerprint,
            OffsetDateTime createdAt,
            Long createdBy) { }

    public record OwnershipTransferSummary(
            UUID transferBatchId,
            Long sourceOwnerUserId,
            String sourceOwnerDisplayName,
            Long targetOwnerUserId,
            String targetOwnerDisplayName,
            String disposition,
            String reasonCode,
            String reason,
            String sourceReference,
            OffsetDateTime retentionUntil,
            int transferredCount,
            OffsetDateTime createdAt,
            Long createdBy) { }

    public record OrphanedView(
            UUID savedViewId,
            String surfaceKey,
            String name,
            String scope,
            UUID ownerGroupRef,
            OffsetDateTime retentionUntil,
            long version,
            OffsetDateTime updatedAt,
            String reassignmentBlockReason) { }

    public record OrphanReassignRequest(
            @NotBlank @Size(max = 120) String idempotencyKey,
            @NotNull @Positive Long targetOwnerUserId,
            @PositiveOrZero long version,
            @NotBlank @Size(max = 40) String reasonCode,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotBlank @Size(min = 3, max = 240) String sourceReference) { }

    public record OrphanRetentionRequest(
            @NotBlank @Size(max = 120) String idempotencyKey,
            @NotNull OffsetDateTime retentionUntil,
            @PositiveOrZero long version,
            @NotBlank @Size(max = 40) String reasonCode,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotBlank @Size(min = 3, max = 240) String sourceReference) { }

    public record OrphanArchiveRequest(
            @NotBlank @Size(max = 120) String idempotencyKey,
            @PositiveOrZero long version,
            @NotBlank @Size(max = 40) String reasonCode,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotBlank @Size(min = 3, max = 240) String sourceReference) { }

    public record OrphanLifecycleResult(
            UUID commandId,
            String idempotencyKey,
            UUID savedViewId,
            String savedViewName,
            String surfaceKey,
            String scope,
            String action,
            Long targetOwnerUserId,
            String targetOwnerDisplayName,
            String previousLifecycleState,
            String newLifecycleState,
            OffsetDateTime previousRetentionUntil,
            OffsetDateTime nextRetentionUntil,
            String reasonCode,
            String reason,
            String sourceReference,
            String requestFingerprint,
            long previousVersion,
            long resultingVersion,
            OffsetDateTime createdAt,
            Long createdBy) { }
}
