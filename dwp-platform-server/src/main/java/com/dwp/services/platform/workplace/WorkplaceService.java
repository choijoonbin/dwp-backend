package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.calendar.CalendarDtos;
import com.dwp.services.platform.calendar.CalendarService;
import com.dwp.services.platform.calendar.CalendarTypes;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.*;

@Service
public class WorkplaceService {

    private static final Duration MAX_EXPLORE_SPAN = Duration.ofDays(31);
    private static final Duration MAX_BOOKING_HISTORY_SPAN = Duration.ofDays(400);

    private final WorkplaceCatalogRepository catalog;
    private final WorkplaceBookingRepository bookings;
    private final CalendarService calendarService;
    private final TenantMediaStorage mediaStorage;
    private final WorkplaceFloorPlanValidator floorPlanValidator;

    public WorkplaceService(
            WorkplaceCatalogRepository catalog,
            WorkplaceBookingRepository bookings,
            CalendarService calendarService,
            TenantMediaStorage mediaStorage,
            WorkplaceFloorPlanValidator floorPlanValidator) {
        this.catalog = catalog;
        this.bookings = bookings;
        this.calendarService = calendarService;
        this.mediaStorage = mediaStorage;
        this.floorPlanValidator = floorPlanValidator;
    }

    @Transactional(readOnly = true)
    public WorkplaceDtos.ExploreResponse explore(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID floorId,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale) {
        validateRange(from, to, MAX_EXPLORE_SPAN, "Workplace searches are limited to 31 days.");
        boolean ko = korean(locale);
        List<WorkplaceCatalogRepository.SiteRow> siteRows = catalog.sites(tenantId, ko).stream()
                .filter(value -> value.state() == SiteState.ACTIVE)
                .toList();
        Set<UUID> activeSiteIds = siteRows.stream()
                .map(WorkplaceCatalogRepository.SiteRow::siteId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<WorkplaceCatalogRepository.FloorRow> floorRows = catalog.floors(tenantId, null, ko).stream()
                .filter(value -> value.state() == FloorState.ACTIVE)
                .filter(value -> activeSiteIds.contains(value.siteId()))
                .toList();
        WorkplaceCatalogRepository.FloorRow selected = selectFloor(floorRows, floorId);
        WorkplaceCatalogRepository.PolicyRow policy = catalog.policy(tenantId);
        if (selected == null) {
            return new WorkplaceDtos.ExploreResponse(
                    siteRows.stream().map(this::site).toList(),
                    floorRows.stream().map(this::floor).toList(), null, List.of(), List.of(),
                    policy(policy), OffsetDateTime.now());
        }
        List<WorkplaceDtos.Resource> resources = catalog
                .resources(tenantId, selected.floorId(), ko).stream()
                .filter(value -> value.state() != ResourceState.RETIRED)
                .map(value -> publicResource(
                        value, selected.siteId(), userId, personPublicId,
                        policy.showColleagueNames()))
                .toList();
        List<WorkplaceDtos.Occupancy> occupancy = bookings
                .occupancy(tenantId, userId, selected.floorId(), from, to).stream()
                .map(value -> occupancy(value, policy.showColleagueNames()))
                .toList();
        return new WorkplaceDtos.ExploreResponse(
                siteRows.stream().map(this::site).toList(),
                floorRows.stream().map(this::floor).toList(), floor(selected),
                resources, occupancy, policy(policy), OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<WorkplaceDtos.Booking> myBookings(
            Long tenantId,
            Long userId,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale) {
        validateRange(
                from, to, MAX_BOOKING_HISTORY_SPAN,
                "Workplace booking history is limited to 400 days.");
        WorkplaceCatalogRepository.PolicyRow policy = catalog.policy(tenantId);
        OffsetDateTime now = OffsetDateTime.now();
        return bookings.bookings(tenantId, userId, from, to, korean(locale)).stream()
                .map(value -> booking(value, policy, now))
                .toList();
    }

    @Transactional
    public WorkplaceDtos.Booking createBooking(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String displayName,
            String locale,
            String correlationId,
            WorkplaceDtos.BookingRequest request) {
        boolean ko = korean(locale);
        WorkplaceCatalogRepository.ResourceRow resource = catalog
                .resource(tenantId, request.resourceId(), ko)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        WorkplaceCatalogRepository.FloorRow floor = catalog
                .floor(tenantId, resource.floorId(), ko)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        WorkplaceCatalogRepository.SiteRow site = catalog
                .site(tenantId, floor.siteId(), ko)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        WorkplaceCatalogRepository.PolicyRow policy = catalog.policy(tenantId);
        validateBookable(resource, userId, personPublicId, request, site, floor, policy);
        bookings.lockUserBookingScope(tenantId, userId);
        if (bookings.activeBookingCount(tenantId, userId, OffsetDateTime.now())
                >= policy.maximumActiveBookings()) {
            throw invalid("The active Workplace booking limit has been reached.");
        }
        if (bookings.userHasConflict(tenantId, userId, request.startsAt(), request.endsAt())) {
            throw conflict("You already have another Workplace reservation in this time range.");
        }
        try {
            WorkplaceBookingRepository.BookingRow saved = bookings.createBooking(
                    tenantId, userId, personPublicId, display(displayName), request, ko);
            bookings.audit(tenantId, userId, "workplace.booking.created", "BOOKING",
                    saved.bookingId(), correlationId, Map.of(
                            "resourceId", saved.resourceId(),
                            "startsAt", saved.startsAt(),
                            "endsAt", saved.endsAt()));
            return booking(saved, policy, OffsetDateTime.now());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The selected time conflicts with an existing Workplace reservation.");
        }
    }

    @Transactional
    public WorkplaceDtos.Booking checkIn(
            Long tenantId,
            Long userId,
            UUID bookingId,
            String locale,
            String correlationId,
            WorkplaceDtos.VersionRequest request) {
        WorkplaceCatalogRepository.PolicyRow policy = catalog.policy(tenantId);
        WorkplaceBookingRepository.BookingRow current = requireBookingRow(
                tenantId, userId, bookingId, locale);
        OffsetDateTime now = OffsetDateTime.now();
        if (!policy.requireCheckIn() || current.status() != BookingStatus.RESERVED) {
            throw invalid("This reservation is not eligible for check-in.");
        }
        OffsetDateTime opens = current.startsAt().minusMinutes(policy.checkInLeadMinutes());
        OffsetDateTime closes = current.startsAt().plusMinutes(policy.autoReleaseMinutes());
        if (now.isBefore(opens) || now.isAfter(closes) || now.isAfter(current.endsAt())) {
            throw invalid("Check-in is outside the allowed arrival window.");
        }
        if (bookings.checkIn(tenantId, userId, bookingId, request.version(), now) == 0) {
            throw conflict("The reservation changed. Refresh and try again.");
        }
        bookings.audit(tenantId, userId, "workplace.booking.checked_in", "BOOKING",
                bookingId, correlationId, Map.of("checkedInAt", now));
        return booking(requireBookingRow(tenantId, userId, bookingId, locale), policy, now);
    }

    @Transactional
    public WorkplaceDtos.Booking cancelBooking(
            Long tenantId,
            Long userId,
            UUID bookingId,
            String locale,
            String correlationId,
            WorkplaceDtos.VersionRequest request) {
        WorkplaceBookingRepository.BookingRow current = requireBookingRow(
                tenantId, userId, bookingId, locale);
        OffsetDateTime now = OffsetDateTime.now();
        if (current.status() != BookingStatus.RESERVED || !now.isBefore(current.startsAt())) {
            throw invalid("Only a future reserved booking can be cancelled.");
        }
        if (bookings.cancel(tenantId, userId, bookingId, request.version(), now) == 0) {
            throw conflict("The reservation changed. Refresh and try again.");
        }
        bookings.audit(tenantId, userId, "workplace.booking.cancelled", "BOOKING",
                bookingId, correlationId, Map.of("cancelledAt", now));
        WorkplaceCatalogRepository.PolicyRow policy = catalog.policy(tenantId);
        return booking(requireBookingRow(tenantId, userId, bookingId, locale), policy, now);
    }

    @Transactional
    public WorkplaceDtos.Booking releaseBooking(
            Long tenantId,
            Long userId,
            UUID bookingId,
            String locale,
            String correlationId,
            WorkplaceDtos.VersionRequest request) {
        WorkplaceBookingRepository.BookingRow current = requireBookingRow(
                tenantId, userId, bookingId, locale);
        OffsetDateTime now = OffsetDateTime.now();
        boolean active = current.status() == BookingStatus.RESERVED
                || current.status() == BookingStatus.CHECKED_IN;
        if (!active || now.isBefore(current.startsAt()) || !now.isBefore(current.endsAt())) {
            throw invalid("Only an active booking can be released after it starts.");
        }
        if (bookings.release(tenantId, userId, bookingId, request.version(), now) == 0) {
            throw conflict("The reservation changed. Refresh and try again.");
        }
        bookings.audit(tenantId, userId, "workplace.booking.released", "BOOKING",
                bookingId, correlationId, Map.of("releasedAt", now));
        WorkplaceCatalogRepository.PolicyRow policy = catalog.policy(tenantId);
        return booking(requireBookingRow(tenantId, userId, bookingId, locale), policy, now);
    }

    @Transactional(readOnly = true)
    public List<WorkplaceDtos.Site> sites(Long tenantId, String locale) {
        return catalog.sites(tenantId, korean(locale)).stream().map(this::site).toList();
    }

    @Transactional(readOnly = true)
    public List<WorkplaceDtos.Floor> floors(Long tenantId, UUID siteId, String locale) {
        requireSite(tenantId, siteId, locale);
        return catalog.floors(tenantId, siteId, korean(locale)).stream().map(this::floor).toList();
    }

    @Transactional(readOnly = true)
    public List<WorkplaceDtos.Resource> resources(Long tenantId, UUID floorId, String locale) {
        WorkplaceCatalogRepository.FloorRow floor = requireFloor(tenantId, floorId, locale);
        return catalog.resources(tenantId, floorId, korean(locale)).stream()
                .map(value -> resource(value, floor.siteId()))
                .toList();
    }

    @Transactional
    public WorkplaceDtos.Site saveSite(
            Long tenantId,
            Long actorId,
            UUID siteId,
            String locale,
            String correlationId,
            WorkplaceDtos.SiteRequest request) {
        requireWriteMode(siteId, request.version(), "site");
        if (siteId != null) {
            requireSite(tenantId, siteId, locale);
        }
        validateTimeZone(request.timeZone());
        try {
            WorkplaceCatalogRepository.SiteRow saved = catalog.saveSite(
                    tenantId, actorId, siteId, request, korean(locale));
            if (saved == null) throw stale();
            bookings.audit(tenantId, actorId,
                    siteId == null ? "workplace.site.created" : "workplace.site.updated",
                    "SITE", saved.siteId(), correlationId, Map.of("code", saved.code()));
            return site(saved);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The site code or floor hierarchy already exists.");
        }
    }

    @Transactional
    public WorkplaceDtos.Floor saveFloor(
            Long tenantId,
            Long actorId,
            UUID siteId,
            UUID floorId,
            String locale,
            String correlationId,
            WorkplaceDtos.FloorRequest request) {
        requireWriteMode(floorId, request.version(), "floor");
        requireSite(tenantId, siteId, locale);
        if (floorId != null) {
            WorkplaceCatalogRepository.FloorRow current =
                    requireFloor(tenantId, floorId, locale);
            if (!current.siteId().equals(siteId)) {
                throw invalid("The floor does not belong to the selected site.");
            }
        }
        try {
            WorkplaceCatalogRepository.FloorRow saved = catalog.saveFloor(
                    tenantId, actorId, siteId, floorId, request, korean(locale));
            if (saved == null) throw stale();
            bookings.audit(tenantId, actorId,
                    floorId == null ? "workplace.floor.created" : "workplace.floor.updated",
                    "FLOOR", saved.floorId(), correlationId,
                    Map.of("siteId", siteId, "floorNumber", saved.floorNumber()));
            return floor(saved);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("This floor already exists at the selected site.");
        }
    }

    @Transactional
    public WorkplaceDtos.Floor uploadFloorBackground(
            Long tenantId,
            Long actorId,
            UUID floorId,
            Long version,
            String locale,
            String correlationId,
            MultipartFile file) {
        WorkplaceCatalogRepository.FloorRow current = requireFloor(tenantId, floorId, locale);
        WorkplaceFloorPlanValidator.ValidatedFloorPlan plan = floorPlanValidator.validate(file);
        String storageKey = mediaStorage.store(
                tenantId, "workplace/floors/" + floorId, plan.extension(), plan.content());
        try {
            String path = "/api/platform/v1/workplace/floors/" + floorId + "/background";
            WorkplaceCatalogRepository.FloorRow saved = catalog.updateFloorBackground(
                    tenantId, actorId, floorId, version, path, storageKey,
                    plan.contentType(), plan.sizeBytes(), plan.sha256(), korean(locale));
            if (saved == null) throw stale();
            bookings.audit(tenantId, actorId, "workplace.floor-plan.uploaded", "FLOOR",
                    floorId, correlationId, Map.of(
                            "sha256", plan.sha256(),
                            "width", plan.width(),
                            "height", plan.height()));
            scheduleAssetCleanup(tenantId, storageKey, current.backgroundAssetKey());
            return floor(saved);
        } catch (RuntimeException exception) {
            mediaStorage.delete(tenantId, storageKey);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public FloorBackground floorBackground(Long tenantId, UUID floorId) {
        WorkplaceCatalogRepository.FloorRow floor = catalog.floor(tenantId, floorId, false)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (floor.backgroundAssetKey() == null || floor.backgroundContentType() == null
                || floor.backgroundSizeBytes() == null || floor.backgroundSha256() == null) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        return new FloorBackground(
                mediaStorage.load(tenantId, floor.backgroundAssetKey()),
                floor.backgroundContentType(), floor.backgroundSizeBytes(),
                floor.backgroundSha256());
    }

    @Transactional
    public WorkplaceDtos.Resource saveResource(
            Long tenantId,
            Long actorId,
            UUID floorId,
            UUID resourceId,
            String locale,
            String correlationId,
            WorkplaceDtos.ResourceRequest request) {
        requireWriteMode(resourceId, request.version(), "resource");
        validateResource(request);
        WorkplaceCatalogRepository.FloorRow floor = requireFloor(tenantId, floorId, locale);
        WorkplaceCatalogRepository.SiteRow site = requireSite(
                tenantId, floor.siteId(), locale);
        WorkplaceCatalogRepository.ResourceRow existing = resourceId == null ? null
                : catalog.resource(tenantId, resourceId, korean(locale))
                    .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (existing != null && !existing.floorId().equals(floorId)) {
            throw invalid("The resource does not belong to the selected floor.");
        }
        if (existing != null && existing.calendarResourceId() != null
                && request.type() != ResourceType.ROOM) {
            throw invalid("A calendar-linked room cannot be converted to another resource type.");
        }
        UUID calendarResourceId = saveCalendarRoom(
                tenantId, actorId, locale, correlationId, request, site, floor, existing);
        try {
            WorkplaceCatalogRepository.ResourceRow saved = catalog.saveResource(
                    tenantId, actorId, floorId, resourceId, calendarResourceId,
                    request, korean(locale));
            if (saved == null) throw stale();
            bookings.audit(tenantId, actorId,
                    resourceId == null ? "workplace.resource.created" : "workplace.resource.updated",
                    "RESOURCE", saved.resourceId(), correlationId,
                    Map.of("code", saved.code(), "type", saved.type().name()));
            return resource(saved, floor.siteId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The resource code or placement is invalid or already in use.");
        }
    }

    @Transactional
    public List<WorkplaceDtos.Resource> updateLayout(
            Long tenantId,
            Long actorId,
            UUID floorId,
            String locale,
            String correlationId,
            WorkplaceDtos.LayoutRequest request) {
        WorkplaceCatalogRepository.FloorRow floor = requireFloor(tenantId, floorId, locale);
        request.resources().forEach(this::validatePlacement);
        for (WorkplaceDtos.ResourcePlacement placement : request.resources()) {
            if (!catalog.updatePlacement(tenantId, actorId, floorId, placement)) {
                throw stale();
            }
        }
        bookings.audit(tenantId, actorId, "workplace.layout.updated", "FLOOR",
                floorId, correlationId, Map.of("resourceCount", request.resources().size()));
        return catalog.resources(tenantId, floorId, korean(locale)).stream()
                .map(value -> resource(value, floor.siteId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkplaceDtos.Policy policy(Long tenantId) {
        return policy(catalog.policy(tenantId));
    }

    @Transactional
    public WorkplaceDtos.Policy updatePolicy(
            Long tenantId,
            Long actorId,
            String correlationId,
            WorkplaceDtos.PolicyRequest request) {
        if (!request.workingDayEnd().isAfter(request.workingDayStart())) {
            throw invalid("Working hours must end after they start.");
        }
        if (request.maximumBookingMinutes() < request.minimumBookingMinutes()) {
            throw invalid("The maximum booking duration must be at least the minimum duration.");
        }
        if (request.maximumConsecutiveDays() > request.bookingWindowDays()) {
            throw invalid("Consecutive booking days cannot exceed the advance booking window.");
        }
        WorkplaceCatalogRepository.PolicyRow saved = catalog.updatePolicy(tenantId, actorId, request);
        if (saved == null) throw stale();
        bookings.audit(tenantId, actorId, "workplace.policy.updated", "POLICY",
                null, correlationId, Map.of("version", saved.version()));
        return policy(saved);
    }

    @Transactional(readOnly = true)
    public WorkplaceDtos.AdminOverview adminOverview(Long tenantId) {
        WorkplaceCatalogRepository.PolicyRow policy = catalog.policy(tenantId);
        ZoneId zone = catalog.sites(tenantId, false).stream()
                .filter(value -> value.state() == SiteState.ACTIVE)
                .sorted(java.util.Comparator.comparingInt(value ->
                        value.type() == SiteType.HEADQUARTERS ? 0 : 1))
                .map(WorkplaceCatalogRepository.SiteRow::timeZone)
                .map(ZoneId::of)
                .findFirst()
                .orElse(ZoneId.of("UTC"));
        LocalDate monday = LocalDate.now(zone)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        OffsetDateTime from = monday.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to = from.plusDays(7);
        OffsetDateTime dayStart = LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime();
        WorkplaceCatalogRepository.AdminStats stats = catalog.adminStats(
                tenantId, from, to, dayStart, dayStart.plusDays(1));
        long availableMinutes = stats.utilizationResources()
                * 5L * Duration.between(policy.workingDayStart(), policy.workingDayEnd()).toMinutes();
        int utilization = availableMinutes == 0 ? 0 : (int) Math.min(100,
                Math.round(bookings.occupiedMinutes(tenantId, from, to) * 100.0 / availableMinutes));
        return new WorkplaceDtos.AdminOverview(
                stats.activeSites(), stats.configuredFloors(), stats.reservableResources(),
                stats.assignedResources(), stats.bookingsThisWeek(), stats.checkedInToday(),
                utilization, policy(policy), OffsetDateTime.now());
    }

    private UUID saveCalendarRoom(
            Long tenantId,
            Long actorId,
            String locale,
            String correlationId,
            WorkplaceDtos.ResourceRequest request,
            WorkplaceCatalogRepository.SiteRow site,
            WorkplaceCatalogRepository.FloorRow floor,
            WorkplaceCatalogRepository.ResourceRow existing) {
        if (request.type() != ResourceType.ROOM) {
            return existing == null ? null : existing.calendarResourceId();
        }
        Long calendarVersion = existing == null ? null : existing.calendarVersion();
        CalendarDtos.ResourceRequest calendarRequest = new CalendarDtos.ResourceRequest(
                request.code(), request.nameKo(), request.nameEn(), CalendarTypes.ResourceType.ROOM,
                site.nameKo(), floor.nameEn(), request.capacity(), request.features(), site.timeZone(),
                request.approvalRequired(), CalendarTypes.ResourceState.valueOf(request.state().name()),
                calendarVersion);
        return calendarService.saveWorkplaceManagedResource(
                tenantId, actorId, existing == null ? null : existing.calendarResourceId(),
                locale, correlationId, calendarRequest).resourceId();
    }

    private void validateBookable(
            WorkplaceCatalogRepository.ResourceRow resource,
            Long userId,
            UUID personPublicId,
            WorkplaceDtos.BookingRequest request,
            WorkplaceCatalogRepository.SiteRow site,
            WorkplaceCatalogRepository.FloorRow floor,
            WorkplaceCatalogRepository.PolicyRow policy) {
        if (site.state() != SiteState.ACTIVE || floor.state() != FloorState.ACTIVE) {
            throw invalid("This Workplace location is not open for booking.");
        }
        if (resource.type() == ResourceType.ROOM) {
            throw invalid("Meeting rooms must be reserved with the calendar-aware room flow.");
        }
        if (resource.state() != ResourceState.AVAILABLE
                || resource.mode() == BookingMode.UNAVAILABLE) {
            throw invalid("This Workplace resource is not available for booking.");
        }
        boolean assignedToCurrentUser = userId.equals(resource.assignedUserId())
                || (personPublicId != null && personPublicId.equals(resource.assignedPersonPublicId()));
        if (resource.mode() == BookingMode.ASSIGNED
                && !assignedToCurrentUser
                && !policy.allowAssignedDeskLending()) {
            throw new BaseException(ErrorCode.FORBIDDEN, "This is an assigned workspace.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (request.startsAt() == null || request.endsAt() == null
                || !request.endsAt().isAfter(request.startsAt())
                || request.startsAt().isBefore(now.minusMinutes(1))) {
            throw invalid("A valid future booking period is required.");
        }
        if (request.startsAt().isAfter(now.plusDays(policy.bookingWindowDays()))) {
            throw invalid("The booking is outside the configured advance window.");
        }
        long minutes = Duration.between(request.startsAt(), request.endsAt()).toMinutes();
        if (minutes < policy.minimumBookingMinutes() || minutes > policy.maximumBookingMinutes()) {
            throw invalid("The reservation duration is outside the Workplace policy.");
        }
        ZoneId zone = ZoneId.of(site.timeZone());
        LocalDateTime localStart = request.startsAt().atZoneSameInstant(zone).toLocalDateTime();
        LocalDateTime localEnd = request.endsAt().atZoneSameInstant(zone).toLocalDateTime();
        if (!localStart.toLocalDate().equals(localEnd.toLocalDate())
                || localStart.toLocalTime().isBefore(policy.workingDayStart())
                || localEnd.toLocalTime().isAfter(policy.workingDayEnd())) {
            throw invalid("The reservation must fit within one configured working day.");
        }
        if (resource.mode() == BookingMode.DROP_IN
                && request.startsAt().isAfter(now.plusMinutes(15))) {
            throw invalid("Drop-in resources can only be claimed on arrival.");
        }
    }

    private void validateResource(WorkplaceDtos.ResourceRequest request) {
        validateBounds(request.positionX(), request.positionY(),
                request.widthPercent(), request.heightPercent());
        boolean hasAssignee = request.assignedUserId() != null
                || request.assignedPersonPublicId() != null;
        if (request.mode() == BookingMode.ASSIGNED && !hasAssignee) {
            throw invalid("Assigned resources require an assignee.");
        }
        if (request.type() != ResourceType.ROOM && request.approvalRequired()) {
            throw invalid("Approval workflow currently applies to meeting rooms only.");
        }
    }

    private void validatePlacement(WorkplaceDtos.ResourcePlacement request) {
        validateBounds(request.positionX(), request.positionY(),
                request.widthPercent(), request.heightPercent());
    }

    private void validateBounds(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {
        if (x.add(width).compareTo(BigDecimal.valueOf(100)) > 0
                || y.add(height).compareTo(BigDecimal.valueOf(100)) > 0) {
            throw invalid("The resource must remain inside the floor plan.");
        }
    }

    private WorkplaceCatalogRepository.SiteRow requireSite(
            Long tenantId, UUID siteId, String locale) {
        return catalog.site(tenantId, siteId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private WorkplaceCatalogRepository.FloorRow requireFloor(
            Long tenantId, UUID floorId, String locale) {
        return catalog.floor(tenantId, floorId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private WorkplaceBookingRepository.BookingRow requireBookingRow(
            Long tenantId, Long userId, UUID bookingId, String locale) {
        return bookings.booking(tenantId, userId, bookingId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private WorkplaceCatalogRepository.FloorRow selectFloor(
            List<WorkplaceCatalogRepository.FloorRow> floors, UUID floorId) {
        if (floorId == null) {
            return floors.stream().filter(value -> value.state() == FloorState.ACTIVE)
                    .findFirst().orElse(null);
        }
        return floors.stream().filter(value -> value.floorId().equals(floorId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void validateRange(
            OffsetDateTime from, OffsetDateTime to, Duration maximum, String maximumMessage) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw invalid("A valid date range is required.");
        }
        if (Duration.between(from, to).compareTo(maximum) > 0) {
            throw invalid(maximumMessage);
        }
    }

    private void scheduleAssetCleanup(Long tenantId, String newKey, String oldKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            if (oldKey != null) mediaStorage.delete(tenantId, oldKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (oldKey != null && !oldKey.equals(newKey)) mediaStorage.delete(tenantId, oldKey);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) mediaStorage.delete(tenantId, newKey);
            }
        });
    }

    private WorkplaceDtos.Site site(WorkplaceCatalogRepository.SiteRow value) {
        return new WorkplaceDtos.Site(
                value.siteId(), value.code(), value.name(), value.nameKo(), value.nameEn(),
                value.type(), value.address(), value.timeZone(), value.totalFloorCount(),
                value.configuredFloorCount(), value.resourceCount(), value.state(), value.version());
    }

    private WorkplaceDtos.Floor floor(WorkplaceCatalogRepository.FloorRow value) {
        return new WorkplaceDtos.Floor(
                value.floorId(), value.siteId(), value.siteName(), value.floorNumber(),
                value.name(), value.nameKo(), value.nameEn(), value.planWidth(), value.planHeight(),
                value.backgroundAssetPath(), value.state(), value.resourceCount(), value.version());
    }

    private WorkplaceDtos.Resource resource(
            WorkplaceCatalogRepository.ResourceRow value, UUID siteId) {
        return new WorkplaceDtos.Resource(
                value.resourceId(), value.floorId(), siteId, value.calendarResourceId(),
                value.code(), value.name(), value.nameKo(), value.nameEn(), value.type(),
                value.mode(), value.state(), value.neighborhood(), value.capacity(), value.features(),
                value.accessible(), value.approvalRequired(), value.positionX(), value.positionY(),
                value.widthPercent(), value.heightPercent(), value.rotationDegrees(),
                false,
                value.assignedUserId(), value.assignedPersonPublicId(),
                value.assignedDisplayName(), value.version());
    }

    private WorkplaceDtos.Resource publicResource(
            WorkplaceCatalogRepository.ResourceRow value,
            UUID siteId,
            Long userId,
            UUID personPublicId,
            boolean showColleagueNames) {
        boolean assignedToCurrentUser = userId.equals(value.assignedUserId())
                || (personPublicId != null
                    && personPublicId.equals(value.assignedPersonPublicId()));
        return new WorkplaceDtos.Resource(
                value.resourceId(), value.floorId(), siteId, value.calendarResourceId(),
                value.code(), value.name(), value.nameKo(), value.nameEn(), value.type(),
                value.mode(), value.state(), value.neighborhood(), value.capacity(), value.features(),
                value.accessible(), value.approvalRequired(), value.positionX(), value.positionY(),
                value.widthPercent(), value.heightPercent(), value.rotationDegrees(),
                assignedToCurrentUser, null, null,
                assignedToCurrentUser || showColleagueNames
                        ? value.assignedDisplayName() : null,
                value.version());
    }

    private WorkplaceDtos.Occupancy occupancy(
            WorkplaceBookingRepository.OccupancyRow value, boolean showNames) {
        String displayName = showNames || value.currentUser() ? value.bookedByDisplayName() : null;
        return new WorkplaceDtos.Occupancy(
                value.resourceId(), value.bookingId(), value.status(), value.startsAt(),
                value.endsAt(), displayName, value.currentUser());
    }

    private WorkplaceDtos.Booking booking(
            WorkplaceBookingRepository.BookingRow value,
            WorkplaceCatalogRepository.PolicyRow policy,
            OffsetDateTime now) {
        OffsetDateTime checkInOpensAt = value.startsAt().minusMinutes(policy.checkInLeadMinutes());
        OffsetDateTime checkInClosesAt = value.startsAt().plusMinutes(policy.autoReleaseMinutes());
        boolean active = value.status() == BookingStatus.RESERVED
                || value.status() == BookingStatus.CHECKED_IN;
        boolean canCheckIn = policy.requireCheckIn()
                && value.status() == BookingStatus.RESERVED
                && !now.isBefore(checkInOpensAt)
                && !now.isAfter(checkInClosesAt)
                && now.isBefore(value.endsAt());
        boolean canCancel = value.status() == BookingStatus.RESERVED
                && now.isBefore(value.startsAt());
        boolean canRelease = active
                && !now.isBefore(value.startsAt())
                && now.isBefore(value.endsAt());
        return new WorkplaceDtos.Booking(
                value.bookingId(), value.resourceId(), value.resourceName(), value.resourceType(),
                value.siteName(), value.floorName(), value.purpose(), value.startsAt(), value.endsAt(),
                value.status(), value.visibleToColleagues(), value.checkedInAt(),
                value.releasedAt(), canCheckIn, canCancel, canRelease,
                checkInOpensAt, checkInClosesAt, value.version());
    }

    @Transactional
    public int maintainBookingLifecycle() {
        if (!bookings.tryLifecycleLock()) return 0;
        OffsetDateTime now = OffsetDateTime.now();
        return bookings.releaseNoShows(now) + bookings.completeEndedBookings(now);
    }

    private void validateTimeZone(String value) {
        try {
            ZoneId.of(value == null ? "" : value.trim());
        } catch (DateTimeException exception) {
            throw invalid("A valid IANA time zone is required.");
        }
    }

    private void requireWriteMode(UUID resourceId, Long version, String resourceName) {
        if (resourceId == null && version != null) {
            throw invalid("A new Workplace " + resourceName + " cannot include a version.");
        }
        if (resourceId != null && version == null) {
            throw invalid("Updating a Workplace " + resourceName + " requires its version.");
        }
    }

    private WorkplaceDtos.Policy policy(WorkplaceCatalogRepository.PolicyRow value) {
        return new WorkplaceDtos.Policy(
                value.bookingWindowDays(), value.maximumActiveBookings(),
                value.minimumBookingMinutes(), value.maximumBookingMinutes(),
                value.maximumConsecutiveDays(), value.workingDayStart(), value.workingDayEnd(),
                value.allowRecurring(), value.requireCheckIn(), value.checkInLeadMinutes(),
                value.autoReleaseMinutes(), value.allowAssignedDeskLending(),
                value.showColleagueNames(), value.version());
    }

    private String display(String value) {
        return value == null || value.isBlank() ? "Workplace member" : value.trim();
    }

    private boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private BaseException stale() {
        return conflict("The Workplace resource changed. Refresh and try again.");
    }

    public record FloorBackground(
            Resource resource, String contentType, long sizeBytes, String sha256) {
    }
}
