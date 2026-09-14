package com.dwp.services.platform.workplace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WorkplaceExperienceReportDtos {
    private WorkplaceExperienceReportDtos() { }

    public enum Availability { AVAILABLE, EMPTY, UNAVAILABLE }

    @Schema(name = "WorkplaceExperienceScope")
    public record Scope(UUID siteId, UUID floorId, String siteName, String timeZone,
                        LocalDate from, LocalDate to, OffsetDateTime startsAt,
                        OffsetDateTime endsAt, String countsScope, List<UUID> allowedFloorIds) {
        public Scope(UUID siteId, UUID floorId, String siteName, String timeZone, LocalDate from,
                     LocalDate to, OffsetDateTime startsAt, OffsetDateTime endsAt) {
            this(siteId, floorId, siteName, timeZone, from, to, startsAt, endsAt, "SITE", null);
        }
    }

    public record Metadata(OffsetDateTime generatedAt, OffsetDateTime sourceUpdatedAt,
                           Availability availability, String owner, String denominatorBasis,
                           boolean historicalRosterAvailable, boolean recurringOccurrencesIncluded) { }

    public record ExternalSource(String kind, Availability availability, String reason,
                                 String owner, String integrationPath, String configurationStatus,
                                 String provider, OffsetDateTime lastVerifiedAt) { }

    public record CurrentCounts(long activeSites, long configuredFloors,
                                long reservableResources, long assignedResources,
                                long bookingsThisWeek, long checkedInToday,
                                Double utilizationPercent, WorkplaceDtos.Policy policy) { }

    public record Summary(long bookingCount, long cancelledCount, double bookedMinutes,
                          double denominatorResourceMinutes, Double utilizationPercent,
                          long noShowEligibleCount, long noShowCount, Double noShowPercent,
                          Double peakUtilizationPercent, long unresolvedPastBookings) { }

    public record FloorSummary(UUID floorId, String floorName, long resourceCount,
                               Summary summary) { }

    public record HeatmapCell(LocalDate date, int dayOfWeek, int hour, String offset,
                              OffsetDateTime startsAt, OffsetDateTime endsAt,
                              double bookedMinutes, double denominatorResourceMinutes,
                              Double utilizationPercent) { }

    public record DailyTrend(LocalDate date, long bookingCount, double bookedMinutes,
                             double denominatorResourceMinutes, Double utilizationPercent,
                             long noShowCount, Double noShowPercent) { }

    public record Comparison(LocalDate previousFrom, LocalDate previousTo,
                             Summary previous, Double utilizationChangePercentagePoints,
                             Double noShowChangePercentagePoints) { }

    @Schema(name = "WorkplaceExperienceBookingDetail")
    public record BookingDetail(UUID bookingId, UUID resourceId, UUID siteId, UUID floorId,
                                String resourceName, String resourceType, String floorName,
                                String status, OffsetDateTime startsAt, OffsetDateTime endsAt,
                                OffsetDateTime checkedInAt, OffsetDateTime releasedAt,
                                boolean legalHold, long version, OffsetDateTime updatedAt,
                                String detailHref, List<String> exceptionReasons) { }

    public record BookingPage(List<BookingDetail> content, int page, int size,
                              long totalElements, int totalPages) { }

    @Schema(name = "WorkplaceExperienceReport")
    public record Report(Scope scope, Metadata metadata, CurrentCounts current,
                         Summary summary, List<FloorSummary> floors,
                         List<HeatmapCell> hourlyHeatmap, List<DailyTrend> dailyTrend,
                         Comparison comparison, BookingPage exceptions,
                         List<ExternalSource> externalSources, List<String> definitions) { }

    public record FutureBookingImpact(UUID resourceId, UUID siteId, String resourceName,
                                      String owner, String resourceState,
                                      OffsetDateTime from, OffsetDateTime to,
                                      Metadata metadata, BookingPage affectedBookings,
                                      boolean mutatesBookings, boolean notificationScheduled,
                                      boolean replacementScheduled) { }

    public record PolicyChanges(Boolean requireCheckIn,
                                @Min(0) @Max(240) Integer autoReleaseMinutes,
                                @Min(15) @Max(1440) Integer minimumBookingMinutes,
                                @Min(15) @Max(10080) Integer maximumBookingMinutes,
                                LocalTime workingDayStart, LocalTime workingDayEnd) { }

    public record PolicyEffect(BookingDetail booking, List<String> knownEffects) { }

    public record PolicyDailyImpact(LocalDate date, String timeZone,
                                    long reviewedBookings, long affectedBookings) { }

    public record PolicyImpact(UUID siteId, UUID floorId, OffsetDateTime from,
                               OffsetDateTime to, Metadata metadata, PolicyChanges proposed,
                               long reviewedBookings, long affectedBookings,
                               List<PolicyEffect> content, int page, int size, int totalPages,
                               boolean mutatesExistingBookings, List<String> limitations,
                               List<PolicyDailyImpact> dailyImpact, String countsScope,
                               List<UUID> allowedFloorIds) { }
}
