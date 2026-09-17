package com.dwp.services.platform.workplace;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class WorkplaceResourceFavoriteDtos {
    private WorkplaceResourceFavoriteDtos() { }

    public record FavoriteView(
            UUID resourceId,
            boolean favorite,
            long version,
            OffsetDateTime updatedAt) { }

    public record SetFavoriteRequest(
            boolean favorite,
            @Min(0) long expectedVersion) { }

    public record FavoriteReceipt(
            @NotNull UUID commandId,
            @NotNull FavoriteView favorite,
            @NotNull UUID auditEventId,
            String correlationId,
            OffsetDateTime completedAt) { }
}
