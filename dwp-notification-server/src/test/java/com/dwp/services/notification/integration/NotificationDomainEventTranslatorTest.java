package com.dwp.services.notification.integration;

import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.services.notification.domain.NotificationModels.MaterializationContextKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationDomainEventTranslatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final NotificationDomainEventTranslator translator =
            new NotificationDomainEventTranslator(
                    objectMapper,
                    "urn:dwp:messaging=dwp-messaging-server");

    @Test
    void translatesOnboardedCanonicalDirectIntents() throws Exception {
        UUID eventId = UUID.randomUUID();
        ObjectNode data = objectMapper.createObjectNode();
        ObjectNode intent = data.putArray("notificationIntents").addObject()
                .put("typeKey", "MESSAGING.DIRECT_MESSAGE")
                .put("threadKey", "messaging-conversation:42")
                .put("reasonCode", "DIRECT")
                .put("targetReference", "/messages/direct?conversation=42")
                .put("actionRequired", false);
        intent.putArray("recipientUserIds").add(900018L).add(900019L);
        intent.putArray("contexts")
                .addObject()
                .put("kind", "CONVERSATION")
                .put("key", "messaging-conversation:42")
                .put("matchable", true);
        intent.putObject("variables")
                .put("senderName", "박현우")
                .put("messagePreview", "배포 계획을 확인해 주세요.");
        DomainEventEnvelope envelope = new DomainEventEnvelope(
                "1.0",
                eventId,
                "urn:dwp:messaging",
                "messaging.message.sent.v1",
                1,
                Instant.parse("2026-08-20T01:00:00Z"),
                "MESSAGING_CONVERSATION/42",
                1L,
                "MESSAGING_CONVERSATION",
                "42",
                7,
                "corr-42",
                null,
                null,
                data,
                Map.of());

        NotificationDomainEventTranslator.Translation translated = translator
                .translate(objectMapper.writeValueAsString(envelope))
                .getFirst();

        assertThat(translated.actor().sourceService()).isEqualTo("dwp-messaging-server");
        assertThat(translated.request().sourceEventId()).isEqualTo(eventId);
        assertThat(translated.request().typeKey()).isEqualTo("MESSAGING.DIRECT_MESSAGE");
        assertThat(translated.request().recipientUserIds()).containsExactly(900018L, 900019L);
        assertThat(translated.request().contexts()).singleElement().satisfies(context -> {
            assertThat(context.kind()).isEqualTo(MaterializationContextKind.CONVERSATION);
            assertThat(context.key()).isEqualTo("messaging-conversation:42");
            assertThat(context.matchable()).isTrue();
        });
        assertThat(translated.request().variables())
                .containsEntry("senderName", "박현우")
                .containsEntry("messagePreview", "배포 계획을 확인해 주세요.");
    }

    @Test
    void translatesWorkplaceClosureIntentToAUserAccessibleBookingResult() throws Exception {
        UUID bookingId = UUID.randomUUID();
        ObjectNode data = objectMapper.createObjectNode();
        ObjectNode intent = data.putArray("notificationIntents").addObject()
                .put("typeKey", "WORKPLACE.FACILITY_CLOSURE_IMPACT")
                .put("threadKey", "workplace-booking:" + bookingId)
                .put("locale", "ko-KR")
                .put("reasonCode", "OWNER")
                .put("targetReference", "/workplace/reservations?booking=" + bookingId)
                .put("actionRequired", false);
        intent.putArray("recipientUserIds").add(900031L);
        intent.putArray("contexts").addObject()
                .put("kind", "WORK_ITEM")
                .put("key", "workplace-booking:" + bookingId)
                .put("matchable", true);
        intent.putObject("variables")
                .put("resourceName", "회의실 D-1208")
                .put("bookingId", bookingId.toString())
                .put("impactAction", "REPLACE")
                .put("replacementResourceId", UUID.randomUUID().toString())
                .put("startsAt", "2026-09-20T09:00:00+09:00")
                .put("endsAt", "2026-09-20T10:00:00+09:00")
                .put("commandId", UUID.randomUUID().toString())
                .put("closureId", UUID.randomUUID().toString());
        DomainEventEnvelope envelope = DomainEventEnvelope.create(
                "urn:dwp:platform:workplace",
                "workplace.facility-closure.executed.v1",
                1,
                7L,
                "FACILITY_CLOSURE_COMMAND",
                UUID.randomUUID().toString(),
                1,
                "corr-workplace-closure",
                null,
                null,
                data);
        NotificationDomainEventTranslator workplaceTranslator =
                new NotificationDomainEventTranslator(
                        objectMapper,
                        "urn:dwp:platform:workplace=dwp-platform-server");

        NotificationDomainEventTranslator.Translation translated = workplaceTranslator
                .translate(objectMapper.writeValueAsString(envelope))
                .getFirst();

        assertThat(translated.actor().sourceService()).isEqualTo("dwp-platform-server");
        assertThat(translated.request().typeKey())
                .isEqualTo("WORKPLACE.FACILITY_CLOSURE_IMPACT");
        assertThat(translated.request().targetReference())
                .isEqualTo("/workplace/reservations?booking=" + bookingId);
        assertThat(translated.request().recipientUserIds()).containsExactly(900031L);
        assertThat(translated.request().variables())
                .containsEntry("bookingId", bookingId.toString())
                .containsEntry("impactAction", "REPLACE")
                .containsKey("replacementResourceId");
    }

    @Test
    void ignoresBusinessEventsWithoutNotificationProjectionHints() throws Exception {
        ObjectNode data = objectMapper.createObjectNode().put("messageId", "42");
        DomainEventEnvelope envelope = DomainEventEnvelope.create(
                "urn:dwp:unknown",
                "unknown.business.changed.v1",
                1,
                1L,
                "BUSINESS",
                "42",
                1,
                "corr-42",
                null,
                null,
                data);

        assertThat(translator.translate(objectMapper.writeValueAsString(envelope))).isEmpty();
    }

    @Test
    void translatesTargetLifecycleHintsWithoutNotificationRecipients() throws Exception {
        ObjectNode data = objectMapper.createObjectNode();
        data.putArray("notificationTargetChanges")
                .addObject()
                .put("ownerAppKey", "messaging")
                .put("targetReference", "/messages/direct?conversation=42&message=7")
                .put("state", "DELETED")
                .put("reason", "SOURCE_DELETED");
        DomainEventEnvelope envelope = DomainEventEnvelope.create(
                "urn:dwp:messaging",
                "messaging.message.deleted.v1",
                1,
                1L,
                "MESSAGING_MESSAGE",
                "7",
                2,
                "corr-delete-7",
                null,
                null,
                data);

        NotificationDomainEventTranslator.TranslationBatch batch =
                translator.translateBatch(objectMapper.writeValueAsString(envelope));

        assertThat(batch.notifications()).isEmpty();
        assertThat(batch.targetChanges()).singleElement().satisfies(translation -> {
            assertThat(translation.actor().sourceService()).isEqualTo("dwp-messaging-server");
            assertThat(translation.change().ownerAppKey()).isEqualTo("messaging");
            assertThat(translation.change().state()).isEqualTo("DELETED");
            assertThat(translation.change().reason()).isEqualTo("SOURCE_DELETED");
        });
    }

    @Test
    void rejectsProjectionHintsFromUnonboardedProducers() throws Exception {
        ObjectNode data = objectMapper.createObjectNode();
        ObjectNode intent = data.putArray("notificationIntents").addObject()
                .put("typeKey", "MESSAGING.DIRECT_MESSAGE");
        intent.putArray("recipientUserIds").add(900018L);
        intent.putObject("variables");
        DomainEventEnvelope envelope = DomainEventEnvelope.create(
                "urn:dwp:unknown",
                "unknown.business.changed.v1",
                1,
                1L,
                "BUSINESS",
                "42",
                1,
                "corr-42",
                null,
                null,
                data);

        assertThatThrownBy(() -> translator.translate(objectMapper.writeValueAsString(envelope)))
                .isInstanceOf(NotificationDomainEventException.class)
                .hasMessageContaining("not notification-onboarded");
    }

    @Test
    void rejectsUnknownContextFieldsSoPayloadOrBodyCannotBeSmuggled() throws Exception {
        ObjectNode intent = canonicalIntent();
        intent.putArray("contexts").addObject()
                .put("kind", "PROJECT")
                .put("key", "project:renewal")
                .put("matchable", true)
                .put("body", "secret project content");

        assertThatThrownBy(() -> translator.translate(event(intent)))
                .isInstanceOf(NotificationDomainEventException.class)
                .hasMessageContaining("only contract fields");
    }

    @Test
    void rejectsOversizedDuplicateAndNonCanonicalContextsFailClosed() throws Exception {
        ObjectNode oversized = canonicalIntent();
        var oversizedContexts = oversized.putArray("contexts");
        for (int index = 0; index < 21; index++) {
            oversizedContexts.addObject()
                    .put("kind", "WORK_ITEM")
                    .put("key", "work-item:" + index)
                    .put("matchable", true);
        }
        assertThatThrownBy(() -> translator.translate(event(oversized)))
                .isInstanceOf(NotificationDomainEventException.class)
                .hasMessageContaining("at most 20");

        ObjectNode duplicate = canonicalIntent();
        var duplicateContexts = duplicate.putArray("contexts");
        for (int index = 0; index < 2; index++) {
            duplicateContexts.addObject()
                    .put("kind", "TOPIC")
                    .put("key", "security-alert")
                    .put("matchable", true);
        }
        assertThatThrownBy(() -> translator.translate(event(duplicate)))
                .isInstanceOf(NotificationDomainEventException.class)
                .hasMessageContaining("unique");

        ObjectNode nonCanonical = canonicalIntent();
        nonCanonical.putArray("contexts").addObject()
                .put("kind", "PROJECT")
                .put("key", " project:renewal")
                .put("matchable", true);
        assertThatThrownBy(() -> translator.translate(event(nonCanonical)))
                .isInstanceOf(NotificationDomainEventException.class)
                .hasMessageContaining("key is invalid");
    }

    @Test
    void rejectsRawPersonIdentifiersAndPersonalDisplayHints() throws Exception {
        ObjectNode email = canonicalIntent();
        email.putArray("contexts").addObject()
                .put("kind", "PERSON")
                .put("key", "person:alice@example.test")
                .put("matchable", true);
        assertThatThrownBy(() -> translator.translate(event(email)))
                .isInstanceOf(NotificationDomainEventException.class)
                .hasMessageContaining("not canonical");

        ObjectNode namedPerson = canonicalIntent();
        namedPerson.putArray("contexts").addObject()
                .put("kind", "PERSON")
                .put("key", "user:42")
                .put("displayHint", "Alice Kim")
                .put("matchable", true);
        assertThatThrownBy(() -> translator.translate(event(namedPerson)))
                .isInstanceOf(NotificationDomainEventException.class)
                .hasMessageContaining("non-personal");
    }

    private ObjectNode canonicalIntent() {
        ObjectNode intent = objectMapper.createObjectNode()
                .put("typeKey", "MESSAGING.DIRECT_MESSAGE")
                .put("threadKey", "messaging-conversation:42")
                .put("actionRequired", false);
        intent.putArray("recipientUserIds").add(900018L);
        intent.putObject("variables");
        return intent;
    }

    private String event(ObjectNode intent) throws Exception {
        ObjectNode data = objectMapper.createObjectNode();
        data.putArray("notificationIntents").add(intent);
        return objectMapper.writeValueAsString(DomainEventEnvelope.create(
                "urn:dwp:messaging",
                "messaging.message.sent.v1",
                1,
                7L,
                "MESSAGING_CONVERSATION",
                "42",
                1,
                "corr-context-42",
                null,
                null,
                data));
    }
}
