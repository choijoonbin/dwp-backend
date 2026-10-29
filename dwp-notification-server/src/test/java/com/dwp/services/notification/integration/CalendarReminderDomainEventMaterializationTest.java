package com.dwp.services.notification.integration;

import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.services.notification.domain.DirectNotificationMaterializer;
import com.dwp.services.notification.domain.NotificationMaterializationRepository.PersistenceResult;
import com.dwp.services.notification.domain.NotificationMaterializationRepository.RenderedContent;
import com.dwp.services.notification.domain.NotificationMaterializationTransactions;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.domain.NotificationModels.MaterializationResult;
import com.dwp.services.notification.domain.NotificationProducerOwnershipPolicy;
import com.dwp.services.notification.domain.NotificationRecipientEntitlementAdmission;
import com.dwp.services.notification.domain.TemplateContract;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalendarReminderDomainEventMaterializationTest {

    private static final String SOURCE = "urn:dwp:platform:calendar";
    private static final String EVENT = "calendar.event-reminder.due.v1";
    private static final String ONBOARDING =
            SOURCE + "=dwp-platform-server:" + EVENT;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @CsvSource({
            "CALENDAR.EVENT_REMINDER.IN_APP,IN_APP,Calendar reminder"
    })
    void exactCalendarSourceEventTranslatesAndReachesOwnedMaterialization(
            String typeKey,
            String channel,
            String titleTemplate) throws Exception {
        long tenantId = 71L;
        long recipientUserId = 900071L;
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope envelope = reminder(
                tenantId, recipientUserId, eventId, EVENT, typeKey, channel);
        NotificationDomainEventTranslator translator =
                new NotificationDomainEventTranslator(
                        mapper,
                        "urn:dwp:messaging=dwp-messaging-server",
                        ONBOARDING);

        NotificationDomainEventTranslator.Translation translation = translator
                .translate(mapper.writeValueAsString(envelope))
                .getFirst();

        assertThat(translation.actor().sourceService()).isEqualTo("dwp-platform-server");
        assertThat(translation.request().typeKey()).isEqualTo(typeKey);
        assertThat(translation.request().recipientUserIds()).containsExactly(recipientUserId);
        assertThat(translation.request().variables())
                .containsEntry("eventId", eventId.toString())
                .containsEntry("channel", channel)
                .doesNotContainKeys("attendeeEmail", "description", "location");

        NotificationMaterializationTransactions transactions =
                mock(NotificationMaterializationTransactions.class);
        NotificationProducerOwnershipPolicy ownership =
                mock(NotificationProducerOwnershipPolicy.class);
        NotificationRecipientEntitlementAdmission entitlements =
                mock(NotificationRecipientEntitlementAdmission.class);
        DirectNotificationMaterializer materializer = new DirectNotificationMaterializer(
                transactions, ownership, entitlements, mapper);
        TemplateContract contract = contract(typeKey, titleTemplate);
        when(transactions.contract(tenantId, typeKey, EVENT, 1, "ko-KR"))
                .thenReturn(contract);
        when(entitlements.admittedRecipients(
                tenantId, List.of(recipientUserId), "calendar"))
                .thenReturn(Set.of(recipientUserId));
        UUID notificationId = UUID.randomUUID();
        when(transactions.materialize(
                eq(tenantId), any(), eq(contract), any(), anyString(),
                eq(envelope.correlationId()), eq(Set.of(recipientUserId)), any(Instant.class)))
                .thenReturn(new PersistenceResult(
                        new MaterializationResult(
                                UUID.randomUUID(), notificationId, 1, false, "1"),
                        List.of()));

        MaterializationResult result = materializer.materialize(
                translation.actor(), translation.request(), translation.correlationId());

        assertThat(result.notificationId()).isEqualTo(notificationId);
        assertThat(result.recipientCount()).isOne();
        verify(ownership).requireOwnership(translation.actor(), contract);
        ArgumentCaptor<DirectMaterializationRequest> request =
                ArgumentCaptor.forClass(DirectMaterializationRequest.class);
        ArgumentCaptor<RenderedContent> rendered =
                ArgumentCaptor.forClass(RenderedContent.class);
        verify(transactions).materialize(
                eq(tenantId), request.capture(), eq(contract), rendered.capture(),
                anyString(), eq(envelope.correlationId()),
                eq(Set.of(recipientUserId)), any(Instant.class));
        assertThat(request.getValue().variables().keySet()).containsExactlyInAnyOrder(
                "eventId", "eventTitle", "occurrenceStartsAt", "dueAt",
                "offsetMinutes", "channel", "timeZone");
        assertThat(rendered.getValue().title()).contains("Calendar reminder");
        assertThat(rendered.getValue().action())
                .containsEntry("route", "/calendar/schedule?eventId=" + eventId);
    }

    @Test
    void emailIntentTranslatesButFailsClosedWithoutAnExternalMaterializationAdapter()
            throws Exception {
        long tenantId = 71L;
        long recipientUserId = 900071L;
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope envelope = reminder(
                tenantId,
                recipientUserId,
                eventId,
                EVENT,
                "CALENDAR.EVENT_REMINDER.EMAIL",
                "EMAIL");
        NotificationDomainEventTranslator translator =
                new NotificationDomainEventTranslator(
                        mapper,
                        "urn:dwp:messaging=dwp-messaging-server",
                        ONBOARDING);
        NotificationDomainEventTranslator.Translation translation = translator
                .translate(mapper.writeValueAsString(envelope))
                .getFirst();
        assertThat(translation.request().variables())
                .containsEntry("channel", "EMAIL");

        NotificationMaterializationTransactions transactions =
                mock(NotificationMaterializationTransactions.class);
        when(transactions.contract(
                tenantId,
                "CALENDAR.EVENT_REMINDER.EMAIL",
                EVENT,
                1,
                "ko-KR"))
                .thenThrow(new NotificationException(
                        NotificationErrorCode.NOTIFICATION_CONTRACT_QUARANTINED));
        DirectNotificationMaterializer materializer = new DirectNotificationMaterializer(
                transactions,
                mock(NotificationProducerOwnershipPolicy.class),
                mock(NotificationRecipientEntitlementAdmission.class),
                mapper);

        assertThatThrownBy(() -> materializer.materialize(
                translation.actor(), translation.request(), translation.correlationId()))
                .isInstanceOf(NotificationException.class);
        verify(transactions, never()).materialize(
                eq(tenantId), any(), any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void reservedCalendarSourceRejectsAnyOtherEventType() throws Exception {
        NotificationDomainEventTranslator translator =
                new NotificationDomainEventTranslator(
                        mapper,
                        "urn:dwp:messaging=dwp-messaging-server",
                        ONBOARDING);
        DomainEventEnvelope envelope = reminder(
                71L,
                900071L,
                UUID.randomUUID(),
                "calendar.event-reminder.forged.v1",
                "CALENDAR.EVENT_REMINDER.IN_APP",
                "IN_APP");

        assertThatThrownBy(() -> translator.translate(mapper.writeValueAsString(envelope)))
                .isInstanceOf(NotificationDomainEventException.class)
                .hasMessageContaining(
                        "reserved Calendar notification source-event pair is not onboarded");
    }

    private DomainEventEnvelope reminder(
            long tenantId,
            long recipientUserId,
            UUID eventId,
            String eventType,
            String typeKey,
            String channel) {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode intent = data.putArray("notificationIntents").addObject()
                .put("typeKey", typeKey)
                .put("threadKey", "calendar-event:" + eventId)
                .put("locale", "ko-KR")
                .put("reasonCode", "EVENT_REMINDER_DUE")
                .put("subjectReference", "calendar-event:" + eventId)
                .put("targetReference", "/calendar/schedule?eventId=" + eventId)
                .put("dueAt", "2026-10-28T00:45:00Z")
                .put("actionRequired", false);
        intent.putArray("recipientUserIds").add(recipientUserId);
        intent.putArray("contexts").addObject()
                .put("kind", "WORK_ITEM")
                .put("key", "calendar-event:" + eventId)
                .put("matchable", true);
        intent.putObject("variables")
                .put("eventId", eventId.toString())
                .put("eventTitle", "Architecture review")
                .put("occurrenceStartsAt", "2026-10-28T01:00:00Z")
                .put("dueAt", "2026-10-28T00:45:00Z")
                .put("offsetMinutes", "15")
                .put("channel", channel)
                .put("timeZone", "Asia/Seoul");
        return DomainEventEnvelope.create(
                SOURCE,
                eventType,
                1,
                tenantId,
                "CALENDAR_REMINDER",
                UUID.randomUUID().toString(),
                1,
                "calendar-reminder-test-" + eventId,
                null,
                null,
                data);
    }

    private TemplateContract contract(String typeKey, String titleTemplate) {
        return new TemplateContract(
                UUID.randomUUID(),
                0L,
                UUID.randomUUID(),
                0L,
                null,
                typeKey,
                "calendar",
                "HIGH",
                "ACTIONABLE",
                "ko-KR",
                titleTemplate + ": {{eventTitle}}",
                "{{eventTitle}} starts soon.",
                "Review the event starting at {{occurrenceStartsAt}}.",
                Map.of(
                        "label", "View event",
                        "route", "/calendar/schedule?eventId={{eventId}}"));
    }
}
