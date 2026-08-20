package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.calendar.CalendarDtos;
import com.dwp.services.platform.calendar.CalendarService;
import com.dwp.services.platform.calendar.CalendarTypes;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.*;

final class WorkplaceCatalogAdminService {

    private final WorkplaceCatalogRepository catalog;
    private final WorkplaceBookingRepository bookings;
    private final CalendarService calendarService;
    private final TenantMediaStorage mediaStorage;
    private final WorkplaceFloorPlanValidator floorPlanValidator;
    private final WorkplaceMediaCleanupRepository mediaCleanup;
    private final WorkplaceSpatialGovernanceService spatialGovernance;
    private final WorkplaceRuntimeGovernance runtimeGovernance;

    WorkplaceCatalogAdminService(
            WorkplaceCatalogRepository catalog,
            WorkplaceBookingRepository bookings,
            CalendarService calendarService,
            TenantMediaStorage mediaStorage,
            WorkplaceFloorPlanValidator floorPlanValidator,
            WorkplaceMediaCleanupRepository mediaCleanup,
            WorkplaceSpatialGovernanceService spatialGovernance,
            WorkplaceRuntimeGovernance runtimeGovernance) {
        this.catalog = catalog;
        this.bookings = bookings;
        this.calendarService = calendarService;
        this.mediaStorage = mediaStorage;
        this.floorPlanValidator = floorPlanValidator;
        this.mediaCleanup = mediaCleanup;
        this.spatialGovernance = spatialGovernance;
        this.runtimeGovernance = runtimeGovernance;
    }

    List<WorkplaceDtos.Site> sites(Long tenantId, String locale) {
        return catalog.sites(tenantId, korean(locale)).stream().map(this::site).toList();
    }

    List<WorkplaceDtos.Floor> floors(Long tenantId, UUID siteId, String locale) {
        requireSite(tenantId, siteId, locale);
        return catalog.floors(tenantId, siteId, korean(locale)).stream().map(this::floor).toList();
    }

    List<WorkplaceDtos.Resource> resources(Long tenantId, UUID floorId, String locale) {
        WorkplaceCatalogRepository.FloorRow floor = requireFloor(tenantId, floorId, locale);
        return catalog.resources(tenantId, floorId, korean(locale)).stream()
                .map(value -> resource(value, floor.siteId()))
                .toList();
    }

