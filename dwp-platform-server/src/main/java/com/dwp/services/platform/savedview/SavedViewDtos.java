package com.dwp.services.platform.savedview;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    public record OwnershipPlanRequest(
            @NotNull Long sourceOwnerUserId,
            @NotBlank @Size(max = 20) String disposition,
            Long targetOwnerUserId,
            @NotBlank @Size(max = 40) String reasonCode,
            @NotBlank @Size(max = 1000) String reason,
            @NotBlank @Size(max = 240) String sourceReference,
            OffsetDateTime retentionUntil) { }

    public record OwnershipPreview(
            Long sourceOwnerUserId,
            String disposition,
            Long targetOwnerUserId,
            OffsetDateTime retentionUntil,
            int affectedCount,
            String ownershipFingerprint,
            List<OwnershipCandidate> views,
            OffsetDateTime evaluatedAt) { }

    public record OwnershipTransferRequest(
            @NotBlank @Size(max = 120) String idempotencyKey,
            @NotNull Long sourceOwnerUserId,
            @NotBlank @Size(max = 20) String disposition,
            Long targetOwnerUserId,
            @NotBlank @Size(max = 40) String reasonCode,
            @NotBlank @Size(max = 1000) String reason,
            @NotBlank @Size(max = 240) String sourceReference,
            OffsetDateTime retentionUntil,
            @PositiveOrZero int expectedCount,
            @NotBlank @Size(min = 64, max = 64) String ownershipFingerprint) { }

    public record OwnershipTransfer(
            UUID transferBatchId,
            String idempotencyKey,
            Long sourceOwnerUserId,
            Long targetOwnerUserId,
            String disposition,
            String reasonCode,
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
            Long targetOwnerUserId,
            String disposition,
            String reasonCode,
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
            OffsetDateTime updatedAt) { }
}
