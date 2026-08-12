package com.dwp.services.platform.savedview;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
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
            @NotNull Map<String, Object> configuration,
            boolean favorite,
            boolean defaultView) { }

    public record UpdateRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 16) String scope,
            @NotNull Map<String, Object> configuration,
            @PositiveOrZero long version) { }

    public record PreferenceRequest(boolean favorite, boolean defaultView) { }
}
