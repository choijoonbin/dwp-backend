package com.dwp.services.platform.mail;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MailNotificationEventsTest {

    private DomainEventRecorder recorder;
    private MailNotificationPreferenceRepository preferences;
    private DomainEventContractRegistry contracts;
    private MailNotificationEvents events;

    @BeforeEach
    void setUp() {
        recorder = mock(DomainEventRecorder.class);
        preferences = mock(MailNotificationPreferenceRepository.class);
        contracts = new DomainEventContractRegistry();
        when(recorder.record(any())).thenAnswer(invocation ->
                invocation.<DomainEventEnvelope>getArgument(0).id());
        events = new MailNotificationEvents(
                recorder, contracts, new ObjectMapper().findAndRegisterModules(), preferences);
    }

    @Test
    void newMailUsesThePersonalPreferenceAndPublishesOnlySafeReferences() {
        UUID threadId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-09-17T09:30:00Z");
        when(preferences.newMailTarget(7L, threadId, messageId))
                .thenReturn(Optional.of(
                        new MailNotificationPreferenceRepository.NewMailTarget(41L, occurredAt)));
        when(preferences.notifyNewMail(7L, 41L)).thenReturn(true);

        UUID eventId = events.newMailReceived(7L, threadId, messageId).orElseThrow();

        ArgumentCaptor<DomainEventEnvelope> captured =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder).record(captured.capture());
        DomainEventEnvelope envelope = captured.getValue();
        assertThat(envelope.id()).isEqualTo(eventId);
        assertThat(envelope.source()).isEqualTo(MailNotificationEvents.SOURCE);
        assertThat(envelope.type()).isEqualTo(MailNotificationEvents.NEW_MAIL_RECEIVED);
        assertThat(envelope.time()).isEqualTo(occurredAt.toInstant());
        assertThat(envelope.aggregateId()).isEqualTo(messageId.toString());
        assertThat(contracts.requireCompatible(envelope).maximumVersion()).isOne();
        JsonNode intent = envelope.data().path("notificationIntents").get(0);
        assertThat(intent.path("typeKey").asText())
                .isEqualTo(MailNotificationEvents.NEW_MAIL_TYPE);
        assertThat(intent.path("recipientUserIds").get(0).asLong()).isEqualTo(41L);
        assertThat(intent.path("targetReference").asText())
                .isEqualTo("/mail/inbox?thread=" + threadId);
        assertThat(intent.path("variables").size()).isEqualTo(2);
        assertThat(intent.path("variables").has("threadId")).isTrue();
        assertThat(intent.path("variables").has("messageId")).isTrue();
        assertThat(envelope.data().toString())
                .doesNotContain("senderEmail", "bodyContent", "recipients", "attachments");
    }

    @Test
    void disabledNewMailPreferenceSuppressesTheDomainEvent() {
        UUID threadId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(preferences.newMailTarget(7L, threadId, messageId))
                .thenReturn(Optional.of(new MailNotificationPreferenceRepository.NewMailTarget(
                        41L, OffsetDateTime.parse("2026-09-17T09:30:00Z"))));
        when(preferences.notifyNewMail(7L, 41L)).thenReturn(false);

        assertThat(events.newMailReceived(7L, threadId, messageId)).isEmpty();

        verify(recorder, never()).record(any());
    }

    @Test
    void selfAssignmentIsSuppressedBeforeAnyPreferenceOrThreadLookup() {
        assertThat(events.sharedInboxAssigned(
                7L, 41L, UUID.randomUUID(), 41L, 3L, "corr-self")).isEmpty();

        verifyNoInteractions(preferences, recorder);
    }

    @Test
    void sharedAssignmentRequiresTheCurrentFactAndRecipientPreference() {
        UUID threadId = UUID.randomUUID();
        UUID inboxId = UUID.randomUUID();
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-09-17T09:45:00Z");
        when(preferences.assignmentTarget(7L, threadId, 42L, 4L))
                .thenReturn(Optional.of(new MailNotificationPreferenceRepository.AssignmentTarget(
                        inboxId, occurredAt)));
        when(preferences.notifySharedAssignment(7L, 42L)).thenReturn(true);

        events.sharedInboxAssigned(7L, 41L, threadId, 42L, 4L, "corr-assign")
                .orElseThrow();

        ArgumentCaptor<DomainEventEnvelope> captured =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder).record(captured.capture());
        DomainEventEnvelope envelope = captured.getValue();
        assertThat(envelope.aggregateSequence()).isEqualTo(4L);
        assertThat(envelope.time()).isEqualTo(occurredAt.toInstant());
        JsonNode intent = envelope.data().path("notificationIntents").get(0);
        assertThat(intent.path("typeKey").asText())
                .isEqualTo(MailNotificationEvents.SHARED_ASSIGNMENT_TYPE);
        assertThat(intent.path("actorReference").asText()).isEqualTo("user:41");
        assertThat(intent.path("targetReference").asText())
                .isEqualTo("/mail/shared?threadId=" + threadId);
        assertThat(intent.path("variables").path("sharedInboxId").asText())
                .isEqualTo(inboxId.toString());
    }

    @Test
    void followUpDueIsPreferenceGatedAndHasAStableOccurrenceIdentity() {
        UUID followUpId = UUID.randomUUID();
        var due = new MailFollowUpDueNotificationRepository.DueFollowUp(
                7L, followUpId, 41L, UUID.randomUUID(), 2L,
                OffsetDateTime.parse("2026-09-17T10:00:00Z"));
        when(preferences.notifyFollowUpDue(7L, 41L)).thenReturn(true);

        UUID first = events.followUpDue(due).orElseThrow();
        UUID replay = events.followUpDue(due).orElseThrow();

        ArgumentCaptor<DomainEventEnvelope> captured =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder, org.mockito.Mockito.times(2)).record(captured.capture());
        assertThat(first).isEqualTo(replay);
        assertThat(captured.getAllValues())
                .extracting(DomainEventEnvelope::id)
                .containsExactly(first, first);
        DomainEventEnvelope envelope = captured.getAllValues().getFirst();
        assertThat(envelope.aggregateSequence()).isEqualTo(3L);
        JsonNode intent = envelope.data().path("notificationIntents").get(0);
        assertThat(intent.path("typeKey").asText())
                .isEqualTo(MailNotificationEvents.FOLLOW_UP_DUE_TYPE);
        assertThat(intent.path("targetReference").asText())
                .isEqualTo("/mail/follow-up?threadId=" + due.threadId());
        assertThat(intent.path("dueAt").asText()).isEqualTo("2026-09-17T10:00:00Z");
        assertThat(intent.path("variables").path("followUpId").asText())
                .isEqualTo(followUpId.toString());
    }
}
