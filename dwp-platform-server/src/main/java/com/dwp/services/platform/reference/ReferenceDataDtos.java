package com.dwp.services.platform.reference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class ReferenceDataDtos {

    private ReferenceDataDtos() {
    }

    public record CreateSetRequest(
            @NotBlank
            @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{1,79}")
            String setKey,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String description) {
    }

    public record UpdateSetRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String description,
            @NotNull @Min(0) Long version) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record LocalizedLabelRequest(
            @NotBlank @Size(max = 20) String locale,
            @NotBlank @Size(max = 200) String label,
            @Size(max = 1000) String description) {
    }

    public record CreateItemRequest(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")
            String code,
            @NotNull @Min(-1_000_000) @Max(1_000_000) Integer sortOrder,
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,79}") String parentCode,
            Instant validFrom,
            Instant validTo,
            @NotEmpty @Size(max = 20) List<@Valid LocalizedLabelRequest> labels) {
    }

    public record UpdateItemRequest(
            @NotNull @Min(-1_000_000) @Max(1_000_000) Integer sortOrder,
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,79}") String parentCode,
            Instant validFrom,
            Instant validTo,
            @NotEmpty @Size(max = 20) List<@Valid LocalizedLabelRequest> labels,
            @NotNull @Min(0) Long version) {
    }

    public record ReferenceSetSummary(
            String setKey,
            String name,
            String description,
            ReferenceLifecycle lifecycleState,
            long itemCount,
            Long revision,
            Long version,
            LocalDateTime updatedAt,
            Long updatedBy) {
    }

    public record ReferenceLabelResponse(
            String locale,
            String label,
            String description) {
    }

    public record ReferenceItemResponse(
            String code,
            ReferenceLifecycle lifecycleState,
            Integer sortOrder,
            String parentCode,
            Instant validFrom,
            Instant validTo,
            List<ReferenceLabelResponse> labels,
            Long version,
            LocalDateTime updatedAt,
            Long updatedBy) {
    }

    public record ReferenceSetDetail(
            String setKey,
            String name,
            String description,
            ReferenceLifecycle lifecycleState,
            Long revision,
            Long version,
            LocalDateTime updatedAt,
            Long updatedBy,
            List<ReferenceItemResponse> items) {
    }

    public record RuntimeReferenceItem(
            String code,
            String label,
            String description,
            Integer sortOrder,
            String parentCode) {
    }

    public record RuntimeReferenceSet(
            String setKey,
            String locale,
            Long revision,
            List<RuntimeReferenceItem> items) {
    }

    public record PageResult<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    static Map<String, Object> setSnapshot(ReferenceSet set) {
        return Map.of(
                "setKey", set.getSetKey(),
                "name", set.getName(),
                "lifecycleState", set.getLifecycleState().name(),
                "revision", set.getContentRevision(),
                "version", set.getVersion() == null ? 0L : set.getVersion());
    }

    static Map<String, Object> itemSnapshot(ReferenceItem item) {
        return Map.of(
                "code", item.getCode(),
                "lifecycleState", item.getLifecycleState().name(),
                "sortOrder", item.getSortOrder(),
                "version", item.getVersion() == null ? 0L : item.getVersion());
    }
}
