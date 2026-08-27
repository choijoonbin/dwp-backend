package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarCollaborationMapper.*;

@Service
public class CalendarCollaborationService {

    private static final Set<String> SHARE_ACCESS_LEVELS = Set.of(
            "VIEW_FREE_BUSY", "VIEW_DETAILS", "EDIT", "MANAGE");

    private final CalendarCollaborationRepository collaboration;
    private final CalendarRepository calendar;
    private final CalendarRetentionRepository retention;

    public CalendarCollaborationService(
            CalendarCollaborationRepository collaboration,
            CalendarRepository calendar,
            CalendarRetentionRepository retention) {
        this.collaboration = collaboration;
        this.calendar = calendar;
        this.retention = retention;
    }

    @Transactional(readOnly = true)
    public List<CalendarDtos.CalendarShare> listShares(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID calendarId) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        CalendarCollaborationRepository.AccessDecision access = requireManage(
                decision(tenantId, actorId, actorPersonPublicId, verifiedGroupRefs, calendarId));
        return collaboration.shares(tenantId, access.calendarId()).stream()
                .map(CalendarCollaborationMapper::share)
                .toList();
    }

    @Transactional(readOnly = true)
    public CalendarDtos.CalendarCapabilities calendarCapabilities(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID calendarId) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        CalendarCollaborationRepository.AccessDecision access = requireAccessible(
                decision(tenantId, actorId, actorPersonPublicId, verifiedGroupRefs, calendarId));
        return CalendarCollaborationMapper.calendarCapabilities(access);
    }

    @Transactional
    public CalendarDtos.CalendarShare upsertPersonShare(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID calendarId,
            UUID sharedPersonPublicId,
            String correlationId,
            CalendarDtos.CalendarShareRequest request) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        lockCalendar(tenantId, calendarId);
        CalendarCollaborationRepository.AccessDecision access = requireManage(
                decision(tenantId, actorId, actorPersonPublicId, verifiedGroupRefs, calendarId));

        if (!request.principalPersonPublicId().equals(sharedPersonPublicId)) {
            throw invalid("The shared person does not match the request path.");
        }
        if (sharedPersonPublicId.equals(actorPersonPublicId)
                || sharedPersonPublicId.equals(access.ownerPersonPublicId())) {
            throw invalid("Calendar ownership cannot be replaced by a share.");
        }
        if (!calendar.knownPersonPublicIds(tenantId, List.of(sharedPersonPublicId))
                .contains(sharedPersonPublicId)) {
            throw notFound();
        }

        String displayName = request.principalDisplayName().trim();
        String accessLevel = request.accessLevel().name().toUpperCase(Locale.ROOT);
        if (!SHARE_ACCESS_LEVELS.contains(accessLevel)) {
            throw invalid("The calendar share access level is invalid.");
        }
        boolean canViewPrivate = request.canViewPrivate();
        if (canViewPrivate && !"OWNER".equals(access.accessLevel())) {
            throw forbidden();
        }
        if (canViewPrivate && "VIEW_FREE_BUSY".equals(accessLevel)) {
            throw invalid("Free/busy access cannot include private event details.");
        }
        OffsetDateTime validUntil = request.validUntil();
        if (validUntil != null && !validUntil.isAfter(OffsetDateTime.now())) {
            throw invalid("The calendar share expiry must be in the future.");
        }

        CalendarCollaborationRepository.ShareRow before = collaboration
                .shares(tenantId, calendarId).stream()
                .filter(row -> sharedPersonPublicId.equals(row.principalPersonPublicId()))
                .findFirst()
                .orElse(null);
        if (request.version() == null) {
            throw invalid("The calendar share version is required.");
        }
        long expectedVersion = request.version();
        if ((before == null && expectedVersion != 0L)
                || (before != null && before.version() != expectedVersion)) {
            throw conflict("The calendar share changed. Refresh and try again.");
        }

        CalendarCollaborationRepository.ShareRow saved = collaboration.upsertPersonShare(
                        tenantId,
                        actorId,
                        calendarId,
                        sharedPersonPublicId,
                        displayName,
                        accessLevel,
                        canViewPrivate,
                        validUntil,
                        expectedVersion)
                .orElseThrow(() -> conflict(
                        "The calendar share could not be saved."));
        calendar.audit(
                tenantId,
                actorId,
                null,
                "calendar.share.upserted",
                correlationId,
                shareSnapshot(calendarId, before),
                shareSnapshot(calendarId, saved));
        return share(saved);
    }

    @Transactional
    public void revokeShare(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID calendarId,
            UUID grantId,
            long version,
            String correlationId) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        lockCalendar(tenantId, calendarId);
        requireManage(decision(
                tenantId, actorId, actorPersonPublicId, verifiedGroupRefs, calendarId));
        CalendarCollaborationRepository.ShareRow before = collaboration
                .shares(tenantId, calendarId).stream()
                .filter(row -> row.grantId().equals(grantId))
                .findFirst()
                .orElseThrow(this::notFound);
        if (before.version() != version
                || collaboration.revokeShare(
                tenantId, actorId, calendarId, grantId, version) == 0) {
            throw conflict("The calendar share changed. Refresh and try again.");
        }
        calendar.audit(
                tenantId,
                actorId,
                null,
                "calendar.share.revoked",
                correlationId,
                shareSnapshot(calendarId, before),
                snapshot(
                        "calendarId", calendarId,
                        "grantId", grantId,
                        "lifecycleState", "REVOKED",
                        "version", version + 1));
    }

    @Transactional
    public CalendarDtos.CalendarSubscriptionResponse updateSubscription(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID calendarId,
            String correlationId,
            CalendarDtos.CalendarSubscriptionRequest request) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        lockCalendar(tenantId, calendarId);
        CalendarCollaborationRepository.AccessDecision access = requireAccessible(
                decision(tenantId, actorId, actorPersonPublicId, verifiedGroupRefs, calendarId));
        boolean selected = request.selected();
        boolean favorite = request.favorite();
        int displayOrder = request.displayOrder();
        if (displayOrder < 0 || displayOrder > 10_000) {
            throw invalid("The calendar display order is invalid.");
        }
        String colorOverride = request.colorOverride();
        if (colorOverride != null && !colorOverride.matches("^#[0-9A-Fa-f]{6}$")) {
            throw invalid("The calendar color override is invalid.");
        }
        if ("REQUIRED".equals(access.subscriptionPolicy()) && !selected) {
            throw invalid("A required company calendar cannot be deselected.");
        }
        long version = request.version();
        OptionalVersion current = subscriptionVersion(
                tenantId, actorPersonPublicId, calendarId);
        requireExpectedVersion(current, version, "The calendar subscription changed.");

        CalendarCollaborationRepository.SubscriptionRow saved = collaboration
                .upsertSubscription(
                        tenantId,
                        actorPersonPublicId,
                        calendarId,
                        selected,
                        favorite,
                        displayOrder,
                        colorOverride,
                        version)
                .orElseThrow(() -> conflict(
                        "The calendar subscription changed. Refresh and try again."));
        calendar.audit(
                tenantId,
                actorId,
                null,
                "calendar.subscription.updated",
                correlationId,
                snapshot(
                        "calendarId", calendarId,
                        "version", current.value()),
                snapshot(
                        "calendarId", calendarId,
                        "selected", saved.selected(),
                        "favorite", saved.favorite(),
                        "displayOrder", saved.displayOrder(),
                        "colorOverride", saved.colorOverride(),
                        "version", saved.version()));
        return subscription(saved);
    }

    @Transactional(readOnly = true)
    public CalendarDtos.EventCapabilities eventCapabilities(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID eventId) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        CalendarCollaborationRepository.EventDecision event = collaboration
                .eventDecision(
                        tenantId,
                        actorId,
                        actorPersonPublicId,
                        groups(verifiedGroupRefs),
                        eventId)
                .orElseThrow(this::notFound);
        return CalendarCollaborationMapper.eventCapabilities(event);
    }

    @Transactional
    public CalendarDtos.EventPreferenceResponse updateEventPreference(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID eventId,
            String correlationId,
            CalendarDtos.EventPreferenceRequest request) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        UUID calendarId = lockEventCalendar(tenantId, eventId);
        CalendarCollaborationRepository.EventDecision event = eventDecision(
                tenantId, actorId, actorPersonPublicId, verifiedGroupRefs, eventId);
        if (!calendarId.equals(event.calendarId())
                || event.deletedAt() != null
                || "CANCELLED".equals(event.status())) {
            throw notFound();
        }
        boolean starred = request.starred();
        boolean hidden = request.hidden();
        long version = request.version();
        OptionalVersion current = eventPreferenceVersion(
                tenantId, actorPersonPublicId, eventId);
        requireExpectedVersion(current, version, "The event preference changed.");

        CalendarCollaborationRepository.EventPreferenceRow saved = collaboration
                .upsertEventPreference(
                        tenantId,
                        actorPersonPublicId,
                        eventId,
                        starred,
                        hidden,
                        version)
                .orElseThrow(() -> conflict(
                        "The event preference changed. Refresh and try again."));
        calendar.audit(
                tenantId,
                actorId,
                eventId,
                "calendar.event.preference.updated",
                correlationId,
                snapshot("version", current.value()),
                snapshot(
                        "starred", saved.starred(),
                        "hidden", saved.hidden(),
                        "version", saved.version()));
        return eventPreference(saved);
    }

    @Transactional
    public CalendarDtos.EventCapabilities trashEvent(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID eventId,
            String correlationId,
            CalendarDtos.TrashEventRequest request) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        UUID calendarId = lockEventCalendar(tenantId, eventId);
        CalendarCollaborationRepository.EventDecision event = requireEventManager(
                eventDecision(
                        tenantId, actorId, actorPersonPublicId,
                        verifiedGroupRefs, eventId));
        long version = request.version();
        String reason = request.reason() == null || request.reason().isBlank()
                ? "User requested deletion" : request.reason().trim();
        if (!calendarId.equals(event.calendarId())
                || event.deletedAt() != null
                || "CANCELLED".equals(event.status())) {
            throw conflict("The event is not available for deletion.");
        }
        if (event.version() != version) {
            throw conflict("The event changed. Refresh and try again.");
        }

        CalendarCollaborationRepository.EventMutation saved = collaboration
                .trashEvent(tenantId, actorId, eventId, reason, version)
                .orElseThrow(() -> conflict(
                        "The event changed. Refresh and try again."));
        retention.recordTombstone(tenantId, eventId);
        calendar.cancelBookings(tenantId, actorId, eventId);
        calendar.audit(
                tenantId,
                actorId,
                eventId,
                "calendar.event.trashed",
                correlationId,
                snapshot(
                        "deletedAt", event.deletedAt(),
                        "version", event.version()),
                snapshot(
                        "deletedAt", saved.deletedAt(),
                        "purgeAfter", saved.purgeAfter(),
                        "legalHold", saved.legalHold(),
                        "reason", reason,
                        "version", saved.version()));
        return CalendarCollaborationMapper.eventCapabilities(eventAfter(event, saved));
    }

    @Transactional
    public CalendarDtos.EventCapabilities restoreEvent(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID eventId,
            String correlationId,
            CalendarDtos.VersionRequest request) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        UUID calendarId = lockEventCalendar(tenantId, eventId);
        CalendarCollaborationRepository.EventDecision event = requireEventManager(
                eventDecision(
                        tenantId, actorId, actorPersonPublicId,
                        verifiedGroupRefs, eventId));
        long version = request.version();
        if (!calendarId.equals(event.calendarId()) || event.deletedAt() == null) {
            throw conflict("The event is not available for restoration.");
        }
        if (event.version() != version) {
            throw conflict("The event changed. Refresh and try again.");
        }

        CalendarCollaborationRepository.EventMutation saved = collaboration
                .restoreEvent(tenantId, actorId, eventId, version)
                .orElseThrow(() -> conflict(
                        "The event retention window expired or the event changed."));
        retention.removeTombstone(tenantId, eventId);
        calendar.audit(
                tenantId,
                actorId,
                eventId,
                "calendar.event.restored",
                correlationId,
                snapshot(
                        "deletedAt", event.deletedAt(),
                        "purgeAfter", event.purgeAfter(),
                        "legalHold", event.legalHold(),
                        "version", event.version()),
                snapshot(
                        "deletedAt", saved.deletedAt(),
                        "resourceBookingsRestored", false,
                        "version", saved.version()));
        return CalendarCollaborationMapper.eventCapabilities(eventAfter(event, saved));
    }

    private CalendarCollaborationRepository.AccessDecision decision(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID calendarId) {
        return collaboration.accessDecision(
                        tenantId,
                        actorId,
                        actorPersonPublicId,
                        groups(verifiedGroupRefs),
                        calendarId)
                .orElseThrow(this::notFound);
    }

    private CalendarCollaborationRepository.EventDecision eventDecision(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID eventId) {
        return collaboration.eventDecisionForUpdate(
                        tenantId,
                        actorId,
                        actorPersonPublicId,
                        groups(verifiedGroupRefs),
                        eventId)
                .orElseThrow(this::notFound);
    }

    private CalendarCollaborationRepository.AccessDecision requireAccessible(
            CalendarCollaborationRepository.AccessDecision access) {
        if (!access.accessible()) throw notFound();
        return access;
    }

    private CalendarCollaborationRepository.AccessDecision requireManage(
            CalendarCollaborationRepository.AccessDecision access) {
        if (!access.canManage()) throw forbidden();
        return access;
    }

    private CalendarCollaborationRepository.EventDecision requireEventManager(
            CalendarCollaborationRepository.EventDecision event) {
        if (!event.canManage()) throw forbidden();
        return event;
    }

    private void requireActor(
            Long tenantId, Long actorId, UUID actorPersonPublicId) {
        if (tenantId == null || tenantId < 1
                || actorId == null || actorId < 1
                || actorPersonPublicId == null
                || !collaboration.verifiedActor(
                tenantId, actorId, actorPersonPublicId)) {
            throw forbidden();
        }
    }

    private void lockCalendar(Long tenantId, UUID calendarId) {
        if (!collaboration.lockCalendar(tenantId, calendarId)) throw notFound();
    }

    private UUID lockEventCalendar(Long tenantId, UUID eventId) {
        UUID calendarId = collaboration.eventCalendarId(tenantId, eventId)
                .orElseThrow(this::notFound);
        lockCalendar(tenantId, calendarId);
        return calendarId;
    }

    private OptionalVersion subscriptionVersion(
            Long tenantId, UUID personPublicId, UUID calendarId) {
        return collaboration.subscriptionForUpdate(tenantId, personPublicId, calendarId)
                .map(value -> new OptionalVersion(true, value.version()))
                .orElseGet(() -> new OptionalVersion(false, 0));
    }

    private OptionalVersion eventPreferenceVersion(
            Long tenantId, UUID personPublicId, UUID eventId) {
        return collaboration.eventPreferenceForUpdate(tenantId, personPublicId, eventId)
                .map(value -> new OptionalVersion(true, value.version()))
                .orElseGet(() -> new OptionalVersion(false, 0));
    }

    private void requireExpectedVersion(
            OptionalVersion current, long expected, String message) {
        if ((!current.present() && expected != 0)
                || (current.present() && current.value() != expected)) {
            throw conflict(message + " Refresh and try again.");
        }
    }

    private UUID[] groups(String header) {
        return CalendarVerifiedGroups.databaseArray(header);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException forbidden() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "The current member cannot manage this calendar resource.");
    }

    private BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private record OptionalVersion(boolean present, long value) {
    }
}
