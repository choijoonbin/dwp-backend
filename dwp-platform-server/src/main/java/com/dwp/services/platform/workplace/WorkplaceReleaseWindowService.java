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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.BookingMode;
import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceState;

@Service
class WorkplaceReleaseWindowService {

    private final WorkplaceCatalogRepository catalog;
    private final WorkplaceReleaseWindowRepository releases;
    private final WorkplaceBookingRepository audit;
    private final WorkplaceRuntimeGovernance runtimeGovernance;

    WorkplaceReleaseWindowService(
            WorkplaceCatalogRepository catalog,
            WorkplaceReleaseWindowRepository releases,
            WorkplaceBookingRepository audit,
            WorkplaceRuntimeGovernance runtimeGovernance) {
        this.catalog = catalog;
        this.releases = releases;
        this.audit = audit;
        this.runtimeGovernance = runtimeGovernance;
    }

    @Transactional(readOnly = true)
    List<WorkplaceReleaseWindowDtos.AssignedResource> assignedResources(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String locale,
            String verifiedGroupRefs) {
        WorkplaceCatalogRepository.PolicyRow basePolicy = catalog.policy(tenantId);
        return releases.assignedResources(
                        tenantId, userId, personPublicId, korean(locale)).stream()
                .filter(value -> canBook(
                        tenantId, userId, verifiedGroupRefs, value.siteId()))
                .filter(value -> lendingEnabled(tenantId, value.resourceId(), basePolicy))
                .toList();
    }

    @Transactional(readOnly = true)
    List<WorkplaceReleaseWindowDtos.ReleaseWindow> ownedWindows(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale) {
        validRange(from, to, Duration.ofDays(366));
        OffsetDateTime now = OffsetDateTime.now();
        return releases.ownedWindows(
                        tenantId, userId, personPublicId, from, to, korean(locale))
                .stream().map(value -> dto(value, now)).toList();
    }