    WorkplaceDtos.Site saveSite(
            Long tenantId,
            Long actorId,
            UUID siteId,
            String locale,
            String correlationId,
            WorkplaceDtos.SiteRequest request) {
        requireWriteMode(siteId, request.version(), "site");
        if (siteId != null) requireSite(tenantId, siteId, locale);
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

    WorkplaceDtos.Floor saveFloor(
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

    WorkplaceDtos.Floor uploadFloorBackground(
            Long tenantId,
            Long actorId,
            UUID floorId,
            Long version,
            String locale,
            String correlationId,
            MultipartFile file) {
        throw conflict(
                "Direct floor-plan background updates are retired. "
                        + "Create or update a governed draft revision, submit it for review, "
                        + "and publish the approved revision.");
    }

    WorkplaceSpatialGovernanceDtos.FloorPlanRevision uploadDraftFloorBackground(
            Long tenantId,
            Long actorId,
            UUID revisionId,
            Long version,
            String changeSummary,
            String correlationId,
            MultipartFile file) {
        WorkplaceSpatialGovernanceDtos.FloorPlanRevisionSnapshot snapshot =
                spatialGovernance.floorPlanRevisionSnapshot(tenantId, revisionId);
        WorkplaceSpatialGovernanceDtos.FloorPlanRevision revision = snapshot.revision();
        if (revision.state() != WorkplaceSpatialGovernanceDtos.RevisionState.DRAFT) {
            throw conflict("Only a governed draft revision can receive a background image.");
        }
        if (version == null || version != revision.version()) {
            throw conflict("The floor-plan draft changed. Refresh and retry.");
        }
        String normalizedSummary = changeSummary == null ? "" : changeSummary.trim();
        if (normalizedSummary.isEmpty() || normalizedSummary.length() > 500) {
            throw invalid("A background change summary of up to 500 characters is required.");
        }

        WorkplaceFloorPlanValidator.ValidatedFloorPlan plan = floorPlanValidator.validate(file);
        String storageKey = mediaStorage.store(
                tenantId,
                "workplace/floor-plan-revisions/" + revisionId,
                plan.extension(),
                plan.content());
        try {
            mediaCleanup.registerStaged(tenantId, storageKey);
        } catch (RuntimeException exception) {
            mediaStorage.delete(tenantId, storageKey);
            throw exception;
        }

        String path = "/api/platform/v1/admin/workplace/governance/floor-plan-revisions/"
                + revisionId + "/background";
        WorkplaceSpatialGovernanceDtos.FloorPlanSnapshotRequest request =
                new WorkplaceSpatialGovernanceDtos.FloorPlanSnapshotRequest(
                        revision.planWidth(), revision.planHeight(), path, storageKey,
                        plan.contentType(), plan.sizeBytes(), plan.sha256(), normalizedSummary,
                        snapshot.placements().stream().map(this::placementRequest).toList(),
                        version);
        return spatialGovernance.updateFloorPlanRevisionMedia(
                tenantId, actorId, revisionId, correlationId, request);
    }

    WorkplaceService.FloorBackground floorPlanRevisionBackground(
            Long tenantId, UUID revisionId) {
        WorkplaceSpatialGovernanceDtos.FloorPlanRevision revision =
                spatialGovernance.floorPlanRevisionSnapshot(tenantId, revisionId).revision();
        if (revision.backgroundAssetKey() == null
                || revision.backgroundContentType() == null
                || revision.backgroundSizeBytes() == null
                || revision.backgroundSha256() == null) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        return new WorkplaceService.FloorBackground(
                mediaStorage.load(tenantId, revision.backgroundAssetKey()),
                revision.backgroundContentType(), revision.backgroundSizeBytes(),
                revision.backgroundSha256());
    }

    WorkplaceService.FloorBackground floorBackground(
            Long tenantId, Long userId, String verifiedGroupRefs, UUID floorId) {
        WorkplaceCatalogRepository.FloorRow floor = catalog.floor(tenantId, floorId, false)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        runtimeGovernance.requireViewAccess(
                tenantId, userId, verifiedGroupRefs, floor.siteId());
        if (floor.backgroundAssetKey() == null || floor.backgroundContentType() == null
                || floor.backgroundSizeBytes() == null || floor.backgroundSha256() == null) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        return new WorkplaceService.FloorBackground(
                mediaStorage.load(tenantId, floor.backgroundAssetKey()),
                floor.backgroundContentType(), floor.backgroundSizeBytes(),
                floor.backgroundSha256());
    }

    WorkplaceDtos.Resource saveResource(
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
                    resourceId == null
                            ? "workplace.resource.created" : "workplace.resource.updated",
                    "RESOURCE", saved.resourceId(), correlationId,
                    Map.of("code", saved.code(), "type", saved.type().name()));
            return resource(saved, floor.siteId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The resource code or placement is invalid or already in use.");
        }
    }

    List<WorkplaceDtos.Resource> updateLayout(
            Long tenantId,
            Long actorId,
            UUID floorId,
            String locale,
            String correlationId,
            WorkplaceDtos.LayoutRequest request) {
        throw conflict(
                "Direct floor-plan layout updates are retired. "
                        + "All placements must be changed through a governed draft revision.");
    }

    WorkplaceDtos.Policy policy(Long tenantId) {
        return policy(catalog.policy(tenantId));
    }

    WorkplaceDtos.Policy updatePolicy(
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
        WorkplaceCatalogRepository.PolicyRow saved = catalog.updatePolicy(
                tenantId, actorId, request);
        if (saved == null) throw stale();
        bookings.audit(tenantId, actorId, "workplace.policy.updated", "POLICY",
                null, correlationId, Map.of("version", saved.version()));
        return policy(saved);
    }

    WorkplaceDtos.AdminOverview adminOverview(Long tenantId) {
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
                * 5L * Duration.between(
                        policy.workingDayStart(), policy.workingDayEnd()).toMinutes();
        int utilization = availableMinutes == 0 ? 0 : (int) Math.min(100,
                Math.round(bookings.occupiedMinutes(tenantId, from, to)
                        * 100.0 / availableMinutes));
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

    private void validateTimeZone(String value) {
        try {
            ZoneId.of(value == null ? "" : value.trim());
        } catch (DateTimeException exception) {
            throw invalid("A valid IANA time zone is required.");
        }
    }

    private void requireWriteMode(UUID id, Long version, String resourceName) {
        if (id == null && version != null) {
            throw invalid("A new Workplace " + resourceName + " cannot include a version.");
        }
        if (id != null && version == null) {
            throw invalid("Updating a Workplace " + resourceName + " requires its version.");
        }
    }

    private WorkplaceDtos.Site site(WorkplaceCatalogRepository.SiteRow value) {
        return new WorkplaceDtos.Site(
                value.siteId(), value.campusId(), value.code(), value.name(), value.nameKo(),
                value.nameEn(), value.type(), value.address(), value.timeZone(),
                value.totalFloorCount(), value.configuredFloorCount(), value.resourceCount(),
                value.state(), value.version());
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
                value.assignedUserId(), value.assignedPersonPublicId(), value.assignedDisplayName(),
                value.version());
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

    private WorkplaceSpatialGovernanceDtos.FloorPlanPlacementRequest placementRequest(
            WorkplaceSpatialGovernanceDtos.FloorPlanPlacement placement) {
        return new WorkplaceSpatialGovernanceDtos.FloorPlanPlacementRequest(
                placement.resourceId(), placement.resourceVersion(), placement.zoneId(),
                placement.sectionId(), placement.positionX(), placement.positionY(),
                placement.widthPercent(), placement.heightPercent(),
                placement.rotationDegrees(), placement.metadata());
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
}
