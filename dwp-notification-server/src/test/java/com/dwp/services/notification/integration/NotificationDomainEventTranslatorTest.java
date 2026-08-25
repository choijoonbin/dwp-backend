package com.dwp.services.notification.integration;

import com.dwp.core.event.DomainEventEnvelope;
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
        assertThat(translated.request().variables())
                .containsEntry("senderName", "박현우")
                .containsEntry("messagePreview", "배포 계획을 확인해 주세요.");
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
}