    @Transactional
    WorkplaceReleaseWindowDtos.ReleaseWindow create(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String locale,
            String correlationId,
            String idempotencyKey,
            String verifiedGroupRefs,
            WorkplaceReleaseWindowDtos.CreateRequest request) {
        String key = normalizeIdempotencyKey(idempotencyKey);
        String fingerprint = fingerprint(request);
        if (key != null) {
            releases.lockUserReleaseScope(tenantId, userId);
            WorkplaceReleaseWindowRepository.IdempotencyRow existing = releases
                    .idempotency(tenantId, userId, key).orElse(null);
            if (existing != null) {
                if (!fingerprint.equals(existing.requestFingerprint())) {
                    throw conflict("The idempotency key was already used with a different request.");
                }
                return dto(releases.ownedWindow(
                        tenantId,
                        userId,
                        personPublicId,
                        existing.releaseWindowId(),
                        korean(locale)).orElseThrow(() -> conflict(
                                "The release-window idempotency state is unavailable.")),
                        OffsetDateTime.now());
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        WorkplaceCatalogRepository.ResourceRow resource = catalog
                .resource(tenantId, request.resourceId(), korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        audit.lockResourceBookingScope(tenantId, resource.resourceId());
        WorkplaceCatalogRepository.FloorRow floor = catalog
                .floor(tenantId, resource.floorId(), korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        runtimeGovernance.requireBookAccess(
                tenantId, userId, verifiedGroupRefs, floor.siteId());
        WorkplaceCatalogRepository.PolicyRow basePolicy = catalog.policy(tenantId);
        WorkplaceCatalogRepository.PolicyRow resolved = runtimeGovernance.effectivePolicy(
                tenantId,
                WorkplaceSpatialGovernanceDtos.PolicyScopeType.RESOURCE,
                resource.resourceId(),
                basePolicy);
        WorkplaceCatalogRepository.PolicyRow policy = resolved == null ? basePolicy : resolved;
        if (!policy.allowAssignedDeskLending()) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Assigned workspace lending is disabled by Workplace policy.");
        }
        validRange(request.startsAt(), request.endsAt(),
                Duration.ofDays(policy.bookingWindowDays()));
        if (!request.startsAt().isAfter(now)) {
            throw invalid("A release window must start in the future.");
        }
        if (request.startsAt().isAfter(now.plusDays(policy.bookingWindowDays()))) {
            throw invalid("The release window is outside the booking horizon.");
        }
        boolean owner = userId.equals(resource.assignedUserId())
                || (personPublicId != null
                    && personPublicId.equals(resource.assignedPersonPublicId()));
        if (resource.mode() != BookingMode.ASSIGNED || !owner) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Only the verified assignee can release this workspace.");
        }
        if (resource.state() != ResourceState.AVAILABLE) {
            throw invalid("The assigned workspace is not available for lending.");
        }
        try {
            WorkplaceReleaseWindowRepository.ReleaseWindowRow created = releases.create(
                    tenantId,
                    userId,
                    personPublicId,
                    request,
                    key,
                    key == null ? null : fingerprint,
                    policy.bookingRetentionDays(),
                    korean(locale));
            audit.audit(
                    tenantId,
                    userId,
                    "workplace.assigned-resource.released",
                    "RELEASE_WINDOW",
                    created.releaseWindowId(),
                    correlationId,
                    Map.of(
                            "resourceId", created.resourceId(),
                            "startsAt", created.startsAt(),
                            "endsAt", created.endsAt()));
            return dto(created, now);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This workspace already has an overlapping release window.",
                    exception);
        }
    }

    @Transactional
    WorkplaceReleaseWindowDtos.ReleaseWindow cancel(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID releaseWindowId,
            String locale,
            String correlationId,
            WorkplaceDtos.VersionRequest request) {
        WorkplaceReleaseWindowRepository.ReleaseWindowRow current = releases
                .ownedWindow(
                        tenantId,
                        userId,
                        personPublicId,
                        releaseWindowId,
                        korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        audit.lockResourceBookingScope(tenantId, current.resourceId());
        UUID lockedResourceId = releases.lockWindowForUpdate(tenantId, releaseWindowId);
        if (!current.resourceId().equals(lockedResourceId)) {
            throw conflict("The release window resource changed unexpectedly.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (releases.cancel(
                tenantId,
                userId,
                personPublicId,
                releaseWindowId,
                request.version(),
                now) == 0) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The release window changed or already has an active reservation.");
        }
        audit.audit(
                tenantId,
                userId,
                "workplace.assigned-resource.release-cancelled",
                "RELEASE_WINDOW",
                releaseWindowId,
                correlationId,
                Map.of("resourceId", current.resourceId(), "cancelledAt", now));
        return dto(releases.ownedWindow(
                        tenantId,
                        userId,
                        personPublicId,
                        releaseWindowId,
                        korean(locale)).orElseThrow(), now);
    }

    private WorkplaceReleaseWindowDtos.ReleaseWindow dto(
            WorkplaceReleaseWindowRepository.ReleaseWindowRow value,
            OffsetDateTime now) {
        String status = "ACTIVE".equals(value.status()) && !value.endsAt().isAfter(now)
                ? "EXPIRED" : value.status();
        boolean canCancel = "ACTIVE".equals(value.status()) && value.endsAt().isAfter(now);
        return new WorkplaceReleaseWindowDtos.ReleaseWindow(
                value.releaseWindowId(), value.resourceId(), value.resourceName(),
                value.siteName(), value.floorName(), value.startsAt(), value.endsAt(),
                value.note(), status, canCancel, value.version());
    }

    private boolean canBook(
            Long tenantId, Long userId, String verifiedGroupRefs, UUID siteId) {
        try {
            runtimeGovernance.requireBookAccess(
                    tenantId, userId, verifiedGroupRefs, siteId);
            return true;
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.FORBIDDEN) return false;
            throw exception;
        }
    }

    private boolean lendingEnabled(
            Long tenantId,
            UUID resourceId,
            WorkplaceCatalogRepository.PolicyRow basePolicy) {
        WorkplaceCatalogRepository.PolicyRow resolved = runtimeGovernance.effectivePolicy(
                tenantId,
                WorkplaceSpatialGovernanceDtos.PolicyScopeType.RESOURCE,
                resourceId,
                basePolicy);
        return (resolved == null ? basePolicy : resolved).allowAssignedDeskLending();
    }

    private void validRange(OffsetDateTime from, OffsetDateTime to, Duration maximum) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw invalid("A valid release-window range is required.");
        }
        if (Duration.between(from, to).compareTo(maximum) > 0) {
            throw invalid("The release-window range is too large.");
        }
    }

    private boolean korean(String locale) {
        return locale != null && locale.toLowerCase().startsWith("ko");
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) return null;
        String key = value.trim();
        if (key.length() > 160 || key.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw invalid("Idempotency-Key must contain 1 to 160 visible ASCII characters.");
        }
        return key;
    }

    private String fingerprint(WorkplaceReleaseWindowDtos.CreateRequest request) {
        String note = request.note() == null || request.note().isBlank()
                ? null : request.note().trim();
        String canonical = request.resourceId() + "\n"
                + request.startsAt().toInstant() + "\n"
                + request.endsAt().toInstant() + "\n"
                + String.valueOf(note);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
