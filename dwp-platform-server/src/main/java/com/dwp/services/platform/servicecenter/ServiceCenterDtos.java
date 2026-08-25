package com.dwp.services.platform.servicecenter;

import com.fasterxml.jackson.databind.JsonNode;
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

import static com.dwp.services.platform.servicecenter.ServiceCenterTypes.*;

public final class ServiceCenterDtos {

    private ServiceCenterDtos() {
    }

    public record Category(
            String categoryKey,
            String name,
            String description,
            String iconKey,
            String tone,
            int sortOrder) {
    }

    public record CatalogItem(
            String serviceKey,
            String categoryKey,
            String name,
            String description,
            String ownerGroup,
            CatalogLifecycle lifecycleState,
            JsonNode requestSchema,
            int schemaVersion,
            int slaHours,
            int estimatedResolutionHours,
            DataClassification dataClassification,
            boolean featured,
            List<String> tags,
            long version) {
    }

    public record AdminCatalogItem(
            String serviceKey,
            String categoryKey,
            String nameKo,
            String nameEn,
            String descriptionKo,
            String descriptionEn,
            String ownerGroup,
            CatalogLifecycle lifecycleState,
            JsonNode requestSchema,
            int schemaVersion,
            int slaHours,
            int estimatedResolutionHours,
            DataClassification dataClassification,
            boolean featured,
            List<String> tags,
            long version) {
    }

    public record CatalogResponse(
            List<Category> categories,
            List<CatalogItem> items,
            long activeCount,
            OffsetDateTime generatedAt) {
    }

    public record CreateRequest(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9.-]{2,79}") String serviceKey,
            @NotBlank @Size(max = 240) String summary,
            @NotNull @Size(max = 50) Map<String, Object> values,
            @NotNull UUID idempotencyKey,
            boolean submit) {
    }

    public record UpdateDraftRequest(
            @NotBlank @Size(max = 240) String summary,
            @NotNull @Size(max = 50) Map<String, Object> values,
            @NotNull @Min(0) Long version,
            boolean submit) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record RequestSummary(
            UUID requestId,
            String requestNumber,
            String serviceKey,
            String serviceNameKo,
            String serviceNameEn,
            String summary,
            DataClassification dataClassification,
            RequestStatus status,
            RequestPriority priority,
            String assignedGroup,
            String assignedTo,
            OffsetDateTime submittedAt,
            OffsetDateTime slaDueAt,
            OffsetDateTime updatedAt,
            long version) {
    }

    public record TimelineEvent(
            UUID eventId,
            String eventType,
            RequestStatus status,
            String actorType,
            Long actorId,
            String note,
            OffsetDateTime occurredAt) {
    }

    public record RequestDetail(
            RequestSummary request,
            Map<String, Object> values,
            JsonNode requestSchema,
            int schemaVersion,
            DataClassification dataClassification,
            List<TimelineEvent> timeline) {
    }

    public record CatalogDefinitionRequest(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9.-]{2,79}") String serviceKey,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,49}") String categoryKey,
            @NotBlank @Size(max = 160) String nameKo,
            @NotBlank @Size(max = 160) String nameEn,
            @NotBlank @Size(max = 1000) String descriptionKo,
            @NotBlank @Size(max = 1000) String descriptionEn,
            @NotBlank @Size(max = 160) String ownerGroup,
            @NotNull CatalogLifecycle lifecycleState,
            @NotNull JsonNode requestSchema,
            @Min(1) @Max(8760) int slaHours,
            @Min(1) @Max(8760) int estimatedResolutionHours,
            @NotNull DataClassification dataClassification,
            boolean featured,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 60) String> tags,
            Long version) {
    }

    public record TransitionRequest(
            @NotNull RequestStatus targetStatus,
            @Size(max = 2000) String note,
            @Size(max = 160) String assignedTo,
            @NotNull @Min(0) Long version) {
    }
}
