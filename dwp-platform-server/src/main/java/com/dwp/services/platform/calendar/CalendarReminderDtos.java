package com.dwp.services.platform.calendar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class CalendarReminderDtos {

    private CalendarReminderDtos() {
    }

    public enum ReminderChannel { IN_APP, EMAIL }

    public enum ReminderSetState { ACTIVE, INVALIDATED }

    public record ReminderInput(
            @NotNull @Min(0) @Max(40320) Integer offsetMinutes,
            @NotNull ReminderChannel channel) {
    }

    public record ReplaceRemindersRequest(
            @NotNull @Min(0) Long version,
            @NotNull @Size(max = 5) List<@Valid ReminderInput> reminders) {
    }

    public record ReminderDefinition(
            UUID reminderId,
            int offsetMinutes,
            ReminderChannel channel,
            OffsetDateTime nextOccurrenceStartsAt,
            OffsetDateTime nextDueAt) {
    }

    public record ReminderSet(
            UUID eventId,
            long eventVersion,
            long version,
            ReminderSetState state,
            List<ReminderDefinition> reminders,
            OffsetDateTime updatedAt) {
    }
}
