package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarReminderDtos.*;
import static com.dwp.services.platform.calendar.CalendarTypes.CalendarAccessLevel;
import static com.dwp.services.platform.calendar.CalendarTypes.EventDetailLevel;
import static com.dwp.services.platform.calendar.CalendarTypes.EventStatus;

@Service
public class CalendarReminderService {

    private final CalendarReminderRepository reminders;
    private final CalendarRepository calendar;
    private final boolean emailEnabled;
    private final Clock clock;

    @Autowired
    public CalendarReminderService(
            CalendarReminderRepository reminders,
            CalendarRepository calendar,
            @Value("${dwp.platform.calendar.reminders.email-enabled:false}")
                    boolean emailEnabled) {
        this(reminders, calendar, emailEnabled, Clock.systemUTC());
    }

    CalendarReminderService(
            CalendarReminderRepository reminders,
            CalendarRepository calendar,
            boolean emailEnabled,
            Clock clock) {
        this.reminders = reminders;
        this.calendar = calendar;
        this.emailEnabled = emailEnabled;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReminderSet reminders(CalendarReminderAccess.Actor actor, UUID eventId) {
        CalendarRepository.EventRow event = editableEvent(actor, eventId);
        return response(actor, event, reminders.reminderSet(
                actor.tenantId(), eventId, actor.userId(), actor.personPublicId()).orElse(null));
    }

    @Transactional
    public ReminderSet replace(
            CalendarReminderAccess.Actor actor,
            UUID eventId,
            String correlationId,
            ReplaceRemindersRequest request) {
        List<ReminderInput> definitions = validate(request);
        CalendarRepository.EventRow observed = editableEvent(actor, eventId);
        CalendarReminderRepository.EventLock locked = reminders.lockEvent(
                        actor.tenantId(), eventId)
                .orElseThrow(this::notFound);
        if (locked.version() != observed.version()) throw conflict();
        if (EventStatus.CANCELLED.name().equals(locked.status())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "Cancelled Calendar events cannot have active reminders.");
        }
        CalendarRepository.EventRow event = editableEvent(actor, eventId);
        if (event.version() != locked.version()) throw conflict();

        long currentVersion = reminders.lockSetVersion(
                actor.tenantId(), eventId, actor.userId(), actor.personPublicId()).orElse(0L);
        if (request.version() == null || request.version() != currentVersion) throw conflict();
        int beforeCount = currentVersion == 0 ? 0 : reminders.definitions(
                actor.tenantId(), eventId, actor.userId(), actor.personPublicId(),
                currentVersion).size();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        long nextVersion = Math.addExact(currentVersion, 1L);
        if (currentVersion == 0) {
            reminders.insertSet(
                    actor.tenantId(), eventId, actor.userId(), actor.personPublicId(),
                    event.version(), now);
        } else {
            reminders.updateSet(
                    actor.tenantId(), eventId, actor.userId(), actor.personPublicId(),
                    event.version(), currentVersion, now);
            reminders.deleteDefinitions(
                    actor.tenantId(), eventId, actor.userId(), actor.personPublicId());
        }
        for (ReminderInput definition : definitions) {
            CalendarReminderSchedule.Occurrence next = CalendarReminderSchedule.next(
                    event, definition.offsetMinutes(), now);
            reminders.insertDefinition(
                    UUID.randomUUID(), actor.tenantId(), eventId,
                    actor.userId(), actor.personPublicId(), nextVersion,
                    definition.offsetMinutes(), definition.channel(), next, now);
        }
        reminders.auditReplacement(
                actor, eventId, correlationId, currentVersion, nextVersion,
                beforeCount, definitions.size());
        return response(actor, event, reminders.reminderSet(
                actor.tenantId(), eventId, actor.userId(), actor.personPublicId())
                .orElseThrow(() -> new IllegalStateException(
                        "The Calendar reminder set was not persisted.")));
    }

    List<ReminderInput> validate(ReplaceRemindersRequest request) {
        if (request == null || request.version() == null || request.version() < 0
                || request.reminders() == null || request.reminders().size() > 5) {
            throw invalid();
        }
        Set<String> unique = new HashSet<>();
        for (ReminderInput definition : request.reminders()) {
            if (definition == null || definition.offsetMinutes() == null
                    || definition.offsetMinutes() < 0 || definition.offsetMinutes() > 40320
                    || definition.channel() == null
                    || !unique.add(definition.channel().name() + ':'
                        + definition.offsetMinutes())) {
                throw invalid();
            }
            if (definition.channel() == ReminderChannel.EMAIL && !emailEnabled) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "Calendar email reminders are not enabled for delivery.");
            }
        }
        return request.reminders().stream()
                .sorted(Comparator.comparingInt(ReminderInput::offsetMinutes)
                        .thenComparing(value -> value.channel().name()))
                .toList();
    }

    private CalendarRepository.EventRow editableEvent(
            CalendarReminderAccess.Actor actor,
            UUID eventId) {
        if (actor == null || eventId == null || !reminders.identityMatches(
                actor.tenantId(), actor.userId(), actor.personPublicId())) {
            throw CalendarReminderAccess.denied();
        }
        CalendarRepository.EventRow event = CalendarRepositoryRouting.event(
                        calendar, actor.tenantId(), actor.userId(), actor.personPublicId(),
                        actor.groupRefs(), eventId, false)
                .orElseThrow(this::notFound);
        if (!canManagePersonalReminder(event)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "This Calendar event does not expose reminder-safe details.");
        }
        return event;
    }

    private boolean canManagePersonalReminder(CalendarRepository.EventRow event) {
        return event.detailLevel() == EventDetailLevel.FULL
                && event.accessLevel() != CalendarAccessLevel.VIEW_FREE_BUSY
                && event.accessLevel() != CalendarAccessLevel.NONE;
    }

    private ReminderSet response(
            CalendarReminderAccess.Actor actor,
            CalendarRepository.EventRow event,
            CalendarReminderRepository.ReminderSetRow set) {
        if (set == null) {
            return new ReminderSet(
                    event.eventId(), event.version(), 0,
                    ReminderSetState.ACTIVE, List.of(), null);
        }
        ReminderSetState state = "ACTIVE".equals(set.state())
                && set.eventVersion() == event.version()
                && event.status() != EventStatus.CANCELLED
                ? ReminderSetState.ACTIVE : ReminderSetState.INVALIDATED;
        List<ReminderDefinition> values = reminders.definitions(
                        actor.tenantId(), event.eventId(), actor.userId(),
                        actor.personPublicId(), set.version()).stream()
                .map(value -> new ReminderDefinition(
                        value.reminderId(), value.offsetMinutes(), value.channel(),
                        value.nextOccurrenceStartsAt(), value.nextDueAt()))
                .toList();
        return new ReminderSet(
                event.eventId(), event.version(), set.version(), state,
                values, set.updatedAt());
    }

    private BaseException invalid() {
        return new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "Use at most five unique Calendar reminders with offsets from 0 to 40320 minutes.");
    }

    private BaseException conflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The Calendar event or reminder set changed. Refresh and try again.");
    }

    private BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND);
    }
}
