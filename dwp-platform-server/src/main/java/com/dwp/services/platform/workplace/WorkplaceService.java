package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.calendar.CalendarService;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.OffsetDateTime;
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
    private final WorkplaceSpatialGovernanceService spatialGovernance;
    private final WorkplaceDomainEvents domainEvents;
    private final WorkplaceRuntimeGovernance runtimeGovernance;
    private final WorkplaceCatalogAdminService catalogAdmin;
    private final WorkplaceBookingPolicyService bookingPolicy;

    public WorkplaceService(
            WorkplaceCatalogRepository catalog,
            WorkplaceBookingRepository bookings,
            CalendarService calendarService,
            TenantMediaStorage mediaStorage,
            WorkplaceFloorPlanValidator floorPlanValidator,
            WorkplaceMediaCleanupRepository mediaCleanup,
            WorkplaceSpatialGovernanceService spatialGovernance,
            WorkplaceReleaseWindowRepository releaseWindows,
            WorkplaceDomainEvents domainEvents,
            WorkplaceRuntimeGovernance runtimeGovernance) {
        this.catalog = catalog;
        this.bookings = bookings;
        this.spatialGovernance = spatialGovernance;
        this.domainEvents = domainEvents;
        this.runtimeGovernance = runtimeGovernance;
        this.catalogAdmin = new WorkplaceCatalogAdminService(
                catalog, bookings, calendarService, mediaStorage, floorPlanValidator,
                mediaCleanup, spatialGovernance, runtimeGovernance);
        this.bookingPolicy = new WorkplaceBookingPolicyService(
                bookings, releaseWindows, runtimeGovernance);
    }

    @Transactional(readOnly = true)
    public WorkplaceDtos.ExploreResponse explore(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID floorId,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale,
            String verifiedGroupRefs) {
        validateRange(from, to, MAX_EXPLORE_SPAN, "Workplace searches are limited to 31 days.");
        boolean ko = korean(locale);
        List<WorkplaceCatalogRepository.SiteRow> siteRows = catalog.sites(tenantId, ko).stream()
                .filter(value -> value.state() == SiteState.ACTIVE)
                .filter(value -> canViewSite(
                        tenantId, userId, verifiedGroupRefs, value.siteId()))
                .toList();
        Set<UUID> activeSiteIds = siteRows.stream()
                .map(WorkplaceCatalogRepository.SiteRow::siteId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<WorkplaceCatalogRepository.FloorRow> floorRows = catalog.floors(tenantId, null, ko).stream()
                .filter(value -> value.state() == FloorState.ACTIVE)
                .filter(value -> activeSiteIds.contains(value.siteId()))
                .toList();
        WorkplaceCatalogRepository.FloorRow selected = selectFloor(floorRows, floorId);
        WorkplaceCatalogRepository.PolicyRow basePolicy = catalog.policy(tenantId);
        WorkplaceCatalogRepository.PolicyRow policy = selected == null
                ? basePolicy
                : effectivePolicy(
                        tenantId,
                        WorkplaceSpatialGovernanceDtos.PolicyScopeType.FLOOR,
                        selected.floorId(),
                        basePolicy);
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
            String verifiedGroupRefs,
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
        WorkplaceCatalogRepository.PolicyRow policy = effectivePolicy(
                tenantId,
                WorkplaceSpatialGovernanceDtos.PolicyScopeType.RESOURCE,
                resource.resourceId(),
                catalog.policy(tenantId));
        bookings.lockUserBookingScope(tenantId, userId);
        UUID releaseWindowId = validateBookable(
                tenantId, resource, userId, personPublicId, verifiedGroupRefs,
                request, site, floor, policy);
        if (bookings.activeBookingCount(tenantId, userId, OffsetDateTime.now())
                >= policy.maximumActiveBookings()) {
            throw invalid("The active Workplace booking limit has been reached.");
        }
        if (bookings.userHasConflict(tenantId, userId, request.startsAt(), request.endsAt())) {
            throw conflict("You already have another Workplace reservation in this time range.");
        }
        try {
            WorkplaceBookingRepository.BookingRow saved = bookings.createBooking(
                    tenantId, userId, personPublicId, display(displayName), request,
                    policy, releaseWindowId, ko);
            bookings.audit(tenantId, userId, "workplace.booking.created", "BOOKING",
                    saved.bookingId(), correlationId, Map.of(
                            "resourceId", saved.resourceId(),
                            "startsAt", saved.startsAt(),
                            "endsAt", saved.endsAt()));
            recordBookingEvent(
                    WorkplaceDomainEvents.CREATED,
                    tenantId,
                    correlationId,
                    saved,
                    site.siteId(),
                    floor.floorId(),
                    null);
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
        WorkplaceBookingRepository.BookingRow current = requireBookingRow(
                tenantId, userId, bookingId, locale);
        OffsetDateTime now = OffsetDateTime.now();
        if (!current.requireCheckIn() || current.status() != BookingStatus.RESERVED) {
            throw invalid("This reservation is not eligible for check-in.");
        }
        OffsetDateTime opens = current.startsAt().minusMinutes(current.checkInLeadMinutes());
        OffsetDateTime closes = current.startsAt().plusMinutes(current.autoReleaseMinutes());
        if (now.isBefore(opens) || now.isAfter(closes) || now.isAfter(current.endsAt())) {
            throw invalid("Check-in is outside the allowed arrival window.");
        }
        if (bookings.checkIn(tenantId, userId, bookingId, request.version(), now) == 0) {
            throw conflict("The reservation changed. Refresh and try again.");
        }
        bookings.audit(tenantId, userId, "workplace.booking.checked_in", "BOOKING",
                bookingId, correlationId, Map.of("checkedInAt", now));
        WorkplaceBookingRepository.BookingRow saved = requireBookingRow(
                tenantId, userId, bookingId, locale);
        recordBookingEvent(
                WorkplaceDomainEvents.CHECKED_IN,
                tenantId,
                correlationId,
                saved,
                null,
                null,
                "MEMBER_CHECKED_IN");
        return booking(saved, null, now);
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
        WorkplaceBookingRepository.BookingRow saved = requireBookingRow(
                tenantId, userId, bookingId, locale);
        recordBookingEvent(
                WorkplaceDomainEvents.CANCELLED,
                tenantId,
                correlationId,
                saved,
                null,
                null,
                "MEMBER_CANCELLED");
        return booking(saved, null, now);
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
        WorkplaceBookingRepository.BookingRow saved = requireBookingRow(
                tenantId, userId, bookingId, locale);
        recordBookingEvent(
                WorkplaceDomainEvents.RELEASED,
                tenantId,
                correlationId,
                saved,
                null,
                null,
                "MEMBER_RELEASED");
        return booking(saved, null, now);
    }

    @Transactional(readOnly = true)
    public List<WorkplaceDtos.Site> sites(Long tenantId, String locale) {
        return catalogAdmin.sites(tenantId, locale);
    }

    @Transactional(readOnly = true)
    public List<WorkplaceDtos.Floor> floors(Long tenantId, UUID siteId, String locale) {
        return catalogAdmin.floors(tenantId, siteId, locale);
    }

    @Transactional(readOnly = true)
    public List<WorkplaceDtos.Resource> resources(Long tenantId, UUID floorId, String locale) {
        return catalogAdmin.resources(tenantId, floorId, locale);
    }

    @Transactional
    public WorkplaceDtos.Site saveSite(
            Long tenantId,
            Long actorId,
            UUID siteId,
            String locale,
            String correlationId,
            WorkplaceDtos.SiteRequest request) {
        return catalogAdmin.saveSite(
                tenantId, actorId, siteId, locale, correlationId, request);
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
        return catalogAdmin.saveFloor(
                tenantId, actorId, siteId, floorId, locale, correlationId, request);
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
        return catalogAdmin.uploadFloorBackground(
                tenantId, actorId, floorId, version, locale, correlationId, file);
    }

    @Transactional
    public WorkplaceSpatialGovernanceDtos.FloorPlanRevision uploadDraftFloorBackground(
            Long tenantId,
            Long actorId,
            UUID revisionId,
            Long version,
            String changeSummary,
            String correlationId,
            MultipartFile file) {
        return catalogAdmin.uploadDraftFloorBackground(
                tenantId, actorId, revisionId, version, changeSummary, correlationId, file);
    }

    @Transactional(readOnly = true)
    public FloorBackground floorPlanRevisionBackground(Long tenantId, UUID revisionId) {
        return catalogAdmin.floorPlanRevisionBackground(tenantId, revisionId);
    }

    @Transactional(readOnly = true)
    public FloorBackground floorBackground(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            UUID floorId) {
        return catalogAdmin.floorBackground(tenantId, userId, verifiedGroupRefs, floorId);
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
        return catalogAdmin.saveResource(
                tenantId, actorId, floorId, resourceId, locale, correlationId, request);
    }

    @Transactional
    public List<WorkplaceDtos.Resource> updateLayout(
            Long tenantId,
            Long actorId,
            UUID floorId,
            String locale,
            String correlationId,
            WorkplaceDtos.LayoutRequest request) {
        return catalogAdmin.updateLayout(
                tenantId, actorId, floorId, locale, correlationId, request);
    }

    @Transactional(readOnly = true)
    public WorkplaceDtos.Policy policy(Long tenantId) {
        return catalogAdmin.policy(tenantId);
    }

    @Transactional
    public WorkplaceDtos.Policy updatePolicy(
            Long tenantId,
            Long actorId,
            String correlationId,
            WorkplaceDtos.PolicyRequest request) {
        return catalogAdmin.updatePolicy(tenantId, actorId, correlationId, request);
    }

    @Transactional(readOnly = true)
    public WorkplaceDtos.AdminOverview adminOverview(Long tenantId) {
        return catalogAdmin.adminOverview(tenantId);
    }

    UUID validateBookable(
            Long tenantId,
            WorkplaceCatalogRepository.ResourceRow resource,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            WorkplaceDtos.BookingRequest request,
            WorkplaceCatalogRepository.SiteRow site,
            WorkplaceCatalogRepository.FloorRow floor,
            WorkplaceCatalogRepository.PolicyRow policy) {
        return bookingPolicy.validateBookable(
                tenantId, resource, userId, personPublicId, verifiedGroupRefs,
                request, site, floor, policy);
    }

    WorkplaceCatalogRepository.PolicyRow resolveBookingPolicy(
            Long tenantId,
            UUID resourceId,
            WorkplaceCatalogRepository.PolicyRow base) {
        return bookingPolicy.resolveBookingPolicy(tenantId, resourceId, base);
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

    private void recordBookingEvent(
            String type,
            Long tenantId,
            String correlationId,
            WorkplaceBookingRepository.BookingRow booking,
            UUID knownSiteId,
            UUID knownFloorId,
            String reasonCode) {
        UUID floorId = knownFloorId;
        UUID siteId = knownSiteId;
        if (floorId == null || siteId == null) {
            WorkplaceCatalogRepository.ResourceRow resource = catalog
                    .resource(tenantId, booking.resourceId(), false)
                    .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
            WorkplaceCatalogRepository.FloorRow floor = catalog
                    .floor(tenantId, resource.floorId(), false)
                    .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
            floorId = floor.floorId();
            siteId = floor.siteId();
        }
        domainEvents.bookingChanged(
                type,
                tenantId,
                correlationId,
                new WorkplaceDomainEvents.BookingEvent(
                        booking.bookingId(),
                        null,
                        booking.resourceId(),
                        siteId,
                        floorId,
                        booking.status().name(),
                        booking.startsAt(),
                        booking.endsAt(),
                        reasonCode,
                        booking.version()));
    }

    private WorkplaceDtos.Site site(WorkplaceCatalogRepository.SiteRow value) {
        return new WorkplaceDtos.Site(
                value.siteId(), value.campusId(), value.code(), value.name(),
                value.nameKo(), value.nameEn(),
                value.type(), value.address(), value.timeZone(), value.totalFloorCount(),
                value.configuredFloorCount(), value.resourceCount(), value.state(), value.version());
    }

    private WorkplaceDtos.Floor floor(WorkplaceCatalogRepository.FloorRow value) {
        return new WorkplaceDtos.Floor(
                value.floorId(), value.siteId(), value.siteName(), value.floorNumber(),
                value.name(), value.nameKo(), value.nameEn(), value.planWidth(), value.planHeight(),
                value.backgroundAssetPath(), value.state(), value.resourceCount(), value.version());
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

    WorkplaceDtos.Booking booking(
            WorkplaceBookingRepository.BookingRow value,
            WorkplaceCatalogRepository.PolicyRow policy,
            OffsetDateTime now) {
        OffsetDateTime checkInOpensAt = value.startsAt()
                .minusMinutes(value.checkInLeadMinutes());
        OffsetDateTime checkInClosesAt = value.startsAt()
                .plusMinutes(value.autoReleaseMinutes());
        boolean active = value.status() == BookingStatus.RESERVED
                || value.status() == BookingStatus.CHECKED_IN;
        boolean canCheckIn = value.requireCheckIn()
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
        List<WorkplaceBookingRepository.LifecycleBookingRow> noShows =
                bookings.releaseNoShows(now);
        noShows.forEach(value -> recordLifecycleEvent(
                WorkplaceDomainEvents.NO_SHOW, value, "workplace:no-show-sweep"));
        List<WorkplaceBookingRepository.LifecycleBookingRow> completed =
                bookings.completeEndedBookings(now);
        completed.forEach(value -> recordLifecycleEvent(
                WorkplaceDomainEvents.COMPLETED, value, "workplace:lifecycle-sweep"));
        return noShows.size() + completed.size();
    }

    private boolean canViewSite(
            Long tenantId, Long userId, String verifiedGroupRefs, UUID siteId) {
        try {
            runtimeGovernance.requireViewAccess(
                    tenantId, userId, verifiedGroupRefs, siteId);
            return true;
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.FORBIDDEN) return false;
            throw exception;
        }
    }

    private WorkplaceCatalogRepository.PolicyRow effectivePolicy(
            Long tenantId,
            WorkplaceSpatialGovernanceDtos.PolicyScopeType scopeType,
            UUID scopeId,
            WorkplaceCatalogRepository.PolicyRow base) {
        WorkplaceCatalogRepository.PolicyRow resolved = runtimeGovernance.effectivePolicy(
                tenantId, scopeType, scopeId, base);
        return resolved == null ? base : resolved;
    }

    private void recordLifecycleEvent(
            String type,
            WorkplaceBookingRepository.LifecycleBookingRow value,
            String correlationId) {
        domainEvents.bookingChanged(
                type,
                value.tenantId(),
                correlationId,
                new WorkplaceDomainEvents.BookingEvent(
                        value.bookingId(),
                        null,
                        value.resourceId(),
                        value.siteId(),
                        value.floorId(),
                        value.status().name(),
                        value.startsAt(),
                        value.endsAt(),
                        value.status().name(),
                        value.version()));
    }

    private WorkplaceDtos.Policy policy(WorkplaceCatalogRepository.PolicyRow value) {
        return new WorkplaceDtos.Policy(
                value.bookingWindowDays(), value.maximumActiveBookings(),
                value.minimumBookingMinutes(), value.maximumBookingMinutes(),
                value.maximumConsecutiveDays(), value.workingDayStart(), value.workingDayEnd(),
                value.allowRecurring(), value.requireCheckIn(), value.checkInLeadMinutes(),
                value.autoReleaseMinutes(), value.allowAssignedDeskLending(),
                value.showColleagueNames(), value.bookingRetentionDays(), value.version());
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

    public record FloorBackground(
            Resource resource, String contentType, long sizeBytes, String sha256) {
    }
}
