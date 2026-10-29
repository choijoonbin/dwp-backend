package com.dwp.services.platform.calendar;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
class CalendarReminderTransactions {

    private final CalendarReminderRepository repository;
    private final CalendarReminderNotificationEvents events;

    CalendarReminderTransactions(
            CalendarReminderRepository repository,
            CalendarReminderNotificationEvents events) {
        this.repository = repository;
        this.events = events;
    }

    @Transactional
    BatchResult publishDue(OffsetDateTime now, int batchSize) {
        if (now == null || batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("A bounded Calendar reminder batch is required.");
        }
        int emitted = 0;
        int duplicates = 0;
        var claimed = repository.claimDue(now, batchSize);
        for (var reminder : claimed) {
            CalendarReminderSchedule.Occurrence next = CalendarReminderSchedule.next(
                    reminder.schedule(), reminder.offsetMinutes(),
                    reminder.dueAt().plusNanos(1));
            if (!repository.beginDelivery(reminder, now)) {
                repository.advance(reminder, next, now);
                duplicates++;
                continue;
            }
            UUID eventId = events.reminderDue(reminder);
            repository.completeDelivery(reminder, eventId, now);
            repository.advance(reminder, next, now);
            emitted++;
        }
        return new BatchResult(claimed.size(), emitted, duplicates);
    }

    record BatchResult(int claimed, int emitted, int duplicates) {
    }
}
