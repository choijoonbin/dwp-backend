package com.dwp.services.platform.workplace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.*;

public final class WorkplaceDtos {

    private WorkplaceDtos() {
    }

    @Schema(name = "WorkplaceSite")
    public record Site(
            UUID siteId,
            String code,
            String name,
            String nameKo,
            String nameEn,
            SiteType type,
            String address,
            String timeZone,
            int totalFloorCount,
            long configuredFloorCount,
            long resourceCount,
            SiteState state,
            long version) {
    }

    @Schema(name = "WorkplaceFloor")
    public record Floor(
            UUID floorId,
            UUID siteId,
            String siteName,
            int floorNumber,
            String name,
            String nameKo,
            String nameEn,
            int planWidth,
            int planHeight,
            String backgroundAssetPath,
            FloorState state,
            long resourceCount,
            long version) {
    }

    @Schema(name = "WorkplaceResource")
    public record Resource(
            UUID resourceId,
            UUID floorId,
            UUID siteId,
            UUID calendarResourceId,
            String code,
            String name,
            String nameKo,
            String nameEn,
            ResourceType type,
            BookingMode mode,
            ResourceState state,
            String neighborhood,
            int capacity,
            List<String> features,
            boolean accessible,
            boolean approvalRequired,
            BigDecimal positionX,
            BigDecimal positionY,
            BigDecimal widthPercent,
            BigDecimal heightPercent,
            int rotationDegrees,
            Long assignedUserId,
            UUID assignedPersonPublicId,
            String assignedDisplayName,
            long version) {
    }

    @Schema(name = "WorkplaceOccupancy")
    public record Occupancy(
            UUID resourceId,
            UUID bookingId,
            BookingStatus status,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String bookedByDisplayName,
            boolean currentUser) {
    }

    @Schema(name = "WorkplacePolicy")
    public record Policy(
            int bookingWindowDays,
            int maximumActiveBookings,
            int minimumBookingMinutes,
            int maximumBookingMinutes,
            int maximumConsecutiveDays,
            LocalTime workingDayStart,
            LocalTime workingDayEnd,
            boolean allowRecurring,
            boolean requireCheckIn,
            int checkInLeadMinutes,
            int autoReleaseMinutes,
            boolean allowAssignedDeskLending,
            boolean showColleagueNames,
            long version) {
    }

    @Schema(name = "WorkplaceExploreResponse")
    public record ExploreResponse(
            List<Site> sites,
            List<Floor> floors,
            Floor selectedFloor,
            List<Resource> resources,
            List<Occupancy> occupancy,
            Policy policy,
            OffsetDateTime generatedAt) {
    }

    @Schema(name = "WorkplaceBooking")
    public record Booking(
            UUID bookingId,
            UUID resourceId,
            String resourceName,
            ResourceType resourceType,
            String siteName,
            String floorName,
            String purpose,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            BookingStatus status,
            boolean visibleToColleagues,
            OffsetDateTime checkedInAt,
            OffsetDateTime releasedAt,
            long version) {
    }

    @Schema(name = "WorkplaceAdminOverview")
    public record AdminOverview(
            long activeSites,
            long configuredFloors,
            long reservableResources,
            long assignedResources,
            long bookingsThisWeek,
            long checkedInToday,
            int utilizationPercent,
            Policy policy,
            OffsetDateTime generatedAt) {
    }

    public record BookingRequest(
            @NotNull UUID resourceId,
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            @Size(max = 500) String purpose,
            boolean visibleToColleagues) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record SiteRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,79}") String code,
            @NotBlank @Size(max = 160) String nameKo,
            @NotBlank @Size(max = 160) String nameEn,
            @NotNull SiteType type,
            @Size(max = 500) String address,
            @NotBlank @Size(max = 80) String timeZone,
            @Min(1) @Max(300) int totalFloorCount,
            @NotNull SiteState state,
            Long version) {
    }

    public record FloorRequest(
            @Min(-20) @Max(300) int floorNumber,
            @NotBlank @Size(max = 160) String nameKo,
            @NotBlank @Size(max = 160) String nameEn,
            @Min(400) @Max(5000) int planWidth,
            @Min(300) @Max(5000) int planHeight,
            @NotNull FloorState state,
            Long version) {
    }

    public record ResourceRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,79}") String code,
            @NotBlank @Size(max = 160) String nameKo,
            @NotBlank @Size(max = 160) String nameEn,
            @NotNull ResourceType type,
            @NotNull BookingMode mode,
            @NotNull ResourceState state,
            @Size(max = 120) String neighborhood,
            @Min(1) @Max(10000) int capacity,
            @NotNull @Size(max = 50) List<@NotBlank @Size(max = 60) String> features,
            boolean accessible,
            boolean approvalRequired,
            @NotNull @DecimalMin("0") @DecimalMax("99.99") BigDecimal positionX,
            @NotNull @DecimalMin("0") @DecimalMax("99.99") BigDecimal positionY,
            @NotNull @DecimalMin("1") @DecimalMax("100") BigDecimal widthPercent,
            @NotNull @DecimalMin("1") @DecimalMax("100") BigDecimal heightPercent,
            @Min(-359) @Max(359) int rotationDegrees,
            Long assignedUserId,
            UUID assignedPersonPublicId,
            @Size(max = 160) String assignedDisplayName,
            Long version) {
    }

    public record ResourcePlacement(
            @NotNull UUID resourceId,
            @NotNull @DecimalMin("0") @DecimalMax("99.99") BigDecimal positionX,
            @NotNull @DecimalMin("0") @DecimalMax("99.99") BigDecimal positionY,
            @NotNull @DecimalMin("1") @DecimalMax("100") BigDecimal widthPercent,
            @NotNull @DecimalMin("1") @DecimalMax("100") BigDecimal heightPercent,
            @Min(-359) @Max(359) int rotationDegrees,
            @NotNull @Min(0) Long version) {
    }

    public record LayoutRequest(
            @NotNull @Size(min = 1, max = 500) List<@Valid ResourcePlacement> resources) {
    }

    public record PolicyRequest(
            @Min(1) @Max(365) int bookingWindowDays,
            @Min(1) @Max(100) int maximumActiveBookings,
            @Min(15) @Max(1440) int minimumBookingMinutes,
            @Min(15) @Max(10080) int maximumBookingMinutes,
            @Min(1) @Max(31) int maximumConsecutiveDays,
            @NotNull LocalTime workingDayStart,
            @NotNull LocalTime workingDayEnd,
            boolean allowRecurring,
            boolean requireCheckIn,
            @Min(0) @Max(240) int checkInLeadMinutes,
            @Min(0) @Max(240) int autoReleaseMinutes,
            boolean allowAssignedDeskLending,
            boolean showColleagueNames,
            @NotNull @Min(0) Long version) {
    }
}
