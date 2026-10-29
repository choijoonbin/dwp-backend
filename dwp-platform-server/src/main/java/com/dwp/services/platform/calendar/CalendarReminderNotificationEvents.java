package com.dwp.services.platform.calendar;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarReminderDtos.ReminderChannel;

@Component
class CalendarReminderNotificationEvents {

    static final String SOURCE = "urn:dwp:platform:calendar";
    static final String REMINDER_DUE = "calendar.event-reminder.due.v1";
    static final String IN_APP_TYPE = "CALENDAR.EVENT_REMINDER.IN_APP";
    static final String EMAIL_TYPE = "CALENDAR.EVENT_REMINDER.EMAIL";

    private final DomainEventRecorder recorder;
    private final ObjectMapper json;

    CalendarReminderNotificationEvents(
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            ObjectMapper json) {
        this.recorder = recorder;
        this.json = json;
        contracts.register(REMINDER_DUE, 1, 1);
    }

    UUID reminderDue(CalendarReminderRepository.DueReminder reminder) {
        if (reminder == null || reminder.tenantId() < 1 || reminder.ownerUserId() < 1
                || reminder.reminderId() == null || reminder.eventId() == null
                || reminder.occurrenceStartsAt() == null || reminder.dueAt() == null) {
            throw new IllegalArgumentException("Calendar reminder delivery evidence is required.");
        }
        String typeKey = reminder.channel() == ReminderChannel.EMAIL
                ? EMAIL_TYPE : IN_APP_TYPE;
        ObjectNode data = json.createObjectNode();
        ObjectNode intent = data.putArray("notificationIntents").addObject()
                .put("typeKey", typeKey)
                .put("threadKey", "calendar-event:" + reminder.eventId())
                .put("locale", "ko-KR")
                .put("reasonCode", "EVENT_REMINDER_DUE")
                .put("subjectReference", "calendar-event:" + reminder.eventId())
                .put("targetReference", "/calendar/schedule?eventId=" + reminder.eventId())
                .put("dueAt", reminder.dueAt().toInstant().toString())
                .put("actionRequired", false);
        intent.putArray("recipientUserIds").add(reminder.ownerUserId());
        intent.putArray("contexts").addObject()
                .put("kind", "WORK_ITEM")
                .put("key", "calendar-event:" + reminder.eventId())
                .put("matchable", true);
        intent.putObject("variables")
                .put("eventId", reminder.eventId().toString())
                .put("eventTitle", reminder.eventTitle())
                .put("occurrenceStartsAt", reminder.occurrenceStartsAt().toInstant().toString())
                .put("dueAt", reminder.dueAt().toInstant().toString())
                .put("offsetMinutes", Integer.toString(reminder.offsetMinutes()))
                .put("channel", reminder.channel().name())
                .put("timeZone", reminder.schedule().timeZone());

        long sequence = Math.addExact(reminder.deliverySequence(), 1L);
        UUID eventId = UUID.nameUUIDFromBytes(String.join("\u001f",
                SOURCE, REMINDER_DUE, Long.toString(reminder.tenantId()),
                reminder.reminderId().toString(), Long.toString(reminder.setVersion()),
                reminder.occurrenceStartsAt().toInstant().toString())
                .getBytes(StandardCharsets.UTF_8));
        return recorder.record(new DomainEventEnvelope(
                "1.0",
                eventId,
                SOURCE,
                REMINDER_DUE,
                1,
                reminder.dueAt().toInstant(),
                "CALENDAR_REMINDER/" + reminder.reminderId(),
                reminder.tenantId(),
                "CALENDAR_REMINDER",
                reminder.reminderId().toString(),
                sequence,
                "calendar-reminder:" + reminder.reminderId() + ':' + sequence,
                null,
                null,
                data,
                Map.of("deliveryChannel", reminder.channel().name())));
    }
}
