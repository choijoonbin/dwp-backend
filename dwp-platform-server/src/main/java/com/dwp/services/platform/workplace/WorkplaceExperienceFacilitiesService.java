package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.WorkplaceExperienceFacilitiesDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceExperienceFacilitiesRepository.Target;

@Service
class WorkplaceExperienceFacilitiesService {
    private final WorkplaceExperienceFacilitiesRepository repository;
    private final WorkplaceBookingRepository bookings;
    private final WorkplaceCatalogRepository catalog;
    private final WorkplaceRuntimeGovernance governance;
    private final WorkplaceService workplace;

    WorkplaceExperienceFacilitiesService(WorkplaceExperienceFacilitiesRepository repository,
                                         WorkplaceBookingRepository bookings, WorkplaceCatalogRepository catalog,
                                         WorkplaceRuntimeGovernance governance, WorkplaceService workplace) {
        this.repository = repository;
        this.bookings = bookings;
        this.catalog = catalog;
        this.governance = governance;
        this.workplace = workplace;
    }

    @Transactional(readOnly = true)
    ClosurePage closures(Long tenant, UUID site, UUID floor, UUID resource, OffsetDateTime from,
                         OffsetDateTime to, boolean cancelled, int page, int size) {
        validatePage(page, size);
        validateRange(from, to);
        requireSiteFloor(tenant, site, floor);
        if (resource != null) target(tenant, site, resource, false);
        return repository.closures(tenant, site, floor, resource, from, to, cancelled, page, size);
    }

