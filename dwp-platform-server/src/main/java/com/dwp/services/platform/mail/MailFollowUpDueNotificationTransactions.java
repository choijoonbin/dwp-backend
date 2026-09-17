package com.dwp.services.platform.mail;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
class MailFollowUpDueNotificationTransactions {

    private final MailFollowUpDueNotificationRepository repository;
    private final MailNotificationEvents events;

    MailFollowUpDueNotificationTransactions(
            MailFollowUpDueNotificationRepository repository,
            MailNotificationEvents events) {
        this.repository = repository;
        this.events = events;
    }

    @Transactional
    BatchResult publishDue(OffsetDateTime now, int batchSize) {
        if (now == null || batchSize < 1) {
            throw new IllegalArgumentException("A positive follow-up notification batch is required.");
        }
        int emitted = 0;
        int suppressed = 0;
        var claimed = repository.claimDue(now, batchSize);
        for (var followUp : claimed) {
            UUID eventId = events.followUpDue(followUp).orElse(null);
            repository.complete(followUp, eventId, now);
            if (eventId == null) suppressed++;
            else emitted++;
        }
        return new BatchResult(claimed.size(), emitted, suppressed);
    }

    record BatchResult(int claimed, int emitted, int suppressed) { }
}
