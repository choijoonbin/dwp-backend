package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailFollowUpDueNotificationTransactionsTest {

    @Test
    void completesEveryClaimAsEmittedOrPreferenceSuppressedInTheSameBatch() {
        MailFollowUpDueNotificationRepository repository =
                mock(MailFollowUpDueNotificationRepository.class);
        MailNotificationEvents events = mock(MailNotificationEvents.class);
        MailFollowUpDueNotificationTransactions transactions =
                new MailFollowUpDueNotificationTransactions(repository, events);
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T10:00:00Z");
        var emitted = due(41L, 0L, "2026-09-17T09:00:00Z");
        var suppressed = due(42L, 3L, "2026-09-17T09:30:00Z");
        UUID eventId = UUID.randomUUID();
        when(repository.claimDue(now, 25)).thenReturn(List.of(emitted, suppressed));
        when(events.followUpDue(emitted)).thenReturn(Optional.of(eventId));
        when(events.followUpDue(suppressed)).thenReturn(Optional.empty());

        var result = transactions.publishDue(now, 25);

        assertThat(result).isEqualTo(
                new MailFollowUpDueNotificationTransactions.BatchResult(2, 1, 1));
        InOrder ordered = inOrder(repository, events);
        ordered.verify(repository).claimDue(now, 25);
        ordered.verify(events).followUpDue(emitted);
        ordered.verify(repository).complete(emitted, eventId, now);
        ordered.verify(events).followUpDue(suppressed);
        ordered.verify(repository).complete(suppressed, null, now);
    }

    @Test
    void rejectsAnInvalidBatchBeforeClaiming() {
        var transactions = new MailFollowUpDueNotificationTransactions(
                mock(MailFollowUpDueNotificationRepository.class),
                mock(MailNotificationEvents.class));

        assertThatThrownBy(() -> transactions.publishDue(null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    private MailFollowUpDueNotificationRepository.DueFollowUp due(
            long userId, long version, String expectedAt) {
        return new MailFollowUpDueNotificationRepository.DueFollowUp(
                7L, UUID.randomUUID(), userId, UUID.randomUUID(), version,
                OffsetDateTime.parse(expectedAt));
    }
}