    @Transactional(readOnly = true)
    Closure closure(Long tenant, UUID site, UUID id) {
        return repository.closure(tenant, site, id).orElseThrow(() -> notFound("Facility closure"));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    RoomBookingImpact roomImpact(Long tenant, UUID site, UUID resource, OffsetDateTime from, OffsetDateTime to,
                                 int page, int size, String permissions) {
        validatePage(page, size);
        validateRange(from, to);
        Target target = target(tenant, site, resource, false);
        if (!"ROOM".equals(target.type())) throw invalid("This resource is not owned by Rooms.");
        WorkplaceExperienceFacilitiesPermissions.requirePermission(permissions, "ADMIN.ROOMS:VIEW");
        if (target.calendarResourceId() == null) throw invalid("The room has no canonical Calendar resource mapping.");
        return repository.roomImpact(tenant, target, from, to, page, size);
    }

    @Transactional
    Closure createClosure(Long tenant, Long actor, UUID site, UUID resource, String idempotencyKey,
                           CreateClosure request, String correlation, String permissions) {
        requireConfirmed(request.confirmed(), request.reason());
        validateRange(request.startsAt(), request.endsAt());
        if (!request.endsAt().isAfter(repository.generatedAt())) throw invalid("A closure must include a current or future period.");
        String key = key(idempotencyKey);
        String fingerprint = fingerprint(resource + "|" + request.startsAt().toInstant() + "|"
                + request.endsAt().toInstant() + "|" + request.version() + "|" + request.reason().trim());
        repository.lockRequestKey(tenant, actor, key);
        Target target = lockClosureTarget(tenant, site, resource, permissions);
        var replay = repository.closureReplay(tenant, site, actor, key).orElse(null);
        if (replay != null) {
            if (!fingerprint.equals(replay.fingerprint())) throw conflict("The closure key was used for different input.");
            return replay.closure();
        }
        if (request.version() == null || request.version() != target.version()) throw conflict("The resource changed. Refresh its version before closing it.");
        UUID id;
        try { id = repository.createClosure(tenant, actor, resource, key, fingerprint, request); }
        catch (DataIntegrityViolationException collision) { throw conflict("The closure command conflicts with saved input. Re-read the original target."); }
        bookings.audit(tenant, actor, "workplace.facility.closure_created", "FACILITY_CLOSURE", id, correlation,
                Map.of("resourceId", resource, "siteId", site, "startsAt", request.startsAt(), "endsAt", request.endsAt(),
                        "reason", request.reason().trim(), "version", 0, "existingBookingsMutated", false));
        return closure(tenant, site, id);
    }

    @Transactional
    Closure cancelClosure(Long tenant, Long actor, UUID site, UUID id, CancelClosure request, String correlation, String permissions) {
        requireConfirmed(request.confirmed(), request.reason());
        Closure original = closure(tenant, site, id);
        lockClosureTarget(tenant, site, original.resourceId(), permissions);
        if (request.version() == null || !repository.cancelClosure(tenant, actor, id, request)) {
            throw conflict("The closure changed or was already cancelled. Re-read the original closure.");
        }
        bookings.audit(tenant, actor, "workplace.facility.closure_cancelled", "FACILITY_CLOSURE", id, correlation,
                Map.of("resourceId", original.resourceId(), "siteId", site, "reason", request.reason().trim(),
                        "version", request.version() + 1));
        return closure(tenant, site, id);
    }

    private Target lockClosureTarget(Long tenant, UUID site, UUID resource, String permissions) {
        Target before = target(tenant, site, resource, false);
        if ("ROOM".equals(before.type())) {
            WorkplaceExperienceFacilitiesPermissions.requirePermission(permissions, "ADMIN.ROOMS:UPDATE");
            if (before.calendarResourceId() == null) throw invalid("The room has no canonical Calendar resource mapping.");
            repository.lockCalendar(tenant, before.calendarResourceId());
        }
        bookings.lockResourceBookingScope(tenant, resource);
        Target locked = target(tenant, site, resource, true);
        if (!java.util.Objects.equals(before.calendarResourceId(), locked.calendarResourceId())
                || !before.type().equals(locked.type())) throw conflict("The resource owner mapping changed. Refresh the resource.");
        return locked;
    }

    @Transactional
    FacilityRequest createRequest(Long tenant, Long actor, UUID resource, String idempotencyKey,
                                   String groups, CreateRequest request, String correlation) {
        Target target = target(tenant, null, resource, false);
        governance.requireViewAccess(tenant, actor, groups, target.siteId(), target.floorId());
        if (request.category() == null || request.description() == null || request.description().isBlank()
                || request.description().trim().length() > 2000) throw invalid("Choose a category and provide a description of at most 2000 characters.");
        String key = key(idempotencyKey);
        String fingerprint = fingerprint(resource + "|" + request.category() + "|" + request.description().trim());
        repository.lockRequestKey(tenant, actor, key);
        var replay = repository.requestReplay(tenant, target.siteId(), actor, key).orElse(null);
        if (replay != null) {
            if (!fingerprint.equals(replay.fingerprint())) throw conflict("The facility request key was used for different input.");
            return replay.request();
        }
        UUID id;
        try { id = repository.createRequest(tenant, actor, resource, key, fingerprint, request); }
        catch (DataIntegrityViolationException collision) { throw conflict("The request key conflicts with a saved command. Re-read your original request."); }
        bookings.audit(tenant, actor, "workplace.facility.request_created", "FACILITY_REQUEST", id, correlation,
                Map.of("resourceId", resource, "category", request.category(), "status", "OPEN"));
        return repository.ownRequest(tenant, actor, Set.of(target.siteId()), id).orElseThrow(() -> notFound("Facility request"));
    }

    @Transactional(readOnly = true)
    RequestPage ownRequests(Long tenant, Long actor, String groups, int page, int size) {
        validatePage(page, size);
        return repository.ownRequestsForFloors(tenant, actor, viewableFloors(tenant, actor, groups), page, size);
    }

    @Transactional(readOnly = true)
    FacilityRequest ownRequest(Long tenant, Long actor, String groups, UUID id) {
        return repository.ownRequestForFloors(tenant, actor, viewableFloors(tenant, actor, groups), id)
                .orElseThrow(() -> notFound("Facility request"));
    }

    @Transactional(readOnly = true)
    RequestPage adminRequests(Long tenant, UUID site, UUID floor, RequestStatus status, int page, int size) {
        validatePage(page, size);
        requireSiteFloor(tenant, site, floor);
        return repository.adminRequests(tenant, site, floor, status, page, size);
    }

    @Transactional
    FacilityRequest changeRequestStatus(Long tenant, Long actor, UUID site, UUID id,
                                         ChangeRequestStatus request, String correlation) {
        requireConfirmed(request.confirmed(), request.reason());
        FacilityRequest original = repository.adminRequest(tenant, site, id, true)
                .orElseThrow(() -> notFound("Facility request"));
        if (request.version() == null || request.version() != original.version()) throw conflict("The facility request changed. Refresh its saved version.");
        boolean statusChanged = original.status() != request.status();
        boolean workOrderChanged = changesWorkOrder(original, request);
        if (statusChanged && !canTransition(original.status(), request.status())) throw invalid("This request status transition is not allowed.");
        if (!statusChanged && !workOrderChanged) throw invalid("Change the status or at least one work-order field.");
        if (!repository.updateRequest(tenant, actor, id, request)) throw conflict("The facility request changed. Re-read the original request.");
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("previousStatus", original.status());
        evidence.put("status", request.status());
        evidence.put("reason", request.reason().trim());
        evidence.put("resourceId", original.resourceId());
        evidence.put("version", request.version() + 1);
        if (request.priority() != null) evidence.put("priority", request.priority());
        addTextEvidence(evidence, "assignedTo", request.assignedTo());
        addTextEvidence(evidence, "serviceProvider", request.serviceProvider());
        addTextEvidence(evidence, "externalWorkOrderReference", request.externalWorkOrderReference());
        if (request.slaDueAt() != null) evidence.put("slaDueAt", request.slaDueAt());
        if (request.clearSla()) evidence.put("slaCleared", true);
        bookings.audit(tenant, actor, "workplace.facility.request_status_changed", "FACILITY_REQUEST", id, correlation,
                evidence);
        return repository.adminRequest(tenant, site, id, false).orElseThrow(() -> notFound("Facility request"));
    }

    @Transactional(readOnly = true)
    BookingAvailability bookingAvailability(Long tenant, Long actor, UUID person, String groups,
                                            UUID resource, OffsetDateTime from, OffsetDateTime to) {
        validateRange(from, to);
        Target target = target(tenant, null, resource, false);
        governance.requireViewAccess(tenant, actor, groups, target.siteId(), target.floorId());
        var row = catalog.resource(tenant, resource, false).orElseThrow(() -> notFound("Resource"));
        var floor = catalog.floor(tenant, target.floorId(), false).orElseThrow(() -> notFound("Floor"));
        var site = catalog.site(tenant, target.siteId(), false).orElseThrow(() -> notFound("Site"));
        var policy = workplace.resolveBookingPolicy(tenant, resource, catalog.policy(tenant));
        OffsetDateTime generated = repository.generatedAt();
        List<PublicClosure> closures = bookings.facilityClosures(tenant, target.floorId(), from, to).stream()
                .filter(c -> c.resourceId().equals(resource)).toList();
        String reason = null;
        try {
            workplace.validateBookable(tenant, row, actor, person, groups,
                    new WorkplaceDtos.BookingRequest(resource, from, to, null, false), site, floor, policy);
            if (bookings.resourceHasConflict(tenant, resource, from, to)) reason = "RESOURCE_RESERVED";
            else if (bookings.userHasConflict(tenant, actor, from, to)) reason = "OWN_RESERVATION_CONFLICT";
        } catch (BaseException blocked) {
            if (blocked.getErrorCode() == ErrorCode.FORBIDDEN) throw blocked;
            reason = !closures.isEmpty() ? "FACILITY_CLOSURE" : "BOOKING_POLICY_OR_STATE";
        }
        return new BookingAvailability(resource, target.siteId(), from, to, reason == null, reason,
                closures, generated, "ROOM".equals(target.type()) ? "ROOMS" : "WORKPLACE", false);
    }

    private Target target(Long tenant, UUID site, UUID resource, boolean lock) {
        return repository.target(tenant, site, resource, lock).orElseThrow(() -> notFound("Resource"));
    }
    private void requireSiteFloor(Long tenant, UUID site, UUID floor) {
        if (site == null || catalog.site(tenant, site, false).isEmpty()) throw notFound("Site");
        if (floor != null && !repository.floorExists(tenant, site, floor)) throw notFound("Floor");
    }
    private Set<UUID> viewableFloors(Long tenant, Long actor, String groups) {
        return governance.viewableFloorIds(tenant, actor, groups, catalog.floors(tenant, null, false).stream()
                .collect(java.util.stream.Collectors.toMap(WorkplaceCatalogRepository.FloorRow::floorId,
                        WorkplaceCatalogRepository.FloorRow::siteId)));
    }
    static boolean canTransition(RequestStatus before, RequestStatus after) {
        if (after == null || before == after) return false;
        return switch (before) {
            case OPEN -> after != RequestStatus.OPEN;
            case IN_PROGRESS -> after == RequestStatus.RESOLVED || after == RequestStatus.CANCELLED;
            case RESOLVED, CANCELLED -> after == RequestStatus.OPEN;
        };
    }
    static boolean changesWorkOrder(FacilityRequest before, ChangeRequestStatus after) {
        if (after.priority() != null && after.priority() != before.priority()) return true;
        if (after.assignedTo() != null && !Objects.equals(trimToNull(after.assignedTo()), before.assignedTo())) return true;
        if (after.serviceProvider() != null && !Objects.equals(trimToNull(after.serviceProvider()), before.serviceProvider())) return true;
        if (after.externalWorkOrderReference() != null
                && !Objects.equals(trimToNull(after.externalWorkOrderReference()), before.externalWorkOrderReference())) return true;
        if (after.clearSla()) return before.slaDueAt() != null;
        return after.slaDueAt() != null && !after.slaDueAt().equals(before.slaDueAt());
    }
    private static String trimToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
    private static void addTextEvidence(Map<String, Object> evidence, String key, String raw) {
        if (raw == null) return;
        String normalized = trimToNull(raw);
        if (normalized == null) evidence.put(key + "Cleared", true);
        else evidence.put(key, normalized);
    }
    static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid("Page must be nonnegative and size must be 1–100.");
    }
    static void validateRange(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0) {
            throw invalid("Choose a positive facility period of at most 366 elapsed days.");
        }
    }
    static void requireConfirmed(boolean confirmed, String reason) {
        if (!confirmed || reason == null || reason.isBlank() || reason.trim().length() > 500) throw invalid("Confirm the action and provide a reason of at most 500 characters.");
    }
    private static String key(String value) {
        if (value == null || !value.matches("[!-~]{1,160}")) throw invalid("An opaque Idempotency-Key of 1–160 ASCII characters is required.");
        return value;
    }
    private static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static BaseException notFound(String type) { return new BaseException(ErrorCode.NOT_FOUND, type + " not found in the authorized scope."); }
    private static BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
    private static BaseException conflict(String message) { return new BaseException(ErrorCode.RESOURCE_CONFLICT, message); }
}
