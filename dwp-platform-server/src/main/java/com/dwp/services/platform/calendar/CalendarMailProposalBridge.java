package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.mail.MailProposalHandoffBinding;
import com.dwp.services.platform.mail.MailProposalOutcomePort;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Keeps the optional Mail proposal handoff outside Calendar's event transaction orchestrator. */
final class CalendarMailProposalBridge {

    private final MailProposalOutcomePort outcomes;

    CalendarMailProposalBridge(MailProposalOutcomePort outcomes) {
        this.outcomes = outcomes;
    }

    void validate(long tenantId, long actorId, MailProposalHandoffBinding binding) {
        if (binding == null) return;
        if (outcomes == null) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The Mail proposal owner service is unavailable.");
        }
        outcomes.validate(tenantId, actorId, MailProposalOutcomePort.Owner.CALENDAR, binding);
    }

    void validateNew(
            long tenantId,
            long actorId,
            MailProposalHandoffBinding binding,
            CalendarDtos.CreateEventRequest request) {
        if (binding == null) return;
        outcomes.validateNewExecution(
                tenantId, actorId, MailProposalOutcomePort.Owner.CALENDAR, binding,
                new MailProposalOutcomePort.OwnerMutation(null, payload(request)));
    }

    void complete(
            long tenantId,
            long actorId,
            UUID eventId,
            String correlationId,
            MailProposalHandoffBinding binding) {
        if (binding == null) return;
        outcomes.executed(
                tenantId, actorId, MailProposalOutcomePort.Owner.CALENDAR, binding,
                "calendar-event:" + eventId, correlationId);
    }

    private Map<String, Object> payload(CalendarDtos.CreateEventRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", request.title());
        payload.put("description", request.description());
        payload.put("type", request.type().name());
        payload.put("startsAt", request.startsAt().toInstant().toString());
        payload.put("endsAt", request.endsAt().toInstant().toString());
        payload.put("durationMinutes", Duration.between(
                request.startsAt(), request.endsAt()).toMinutes());
        payload.put("timeZone", request.timeZone());
        payload.put("allDay", request.allDay());
        payload.put("location", request.location());
        payload.put("conferenceUrl", request.conferenceUrl());
        payload.put("visibility", request.visibility().name());
        payload.put("recurrence", request.recurrence().name());
        payload.put("recurrenceInterval", request.recurrenceInterval());
        payload.put("recurrenceUntil", request.recurrenceUntil() == null
                ? null : request.recurrenceUntil().toString());
        payload.put("responseRequired", request.responseRequired());
        payload.put("attendees", request.attendees().stream()
                .map(CalendarDtos.AttendeeInput::email)
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .toList());
        payload.put("resourceId", request.resourceId() == null
                ? null : request.resourceId().toString());
        payload.put("calendarId", request.calendarId() == null
                ? null : request.calendarId().toString());
        payload.put("importance", request.importance() == null
                ? null : request.importance().name());
        return payload;
    }
}
