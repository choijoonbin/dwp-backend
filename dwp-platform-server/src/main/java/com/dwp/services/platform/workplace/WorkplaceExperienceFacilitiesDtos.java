package com.dwp.services.platform.workplace;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WorkplaceExperienceFacilitiesDtos {
    private WorkplaceExperienceFacilitiesDtos() { }

    public enum ClosureStatus { ACTIVE, CANCELLED }
    public enum Category { REPAIR, CLEANING, ACCESS, OTHER }
    public enum RequestStatus { OPEN, IN_PROGRESS, RESOLVED, CANCELLED }

    public record CreateClosure(@NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt,
                                @NotNull @Min(0) Long version,
                                @NotBlank @Size(max = 500) String reason, boolean confirmed) { }
    public record CancelClosure(@NotNull @Min(0) Long version,
                                @NotBlank @Size(max = 500) String reason, boolean confirmed) { }
    public record Closure(UUID closureId, UUID resourceId, UUID siteId, UUID floorId,
                          String resourceName, String timeZone, OffsetDateTime startsAt,
                          OffsetDateTime endsAt, ClosureStatus status, String reason,
                          String cancellationReason, long resourceVersionAtCreate,
                          long version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                          String affectedBookingsPath) { }
    public record PublicClosure(UUID resourceId, OffsetDateTime startsAt,
                                OffsetDateTime endsAt, String availability) { }
    public record ClosurePage(List<Closure> content, int page, int size, long totalElements,
                              int totalPages, OffsetDateTime generatedAt, String countsScope,
                              List<UUID> allowedFloorIds) {
        public ClosurePage(List<Closure> content, int page, int size, long totalElements,
                           int totalPages, OffsetDateTime generatedAt) {
            this(content, page, size, totalElements, totalPages, generatedAt, null, null);
        }
    }

    public record CreateRequest(@NotNull Category category,
                                @NotBlank @Size(max = 2000) String description) { }
    public record ChangeRequestStatus(@NotNull RequestStatus status,
                                      @NotNull @Min(0) Long version,
                                      @NotBlank @Size(max = 500) String reason, boolean confirmed) { }
    public record FacilityRequest(UUID requestId, UUID resourceId, UUID siteId, UUID floorId,
                                  String resourceName, Category category, String description,
                                  RequestStatus status, String statusReason, long version,
                                  OffsetDateTime createdAt, OffsetDateTime updatedAt,
                                  String owner) { }
    public record RequestPage(List<FacilityRequest> content, int page, int size, long totalElements,
                              int totalPages, OffsetDateTime generatedAt, String countsScope,
                              List<UUID> allowedFloorIds) {
        public RequestPage(List<FacilityRequest> content, int page, int size, long totalElements,
                           int totalPages, OffsetDateTime generatedAt) {
            this(content, page, size, totalElements, totalPages, generatedAt, null, null);
        }
    }
    public record BookingAvailability(UUID resourceId, UUID siteId, OffsetDateTime startsAt,
                                      OffsetDateTime endsAt, boolean available, String reason,
                                      List<PublicClosure> closures, OffsetDateTime generatedAt,
                                      String owner, boolean guaranteesBooking) { }
    public record RoomAffectedBooking(UUID bookingId, UUID eventId, UUID calendarResourceId,
                                      OffsetDateTime startsAt, OffsetDateTime endsAt, String status,
                                      long version, String owner) { }
    public record RoomBookingImpact(UUID resourceId, UUID calendarResourceId, UUID siteId,
                                    OffsetDateTime startsAt, OffsetDateTime endsAt,
                                    List<RoomAffectedBooking> content, int page, int size,
                                    long totalElements, int totalPages, OffsetDateTime generatedAt,
                                    String source, String availability, String owner,
                                    boolean existingBookingsMutated) { }
}
