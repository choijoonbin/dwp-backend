package com.dwp.services.platform.workplace;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

final class WorkplaceReleaseWindowDtos {

    private WorkplaceReleaseWindowDtos() {
    }

    record ReleaseWindow(
            UUID releaseWindowId,
            UUID resourceId,
            String resourceName,
            String siteName,
            String floorName,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String note,
            String status,
            boolean canCancel,
            long version) {
    }

    record AssignedResource(
            UUID resourceId,
            String resourceName,
            String resourceType,
            UUID siteId,
            String siteName,
            UUID floorId,
            String floorName,
            String timeZone) {
    }

    record CreateRequest(
            @NotNull UUID resourceId,
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            @Size(max = 240) String note) {
    }
}
