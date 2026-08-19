package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.BookingStatus;
import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;

public final class WorkplaceOperationsDtos {

    private WorkplaceOperationsDtos() {
    }

    @Schema(name = "WorkplaceRelocateBookingRequest")
    public record RelocateBookingRequest(
            @NotNull UUID resourceId,
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            @Size(max = 500) String reason,
            @NotNull @Min(0) Long version) {
    }

    @Schema(name = "WorkplaceForceCancelBookingRequest")
    public record ForceCancelBookingRequest(
            @NotBlank @Size(max = 500) String reason,
            @NotNull @Min(0) Long version) {
    }

    @Schema(name = "WorkplaceLegalHoldRequest")
    public record LegalHoldRequest(
            boolean legalHold,
            @NotBlank @Size(max = 500) String reason,
            @NotNull @Min(0) Long version) {
    }

    @Schema(name = "WorkplaceAdminBooking")
    public record AdminBooking(
            UUID bookingId,
            UUID resourceId,
            String resourceName,
            ResourceType resourceType,
            String siteName,
            String floorName,
            Long userId,
            UUID personPublicId,
            String bookedForDisplayName,
            String purpose,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            BookingStatus status,
            boolean visibleToColleagues,
            OffsetDateTime checkedInAt,
            OffsetDateTime releasedAt,
            OffsetDateTime cancelledAt,
            boolean legalHold,
            OffsetDateTime personalDataExpiresAt,
            OffsetDateTime anonymizedAt,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    @Schema(name = "WorkplaceAdminBookingPage")
    public record AdminBookingPage(
            List<AdminBooking> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    @Schema(name = "WorkplaceAuditEvent")
    public record AuditEvent(
            UUID auditEventId,
            String action,
            String aggregateType,
            UUID aggregateId,
            Long actorUserId,
            String correlationId,
            JsonNode snapshot,
            OffsetDateTime occurredAt) {
    }

    @Schema(name = "WorkplaceAuditEventPage")
    public record AuditEventPage(
            List<AuditEvent> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}
