package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.WorkplaceExperienceFacilitiesDtos.CreateClosure;
import static com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactRepository.*;

@Service
class WorkplaceFacilityClosureImpactService {
    private static final int MAXIMUM_IMPACTED_BOOKINGS = 1000;
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(15);

    private final WorkplaceFacilityClosureImpactRepository repository;
    private final WorkplaceExperienceFacilitiesRepository facilities;
    private final WorkplaceBookingRepository bookings;
    private final WorkplaceCatalogRepository catalog;
    private final WorkplaceFacilityClosureNotificationEvents notificationEvents;
    private final DomainEventOutboxRepository domainOutbox;
    private final boolean notificationTransportConfigured;

    WorkplaceFacilityClosureImpactService(
            WorkplaceFacilityClosureImpactRepository repository,
            WorkplaceExperienceFacilitiesRepository facilities,
            WorkplaceBookingRepository bookings,
            WorkplaceCatalogRepository catalog,
            WorkplaceFacilityClosureNotificationEvents notificationEvents,
            DomainEventOutboxRepository domainOutbox,
            @Value("${dwp.events.transport-enabled:false}") boolean notificationTransportConfigured) {
        this.repository = repository;
        this.facilities = facilities;
        this.bookings = bookings;
        this.catalog = catalog;
        this.notificationEvents = notificationEvents;
        this.domainOutbox = domainOutbox;
        this.notificationTransportConfigured = notificationTransportConfigured;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    ImpactPreview preview(long tenant, long actor, UUID site, UUID resource, String idempotencyKey,
                          CreateImpactPreview input, String permissions) {
        validatePeriod(input.startsAt(), input.endsAt());
        String key = key(idempotencyKey);
        String fingerprint = hash(resource + "|" + input.startsAt().toInstant() + "|"
                + input.endsAt().toInstant() + "|" + input.resourceVersion());
        repository.lockKey(tenant, actor, "workplace-facility-impact-preview", key);
        SavedPreview replay = repository.previewReplay(tenant, actor, key).orElse(null);
        if (replay != null) {
            if (!fingerprint.equals(replay.fingerprint())) throw conflict("The preview key was used for different input.");
            return preview(tenant, site, replay.previewId());
        }
        var target = target(tenant, site, resource, false);
        requireOwnerPermission(target, permissions, false);
        if (target.version() != input.resourceVersion()) throw conflict("The resource changed. Refresh before previewing closure impact.");
        OffsetDateTime now = repository.now();
        if (!input.endsAt().isAfter(now)) throw invalid("A closure impact preview must include a current or future period.");
        List<SourceBooking> affected = repository.affectedBookings(tenant, target,
                input.startsAt().isBefore(now) ? now : input.startsAt(), input.endsAt());
        if (affected.size() > MAXIMUM_IMPACTED_BOOKINGS) {
            throw invalid("The closure affects more than 1000 bookings. Narrow the period and execute bounded batches.");
        }
        int recipients = (int) affected.stream().flatMap(row -> row.recipientUserIds().stream()).distinct().count();
        String confirmationToken = UUID.randomUUID().toString();
        UUID id;
        try {
            id = repository.insertPreview(tenant, actor, site, resource, owner(target),
                    input.startsAt(), input.endsAt(), target.version(), confirmationToken,
                    affected.size(), recipients, key, fingerprint, now.plus(PREVIEW_TTL));
            for (SourceBooking booking : affected) {
                repository.insertItem(tenant, id, booking, repository.candidates(tenant, target, booking));
            }
        } catch (DataIntegrityViolationException collision) {
            throw conflict("Closure impact changed while the preview was being saved. Request a fresh preview.");
        }
        return preview(tenant, site, id);
    }

    @Transactional(readOnly = true)
    ImpactPreview preview(long tenant, UUID site, UUID previewId) {
        PreviewRow row = repository.previewRow(tenant, site, previewId, false)
                .orElseThrow(() -> notFound("Closure impact preview"));
        return toPreview(row, repository.items(tenant, previewId));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    ClosureCommand execute(long tenant, long actor, UUID site, UUID previewId, String idempotencyKey,
                           ExecuteImpactCommand input, String correlationId, String permissions) {
        requireConfirmed(input.confirmed(), input.reason());
        String key = key(idempotencyKey);
        String correlation = correlation(correlationId, key);
        String fingerprint = commandFingerprint(previewId, input);
        repository.lockKey(tenant, actor, "workplace-facility-impact-command", key);
        SavedCommand replay = repository.commandReplay(tenant, actor, key).orElse(null);
        if (replay != null) {
            if (!fingerprint.equals(replay.fingerprint())) throw conflict("The command key was used for different input.");
            return command(tenant, site, replay.commandId());
        }
        PreviewRow preview = repository.previewRow(tenant, site, previewId, true)
                .orElseThrow(() -> notFound("Closure impact preview"));
        if (preview.actorUserId() != actor) throw forbidden("Only the actor who created the preview may execute it.");
        if (!preview.expiresAt().isAfter(repository.now())) throw conflict("The closure impact preview expired. Create a fresh preview.");
        if (preview.version() != input.expectedPreviewVersion()) throw conflict("The preview changed. Refresh it before execution.");
        if (!preview.confirmationToken().equals(input.confirmationToken())) throw invalid("The preview confirmation token is invalid.");
        var target = target(tenant, site, preview.resourceId(), false);
        requireOwnerPermission(target, permissions, true);
        if (target.version() != preview.resourceVersion()) throw conflict("The resource changed after preview. Create a fresh preview.");

        List<ImpactItem> items = repository.items(tenant, previewId);
        Map<UUID, ImpactItem> byId = items.stream().collect(Collectors.toMap(ImpactItem::previewItemId, Function.identity()));
        validateSelections(input.selections(), byId);
        Map<UUID, ReplacementCandidate> replacements = new LinkedHashMap<>();
        Set<UUID> workplaceLocks = new LinkedHashSet<>();
        Set<UUID> calendarLocks = new LinkedHashSet<>();
        workplaceLocks.add(preview.resourceId());
        if (target.calendarResourceId() != null) calendarLocks.add(target.calendarResourceId());
        for (ImpactSelection selection : input.selections()) {
            ImpactItem item = byId.get(selection.previewItemId());
            if (selection.action() != ImpactAction.REPLACE) continue;
            ReplacementCandidate candidate = item.replacementCandidates().stream()
                    .filter(value -> value.workplaceResourceId().equals(selection.replacementResourceId()))
                    .findFirst().orElseThrow(() -> invalid("A replacement must be selected from the saved preview candidates."));
            if (selection.expectedReplacementResourceVersion() == null
                    || candidate.resourceVersion() != selection.expectedReplacementResourceVersion()) {
                throw conflict("A replacement resource version is missing or stale.");
            }
            replacements.put(item.previewItemId(), candidate);
            workplaceLocks.add(candidate.workplaceResourceId());
            if (item.reservationOwner() == ReservationOwner.CALENDAR) calendarLocks.add(candidate.ownerResourceId());
        }
        repository.lockResources(tenant, calendarLocks, workplaceLocks);
        var lockedTarget = target(tenant, site, preview.resourceId(), true);
        if (lockedTarget.version() != preview.resourceVersion()) throw conflict("The resource changed after preview. Create a fresh preview.");
        for (ReplacementCandidate candidate : replacements.values()) {
            var current = target(tenant, site, candidate.workplaceResourceId(), true);
            UUID expectedCalendar = candidate.ownerResourceId().equals(candidate.workplaceResourceId())
                    ? null : candidate.ownerResourceId();
            if (current.version() != candidate.resourceVersion()
                    || !java.util.Objects.equals(current.calendarResourceId(), expectedCalendar)) {
                throw conflict("A replacement resource changed after preview. Create a fresh preview.");
            }
        }

        UUID commandId = repository.insertCommand(tenant, actor, site, preview.resourceId(), previewId,
                input.expectedPreviewVersion(), input.reason().trim(), key, fingerprint, correlation);
        for (ImpactSelection selection : input.selections()) {
            ImpactItem item = byId.get(selection.previewItemId());
            ReplacementCandidate candidate = replacements.get(item.previewItemId());
            repository.insertCommandItem(tenant, commandId, item, selection,
                    candidate == null ? null : candidate.ownerResourceId());
        }

        UUID closureId;
        try {
            closureId = facilities.createClosure(tenant, actor, preview.resourceId(),
                    "closure-impact-" + commandId, hash(preview.resourceId() + "|" + preview.startsAt().toInstant()
                            + "|" + preview.endsAt().toInstant() + "|" + preview.resourceVersion()
                            + "|" + input.reason().trim()),
                    new CreateClosure(preview.startsAt(), preview.endsAt(), preview.resourceVersion(),
                            input.reason().trim(), true));
        } catch (DataIntegrityViolationException collision) {
            throw conflict("A closure was created concurrently. Refresh the impact preview.");
        }

        int kept = 0;
        int cancelled = 0;
        int replaced = 0;
        try {
            for (ImpactSelection selection : input.selections()) {
                ImpactItem item = byId.get(selection.previewItemId());
                ReplacementCandidate candidate = replacements.get(item.previewItemId());
                repository.apply(tenant, actor, commandId, item, selection,
                        candidate == null ? null : candidate.ownerResourceId(), input.reason().trim(), correlation);
                switch (selection.action()) {
                    case KEEP -> kept++;
                    case CANCEL -> cancelled++;
                    case REPLACE -> replaced++;
                }
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("commandId", commandId);
                evidence.put("closureId", closureId);
                evidence.put("bookingOwner", item.reservationOwner());
                evidence.put("bookingId", item.bookingId());
                evidence.put("action", selection.action());
                evidence.put("sourceResourceId", item.sourceWorkplaceResourceId());
                if (selection.replacementResourceId() != null) evidence.put("replacementResourceId", selection.replacementResourceId());
                bookings.audit(tenant, actor, "workplace.facility.closure_booking_impact_executed",
                        "FACILITY_CLOSURE_COMMAND", commandId, correlation, evidence);
            }
        } catch (StaleBookingException stale) {
            throw conflict("A booking changed after preview. No closure impact was committed; create a fresh preview.");
        } catch (DataIntegrityViolationException collision) {
            throw conflict("A replacement is no longer available. No closure impact was committed; create a fresh preview.");
        }

        Set<Long> recipients = repository.recipients(items);
        recipients.remove(actor);
        repository.completeCommand(tenant, commandId, closureId, kept, cancelled, replaced, recipients.size());
        repository.commandEvent(tenant, commandId, actor, "COMMAND_SUCCEEDED", repository.write(Map.of(
                "closureId", closureId, "keptCount", kept, "cancelledCount", cancelled,
                "replacedCount", replaced, "notificationRecipientCount", recipients.size())), correlation);
        bookings.audit(tenant, actor, "workplace.facility.closure_impact_executed",
                "FACILITY_CLOSURE_COMMAND", commandId, correlation, Map.of(
                        "closureId", closureId, "resourceId", preview.resourceId(),
                        "existingBookingsMutated", cancelled + replaced > 0,
                        "notificationScheduled", !recipients.isEmpty(),
                        "keptCount", kept, "cancelledCount", cancelled, "replacedCount", replaced));

        String resourceName = catalog.resource(tenant, preview.resourceId(), false)
                .map(WorkplaceCatalogRepository.ResourceRow::name).orElse("Workplace resource");
        long sequence = 1;
        Map<UUID, ImpactSelection> selectionByItem = input.selections().stream()
                .collect(Collectors.toMap(ImpactSelection::previewItemId, Function.identity()));
        for (ImpactItem item : items) {
            ImpactSelection selection = selectionByItem.get(item.previewItemId());
            for (Long recipient : item.recipientUserIds().stream().filter(id -> id != actor).distinct().toList()) {
                UUID eventId = notificationEvents.recipientImpacted(tenant, actor, recipient, commandId,
                        closureId, preview.resourceId(), resourceName, item.bookingId(), selection.action().name(),
                        selection.replacementResourceId(), item.startsAt().toString(), item.endsAt().toString(),
                        sequence++, correlation);
                repository.notificationEvent(tenant, commandId, recipient, item.bookingId(), selection.action(), eventId);
            }
        }
        return command(tenant, site, commandId);
    }

    @Transactional(readOnly = true)
    ClosureCommand command(long tenant, UUID site, UUID commandId) {
        CommandRow row = repository.commandRow(tenant, site, commandId)
                .orElseThrow(() -> notFound("Facility closure command"));
        return toCommand(tenant, row);
    }

    @Transactional(readOnly = true)
    CommandReceipt receipt(long tenant, UUID site, UUID commandId) {
        ClosureCommand command = command(tenant, site, commandId);
        NotificationDelivery delivery = command.notifications();
        return new CommandReceipt(command, "WORKPLACE_WITH_CALENDAR_ROOM_OWNER",
                command.cancelledCount() + command.replacedCount() > 0,
                delivery.recipientCount() > 0,
                delivery.eventCount() > 0 && delivery.publishedCount() == delivery.eventCount(),
                false,
                repository.audit(tenant, commandId));
    }

    @Transactional
    ClosureCommand reconcile(long tenant, long actor, UUID site, UUID commandId, String idempotencyKey,
                             ReconcileNotifications input) {
        requireConfirmed(input.confirmed(), input.reason());
        String key = key(idempotencyKey);
        String fingerprint = notificationOperationFingerprint("RECONCILE", commandId, input);
        repository.lockKey(tenant, actor, "workplace-facility-notification-operation", key);
        SavedOperation replay = repository.operationReplay(tenant, actor, key).orElse(null);
        if (replay != null) {
            if (!replay.commandId().equals(commandId) || !replay.fingerprint().equals(fingerprint)) {
                throw conflict("The notification operation key was used for different input.");
            }
            return command(tenant, site, commandId);
        }
        CommandRow command = repository.commandRow(tenant, site, commandId)
                .orElseThrow(() -> notFound("Facility closure command"));
        if (command.version() != input.expectedCommandVersion()) throw conflict("The command changed. Refresh before reconciliation.");
        int reconciled = repository.reconcileExpired(tenant, commandId);
        try {
            repository.completeNotificationOperation(tenant, actor, commandId, "RECONCILE",
                    input.expectedCommandVersion(), key, fingerprint, reconciled);
        } catch (StaleCommandException stale) {
            throw conflict("The command changed. Refresh before reconciliation.");
        }
        repository.commandEvent(tenant, commandId, actor, "NOTIFICATIONS_RECONCILED",
                repository.write(Map.of("expiredUnknownReleased", reconciled, "reason", input.reason().trim())),
                command.correlationId());
        return command(tenant, site, commandId);
    }

    @Transactional
    ClosureCommand retry(long tenant, long actor, UUID site, UUID commandId, String idempotencyKey,
                         ReconcileNotifications input) {
        requireConfirmed(input.confirmed(), input.reason());
        String key = key(idempotencyKey);
        String fingerprint = notificationOperationFingerprint("RETRY", commandId, input);
        repository.lockKey(tenant, actor, "workplace-facility-notification-operation", key);
        SavedOperation replay = repository.operationReplay(tenant, actor, key).orElse(null);
        if (replay != null) {
            if (!replay.commandId().equals(commandId) || !replay.fingerprint().equals(fingerprint)) {
                throw conflict("The notification operation key was used for different input.");
            }
            return command(tenant, site, commandId);
        }
        CommandRow command = repository.commandRow(tenant, site, commandId)
                .orElseThrow(() -> notFound("Facility closure command"));
        if (command.version() != input.expectedCommandVersion()) throw conflict("The command changed. Refresh before retrying notifications.");
        int retried = repository.expediteFailed(tenant, commandId);
        for (UUID eventId : repository.deadEvents(tenant, commandId)) {
            if (domainOutbox.replayDead(eventId, "user:" + actor, input.reason().trim())) retried++;
        }
        try {
            repository.completeNotificationOperation(tenant, actor, commandId, "RETRY",
                    input.expectedCommandVersion(), key, fingerprint, retried);
        } catch (StaleCommandException stale) {
            throw conflict("The command changed. Refresh before retrying notifications.");
        }
        repository.commandEvent(tenant, commandId, actor, "NOTIFICATIONS_RETRY_REQUESTED",
                repository.write(Map.of("retryCount", retried, "reason", input.reason().trim())),
                command.correlationId());
        return command(tenant, site, commandId);
    }

    private ImpactPreview toPreview(PreviewRow row, List<ImpactItem> items) {
        return new ImpactPreview(row.previewId(), row.resourceId(), row.siteId(), row.owner(), row.startsAt(),
                row.endsAt(), row.resourceVersion(), row.version(), row.confirmationToken(),
                row.affectedBookingCount(), row.affectedRecipientCount(), row.expiresAt(), row.createdAt(), items);
    }

    private ClosureCommand toCommand(long tenant, CommandRow row) {
        NotificationCounts counts = repository.notificationCounts(tenant, row.commandId());
        NotificationState state;
        if (counts.total() == 0) state = NotificationState.NOT_REQUIRED;
        else if (!notificationTransportConfigured) state = NotificationState.NOT_CONFIGURED;
        else if (counts.unknown() > 0) state = NotificationState.RESULT_UNKNOWN;
        else if (counts.dead() > 0) state = NotificationState.DEAD;
        else if (counts.retry() > 0) state = NotificationState.RETRY_SCHEDULED;
        else if (counts.sending() > 0) state = NotificationState.SENDING;
        else if (counts.pending() > 0) state = NotificationState.PENDING;
        else state = NotificationState.PUBLISHED;
        NotificationDelivery delivery = new NotificationDelivery(counts.recipients(), counts.total(), state, counts.pending(),
                counts.retry(), counts.sending(), counts.published(), counts.unknown(), counts.dead(),
                notificationTransportConfigured, counts.unknown() > 0 || counts.dead() > 0, repository.now());
        return new ClosureCommand(row.commandId(), row.previewId(), row.closureId(), row.resourceId(), row.siteId(),
                row.state(), row.expectedPreviewVersion(), row.reason(), row.keptCount(), row.cancelledCount(),
                row.replacedCount(), row.version(), row.createdAt(), row.completedAt(), delivery,
                repository.commandItems(tenant, row.commandId()));
    }

    private WorkplaceExperienceFacilitiesRepository.Target target(long tenant, UUID site, UUID resource, boolean lock) {
        return facilities.target(tenant, site, resource, lock).orElseThrow(() -> notFound("Resource"));
    }

    private static ReservationOwner owner(WorkplaceExperienceFacilitiesRepository.Target target) {
        return "ROOM".equals(target.type()) ? ReservationOwner.CALENDAR : ReservationOwner.WORKPLACE;
    }

    private static void requireOwnerPermission(WorkplaceExperienceFacilitiesRepository.Target target,
                                               String permissions, boolean mutation) {
        WorkplaceExperienceFacilitiesPermissions.requirePermission(permissions,
                "ADMIN.WORKPLACE:UPDATE");
        if ("ROOM".equals(target.type())) WorkplaceExperienceFacilitiesPermissions.requirePermission(
                permissions, mutation ? "ADMIN.ROOMS:UPDATE" : "ADMIN.ROOMS:VIEW");
        if ("ROOM".equals(target.type()) && target.calendarResourceId() == null) {
            throw invalid("The room has no canonical Calendar resource mapping.");
        }
    }

    private static void validateSelections(List<ImpactSelection> selections, Map<UUID, ImpactItem> items) {
        if (selections == null || selections.size() != items.size()) {
            throw invalid("Choose exactly one action for every booking in the preview.");
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (ImpactSelection selection : selections) {
            if (selection == null || selection.previewItemId() == null || selection.action() == null
                    || selection.expectedBookingVersion() == null || !ids.add(selection.previewItemId())) {
                throw invalid("Each preview booking requires one complete, unique action.");
            }
            ImpactItem item = items.get(selection.previewItemId());
            if (item == null || item.bookingVersion() != selection.expectedBookingVersion()) {
                throw conflict("A booking version is missing or stale.");
            }
            if (selection.action() == ImpactAction.REPLACE) {
                if (selection.replacementResourceId() == null || selection.expectedReplacementResourceVersion() == null) {
                    throw invalid("Replacement actions require a candidate resource and its saved version.");
                }
            } else if (selection.replacementResourceId() != null
                    || selection.expectedReplacementResourceVersion() != null) {
                throw invalid("Only replacement actions may specify a replacement resource.");
            }
        }
        if (!ids.equals(items.keySet())) throw invalid("Choose exactly one action for every booking in the preview.");
    }

    private static void validatePeriod(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !to.isAfter(from)
                || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0) {
            throw invalid("Choose a positive closure period of at most 366 elapsed days.");
        }
    }

    private static void requireConfirmed(boolean confirmed, String reason) {
        if (!confirmed || reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw invalid("Explicitly confirm the command and provide a reason of at most 500 characters.");
        }
    }

    private static String commandFingerprint(UUID preview, ExecuteImpactCommand input) {
        List<ImpactSelection> sorted = new ArrayList<>(input.selections() == null ? List.of() : input.selections());
        sorted.sort(Comparator.comparing(value -> String.valueOf(value.previewItemId())));
        StringBuilder value = new StringBuilder(preview.toString()).append('|')
                .append(input.expectedPreviewVersion()).append('|').append(input.confirmationToken()).append('|')
                .append(input.reason() == null ? "" : input.reason().trim()).append('|').append(input.confirmed());
        sorted.forEach(item -> value.append('|').append(item.previewItemId()).append(':').append(item.action())
                .append(':').append(item.expectedBookingVersion()).append(':').append(item.replacementResourceId())
                .append(':').append(item.expectedReplacementResourceVersion()));
        return hash(value.toString());
    }

    private static String notificationOperationFingerprint(String operation, UUID command,
                                                            ReconcileNotifications input) {
        return hash(operation + '|' + command + '|' + input.expectedCommandVersion() + '|'
                + (input.reason() == null ? "" : input.reason().trim()) + '|' + input.confirmed());
    }

    private static String key(String value) {
        if (value == null || !value.matches("[!-~]{1,160}")) {
            throw invalid("An opaque Idempotency-Key of 1–160 ASCII characters is required.");
        }
        return value;
    }

    private static String correlation(String value, String key) {
        String resolved = value == null || value.isBlank() ? "workplace-facility:" + key : value.trim();
        if (resolved.length() > 160) throw invalid("Correlation id must be at most 160 characters.");
        return resolved;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
    private static BaseException conflict(String message) { return new BaseException(ErrorCode.RESOURCE_CONFLICT, message); }
    private static BaseException forbidden(String message) { return new BaseException(ErrorCode.FORBIDDEN, message); }
    private static BaseException notFound(String type) { return new BaseException(ErrorCode.NOT_FOUND, type + " not found in the authorized scope."); }
}
