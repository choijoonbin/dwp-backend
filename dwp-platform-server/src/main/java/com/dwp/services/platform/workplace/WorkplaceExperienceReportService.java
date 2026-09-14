package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceExperienceReportDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceExperienceReportRepository.*;
import static com.dwp.services.platform.workplace.WorkplaceExperienceReportMetrics.change;
import static com.dwp.services.platform.workplace.WorkplaceExperienceReportSupport.detail;
import static com.dwp.services.platform.workplace.WorkplaceExperienceReportSupport.invalid;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class WorkplaceExperienceReportService {
    static final List<String> DEFINITIONS = List.of(
            "WORKPLACE non-ROOM bookings only; Rooms/Calendar remain a separate owner.",
            "from is inclusive and to is exclusive in the site's canonical time zone.",
            "Denominator = current ACTIVE-site/ACTIVE-floor AVAILABLE RESERVABLE non-ROOM resources × elapsed minutes; not historical capacity or policy-approved operating minutes.",
            "Booked minutes = per-resource union of RESERVED/CHECKED_IN/COMPLETED/RELEASED intervals, clipped at release and range boundaries. CANCELLED and NO_SHOW intervals are excluded.",
            "Booking count includes non-cancelled records intersecting the range, including recorded NO_SHOW; no recurring occurrence synthesis.",
            "No-show cohort starts within the range and required check-in in the booking policy snapshot: recorded NO_SHOW / settled COMPLETED, RELEASED, NO_SHOW records whose scheduled end is no later than generatedAt. Unsettled past records make the rate unknown.",
            "Peak = highest booked-resource-minute ratio in an elapsed-hour bucket; neither people count nor sensor occupancy.",
            "Empty reservation cohorts return null ratios, not a measured sensor zero. Query failure never becomes zero.",
            "Comparison uses the preceding equal number of local dates with today's roster; historical roster and retention completeness are not guaranteed.");
    private final WorkplaceExperienceReportRepository repository;
    private final WorkplaceCatalogRepository catalog;
    private final WorkplaceExperienceReportPolicyPreview policyPreview;
    private final WorkplaceExperienceCollaborationRepository collaboration;

    WorkplaceExperienceReportService(WorkplaceExperienceReportRepository repository,
                                    WorkplaceCatalogRepository catalog,
                                    WorkplaceExperienceReportPolicyPreview policyPreview,
                                    WorkplaceExperienceCollaborationRepository collaboration) {
        this.repository = repository;
        this.catalog = catalog;
        this.policyPreview = policyPreview;
        this.collaboration = collaboration;
    }

    Report report(Long tenantId, UUID siteId, UUID floorId, LocalDate from, LocalDate to,
                  int page, int size) {
        return report(tenantId, siteId, floorId, from, to, page, size, null);
    }

    Report report(Long tenantId, UUID siteId, UUID floorId, LocalDate from, LocalDate to,
                  int page, int size, Set<UUID> allowedFloors) {
        return report(tenantId, siteId, floorId, from, to, page, size, allowedFloors, true);
    }

    Report report(Long tenantId, UUID siteId, UUID floorId, LocalDate from, LocalDate to,
                  int page, int size, Set<UUID> allowedFloors, boolean globalMetadata) {
        validateDates(from, to);
        validatePage(page, size);
        SiteRow site = requireSite(tenantId, siteId);
        ZoneId zone = ZoneId.of(site.timeZone());
        List<FloorRow> floors = requireFloors(tenantId, siteId, floorId, allowedFloors);
        List<ResourceRow> resources = repository.resources(tenantId, siteId, floorId, allowedFloors);
        OffsetDateTime generatedAt = repository.generatedAt();
        LocalDate today = generatedAt.atZoneSameInstant(zone).toLocalDate();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate previousFrom = from.minusDays(ChronoUnit.DAYS.between(from, to));
        LocalDate queryFrom = previousFrom.isBefore(weekStart) ? previousFrom : weekStart;
        LocalDate queryTo = to.isAfter(weekStart.plusDays(7)) ? to : weekStart.plusDays(7);
        List<BookingRow> bookings = boundedBookings(tenantId, siteId, floorId,
                at(queryFrom, zone), at(queryTo, zone), allowedFloors);
        OffsetDateTime startsAt = at(from, zone);
        OffsetDateTime endsAt = at(to, zone);
        WorkplaceExperienceReportMetrics metrics = new WorkplaceExperienceReportMetrics(resources, bookings, generatedAt);
        Summary summary = metrics.summary(startsAt, endsAt);
        Summary previous = metrics.summary(at(previousFrom, zone), startsAt);
        List<FloorSummary> byFloor = floors.stream().map(floor -> {
            List<ResourceRow> floorResources = resources.stream()
                    .filter(r -> r.floorId().equals(floor.floorId())).toList();
            List<BookingRow> floorBookings = bookings.stream()
                    .filter(b -> b.floorId().equals(floor.floorId())).toList();
            return new FloorSummary(floor.floorId(), floor.name(),
                    floorResources.stream().filter(ResourceRow::inDenominator).count(),
                    new WorkplaceExperienceReportMetrics(floorResources, floorBookings, generatedAt)
                            .summary(startsAt, endsAt));
        }).toList();
        long thisWeek = bookings.stream().filter(b -> !"CANCELLED".equals(b.status()))
                .filter(b -> intersects(b, at(weekStart, zone), at(weekStart.plusDays(7), zone))).count();
        long checkedInToday = bookings.stream().filter(b -> b.checkedInAt() != null)
                .filter(b -> !b.checkedInAt().isBefore(at(today, zone))
                        && b.checkedInAt().isBefore(at(today.plusDays(1), zone))).count();
        CurrentCounts current = new CurrentCounts("ACTIVE".equals(site.state()) ? 1 : 0,
                floors.stream().filter(f -> "ACTIVE".equals(f.state())).count(),
                resources.stream().filter(ResourceRow::inDenominator).count(),
                resources.stream().filter(r -> !"ROOM".equals(r.type()) && "ASSIGNED".equals(r.mode())
                        && !"RETIRED".equals(r.state())).count(), thisWeek, checkedInToday,
                metrics.summary(generatedAt, generatedAt.plusHours(1)).utilizationPercent(), policy(tenantId));
        List<BookingDetail> exceptions = bookings.stream().filter(b -> intersects(b, startsAt, endsAt))
                .map(b -> detail(b, generatedAt)).filter(b -> !b.exceptionReasons().isEmpty())
                .sorted(Comparator.comparing(BookingDetail::startsAt).reversed()
                        .thenComparing(BookingDetail::bookingId)).toList();
        Availability availability = summary.bookingCount() == 0 ? Availability.EMPTY : Availability.AVAILABLE;
        return new Report(new Scope(siteId, floorId, site.name(), site.timeZone(), from, to, startsAt, endsAt,
                        allowedFloors == null ? "SITE" : "FLOORS", sorted(allowedFloors)),
                metadata(tenantId, siteId, floorId, generatedAt, availability, allowedFloors), current, summary,
                byFloor, metrics.heatmap(from, to, zone), metrics.trend(from, to, zone),
                new Comparison(previousFrom, from, previous,
                        change(summary.utilizationPercent(), previous.utilizationPercent()),
                        change(summary.noShowPercent(), previous.noShowPercent())),
                page(exceptions, page, size), externalSources(tenantId, globalMetadata), DEFINITIONS);
    }

    BookingDetail booking(Long tenantId, UUID siteId, UUID bookingId) {
        requireSite(tenantId, siteId);
        BookingRow booking = repository.booking(tenantId, siteId, bookingId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND, "Booking not found in this Workplace site."));
        if ("ROOM".equals(booking.resourceType())) throw new BaseException(
                ErrorCode.INVALID_INPUT_VALUE, "This booking belongs to the Rooms/Calendar owner.");
        return detail(booking, repository.generatedAt());
    }

    FutureBookingImpact futureImpact(Long tenantId, UUID siteId, UUID resourceId,
                                     OffsetDateTime from, OffsetDateTime to, int page, int size) {
        validateRange(from, to);
        validatePage(page, size);
        requireSite(tenantId, siteId);
        ResourceRow resource = repository.resource(tenantId, siteId, resourceId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND, "Resource not found in this Workplace site."));
        OffsetDateTime generatedAt = repository.generatedAt();
        if ("ROOM".equals(resource.type())) return new FutureBookingImpact(resourceId, siteId,
                resource.name(), "ROOMS", resource.state(), from, to,
                metadata(tenantId, siteId, resource.floorId(), generatedAt, Availability.UNAVAILABLE, "ROOMS"),
                null, false, false, false);
        OffsetDateTime effectiveFrom = from.isBefore(generatedAt) ? generatedAt : from;
        if (!to.isAfter(effectiveFrom)) return new FutureBookingImpact(resourceId, siteId,
                resource.name(), "WORKPLACE", resource.state(), from, to,
                metadata(tenantId, siteId, resource.floorId(), generatedAt, Availability.EMPTY),
                new BookingPage(List.of(), page, size, 0, 0), false, false, false);
        long total = repository.futureBookingCount(tenantId, siteId, resourceId, effectiveFrom, to);
        List<BookingDetail> rows = repository.futureBookings(tenantId, siteId, resourceId,
                effectiveFrom, to, page, size).stream().map(b -> detail(b, generatedAt)).toList();
        return new FutureBookingImpact(resourceId, siteId, resource.name(), "WORKPLACE", resource.state(),
                effectiveFrom, to, metadata(tenantId, siteId, resource.floorId(), generatedAt,
                total == 0 ? Availability.EMPTY : Availability.AVAILABLE),
                new BookingPage(rows, page, size, total, pages(total, size)), false, false, false);
    }

    PolicyImpact policyImpact(Long tenantId, UUID siteId, UUID floorId, OffsetDateTime from,
                              OffsetDateTime to, PolicyChanges changes, int page, int size) {
        return policyImpact(tenantId, siteId, floorId, from, to, changes, page, size, null);
    }

    PolicyImpact policyImpact(Long tenantId, UUID siteId, UUID floorId, OffsetDateTime from,
                              OffsetDateTime to, PolicyChanges changes, int page, int size, Set<UUID> allowedFloors) {
        validateRange(from, to);
        validatePage(page, size);
        SiteRow site = requireSite(tenantId, siteId);
        requireFloors(tenantId, siteId, floorId, allowedFloors);
        OffsetDateTime generatedAt = repository.generatedAt();
        List<BookingRow> bookings = boundedBookings(tenantId, siteId, floorId, from, to, allowedFloors).stream()
                .filter(b -> b.endsAt().isAfter(generatedAt))
                .filter(b -> Set.of("RESERVED", "CHECKED_IN").contains(b.status())).toList();
        List<PolicyEffect> effects = policyPreview.effects(tenantId, site, bookings, changes, generatedAt);
        int start = (int) Math.min(effects.size(), (long) page * size);
        List<PolicyEffect> content = effects.subList(start, Math.min(effects.size(), start + size));
        return new PolicyImpact(siteId, floorId, from, to,
                metadata(tenantId, siteId, floorId, generatedAt,
                        bookings.isEmpty() ? Availability.EMPTY : Availability.AVAILABLE, allowedFloors),
                changes, bookings.size(), effects.size(), content, page, size, pages(effects.size(), size),
                false, List.of("Read-only candidate comparison against each resource's effective current policy.",
                "Existing bookings are not rewritten or cancelled; saving a policy is a separate command.",
                "No productivity, sensor, attendance, utilization improvement or notification forecast.",
                "This preview does not promise retroactive enforcement, recurring expansion or a full policy mutation plan.",
                "Daily candidate counts use the complete cohort before pagination; each booking belongs once to the site's local date of max(booking start, from)."),
                WorkplaceExperienceReportPolicyPreview.dailyImpact(bookings, effects, site.timeZone(), from, to),
                allowedFloors == null ? "SITE" : "FLOORS", sorted(allowedFloors));
    }

    private SiteRow requireSite(Long tenantId, UUID siteId) {
        if (tenantId == null || tenantId <= 0 || siteId == null) throw invalid("Tenant and site are required.");
        return repository.site(tenantId, siteId).orElseThrow(() -> new BaseException(
                ErrorCode.NOT_FOUND, "Workplace site not found in this tenant."));
    }

    private List<FloorRow> requireFloors(Long tenantId, UUID siteId, UUID floorId) {
        return requireFloors(tenantId, siteId, floorId, null);
    }

    private List<FloorRow> requireFloors(Long tenantId, UUID siteId, UUID floorId, Set<UUID> allowedFloors) {
        List<FloorRow> floors = repository.floors(tenantId, siteId, floorId, allowedFloors);
        if (floorId != null && floors.isEmpty()) throw new BaseException(
                ErrorCode.NOT_FOUND, "Floor not found in this Workplace site.");
        return floors;
    }

    private List<BookingRow> boundedBookings(Long tenantId, UUID siteId, UUID floorId,
                                            OffsetDateTime from, OffsetDateTime to) {
        return boundedBookings(tenantId, siteId, floorId, from, to, null);
    }

    private List<BookingRow> boundedBookings(Long tenantId, UUID siteId, UUID floorId,
                                            OffsetDateTime from, OffsetDateTime to, Set<UUID> allowedFloors) {
        List<BookingRow> rows = repository.bookings(tenantId, siteId, floorId, from, to, allowedFloors);
        if (rows.size() > 100_000) throw invalid("Too many reservation records; choose a smaller date or floor range.");
        return rows;
    }

    private Metadata metadata(Long tenantId, UUID siteId, UUID floorId,
                              OffsetDateTime generatedAt, Availability availability) {
        return metadata(tenantId, siteId, floorId, generatedAt, availability, "WORKPLACE");
    }

    private Metadata metadata(Long tenantId, UUID siteId, UUID floorId,
                              OffsetDateTime generatedAt, Availability availability, String owner) {
        return new Metadata(generatedAt, repository.sourceUpdatedAt(tenantId, siteId, floorId),
                availability, owner, "CURRENT_RESERVABLE_ROSTER_ELAPSED_MINUTES", false, false);
    }

    private Metadata metadata(Long tenantId, UUID siteId, UUID floorId,
                              OffsetDateTime generatedAt, Availability availability, Set<UUID> allowedFloors) {
        return new Metadata(generatedAt, repository.sourceUpdatedAt(tenantId, siteId, floorId, allowedFloors),
                availability, "WORKPLACE", "CURRENT_RESERVABLE_ROSTER_ELAPSED_MINUTES", false, false);
    }

    private static List<UUID> sorted(Set<UUID> floors) {
        return floors == null ? null : floors.stream().sorted().toList();
    }

    private List<ExternalSource> externalSources(Long tenantId, boolean globalMetadata) {
        if (globalMetadata) return externalSources(tenantId);
        return List.of(new ExternalSource("SENSOR_OCCUPANCY", Availability.UNAVAILABLE,
                "REQUIRES_GLOBAL_AUTHORITY", "FACILITY", null, null, null, null),
                new ExternalSource("FACILITY_WORK_ORDER", Availability.UNAVAILABLE,
                        "NO_VERIFIED_EXTERNAL_PRODUCER", "FACILITY", null, null, null, null),
                new ExternalSource("ROOMS_BOOKINGS", Availability.UNAVAILABLE,
                        "SEPARATE_AUTHORITY_DOMAIN", "ROOMS", null, null, null, null));
    }

    private WorkplaceDtos.Policy policy(Long tenantId) {
        try {
            WorkplaceCatalogRepository.PolicyRow p = catalog.policy(tenantId);
            return new WorkplaceDtos.Policy(p.bookingWindowDays(), p.maximumActiveBookings(),
                    p.minimumBookingMinutes(), p.maximumBookingMinutes(), p.maximumConsecutiveDays(),
                    p.workingDayStart(), p.workingDayEnd(), p.allowRecurring(), p.requireCheckIn(),
                    p.checkInLeadMinutes(), p.autoReleaseMinutes(), p.allowAssignedDeskLending(),
                    p.showColleagueNames(), p.bookingRetentionDays(), p.version());
        } catch (EmptyResultDataAccessException absent) { return null; }
    }

    static BookingPage page(List<BookingDetail> rows, int page, int size) {
        int start = (int) Math.min(rows.size(), (long) page * size);
        return new BookingPage(rows.subList(start, Math.min(rows.size(), start + size)),
                page, size, rows.size(), pages(rows.size(), size));
    }

    private List<ExternalSource> externalSources(Long tenantId) {
        var presence = collaboration.connector(tenantId,
                WorkplaceExperienceCollaborationDtos.ConnectorKind.ACTUAL_PRESENCE);
        return List.of(new ExternalSource("SENSOR_OCCUPANCY", Availability.UNAVAILABLE,
                "NO_VERIFIED_SENSOR_PRODUCER", "FACILITY",
                "/api/platform/v1/admin/workplace/experience/collaboration/overview",
                presence.status().name(), presence.provider(), presence.lastVerifiedAt()),
                new ExternalSource("FACILITY_WORK_ORDER", Availability.UNAVAILABLE,
                        "RESOURCE_STATE_IS_NOT_A_FACILITY_WORK_ORDER", "FACILITY", null,
                        "NOT_SUPPORTED", null, null),
                new ExternalSource("ROOMS_BOOKINGS", Availability.UNAVAILABLE,
                        "SEPARATE_AUTHORITY_DOMAIN", "ROOMS", "/api/platform/v1/admin/rooms/bookings",
                        "SEPARATE_OWNER", null, null));
    }

    private static boolean intersects(BookingRow b, OffsetDateTime from, OffsetDateTime to) {
        return b.startsAt().isBefore(to) && b.endsAt().isAfter(from);
    }
    private static OffsetDateTime at(LocalDate date, ZoneId zone) { return date.atStartOfDay(zone).toOffsetDateTime(); }
    static int pages(long total, int size) { return (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size); }
    static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid("Page must be nonnegative and size must be 1–100.");
    }
    static void validateDates(LocalDate from, LocalDate to) {
        if (from == null || to == null || !to.isAfter(from) || ChronoUnit.DAYS.between(from, to) > 93) {
            throw invalid("Choose an exclusive end date 1–93 days after the start date.");
        }
    }
    static void validateRange(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !to.isAfter(from)
                || Duration.between(from, to).compareTo(Duration.ofDays(94)) > 0) {
            throw invalid("Choose a positive date-time range of at most 94 elapsed days.");
        }
    }
}
