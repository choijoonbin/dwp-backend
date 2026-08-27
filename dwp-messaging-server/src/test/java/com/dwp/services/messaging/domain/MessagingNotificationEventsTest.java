package com.dwp.services.messaging.domain;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.services.messaging.security.MessagingRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessagingNotificationEventsTest {

    private final DomainEventRecorder recorder = mock(DomainEventRecorder.class);
    private final MessagingQueryRepository queries = mock(MessagingQueryRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final MessagingNotificationEvents events = new MessagingNotificationEvents(
            recorder,
            new DomainEventContractRegistry(),
            objectMapper,
            queries);

    @Test
    void marksTheDependencyConstructorForRuntimeInjection() throws NoSuchMethodException {
        var constructor = MessagingNotificationEvents.class.getDeclaredConstructor(
                DomainEventRecorder.class,
                DomainEventContractRegistry.class,
                ObjectMapper.class,
                MessagingQueryRepository.class);

        assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
    }

    @Test
    void emitsOneDirectIntentForActiveNonMutedRecipients() {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(queries.members(1L, conversationId)).thenReturn(List.of(
                member(10L, "DEFAULT"),
                member(20L, "DEFAULT"),
                member(30L, "MUTE")));

        events.messageCreated(
                subject(10L),
                conversation(conversationId, "DIRECT", "INTERNAL"),
                new MessagingCommandRepository.MessageInsertResult(messageId, true, 4),
                new MessagingDtos.SendMessageRequest("배포 계획을 확인해 주세요.", UUID.randomUUID(), null),
                null,
                "corr-4");

        ArgumentCaptor<DomainEventEnvelope> captured =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder).record(captured.capture());
        DomainEventEnvelope envelope = captured.getValue();
        assertThat(envelope.type()).isEqualTo(MessagingNotificationEvents.MESSAGE_SENT);
        assertThat(envelope.aggregateSequence()).isEqualTo(4);
        assertThat(envelope.data().path("notificationIntents").get(0).path("typeKey").asText())
                .isEqualTo(MessagingNotificationEvents.DIRECT_MESSAGE);
        assertThat(envelope.data().path("notificationIntents").get(0)
                .path("recipientUserIds").findValuesAsText(""))
                .isEmpty();
        assertThat(envelope.data().path("notificationIntents").get(0)
                .path("recipientUserIds").toString()).isEqualTo("[20]");
    }

    @Test
    void redactsConfidentialMessagePreviewsBeforeTheyEnterTheOutbox() {
        UUID conversationId = UUID.randomUUID();
        when(queries.members(1L, conversationId)).thenReturn(List.of(
                member(10L, "DEFAULT"), member(20L, "DEFAULT")));

        events.messageCreated(
                subject(10L),
                conversation(conversationId, "DIRECT", "CONFIDENTIAL"),
                new MessagingCommandRepository.MessageInsertResult(UUID.randomUUID(), true, 5),
                new MessagingDtos.SendMessageRequest("인수 가격은 100억 원입니다.", UUID.randomUUID(), null),
                null,
                "corr-5");

        ArgumentCaptor<DomainEventEnvelope> captured =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder).record(captured.capture());
        assertThat(captured.getValue().data().toString())
                .doesNotContain("100억")
                .contains("보호된 대화에 새 메시지가 도착했습니다.");
    }

    @Test
    void doesNotNotifyAnEntireGroupWhenNoOneWasMentioned() {
        UUID conversationId = UUID.randomUUID();
        when(queries.members(1L, conversationId)).thenReturn(List.of(
                member(10L, "DEFAULT"),
                member(20L, "DEFAULT"),
                member(30L, "DEFAULT")));

        events.messageCreated(
                subject(10L),
                conversation(conversationId, "GROUP", "INTERNAL"),
                new MessagingCommandRepository.MessageInsertResult(
                        UUID.randomUUID(), true, 6),
                new MessagingDtos.SendMessageRequest(
                        "정기 채널 업데이트", UUID.randomUUID(), null),
                null,
                "corr-6");

        verifyNoInteractions(recorder);
    }

    @Test
    void emitsARecipientTargetLifecycleChangeWhenTheSourceMessageIsDeleted() {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        events.messageDeleted(
                subject(10L),
                conversation(conversationId, "DIRECT", "INTERNAL"),
                messageId,
                3,
                "corr-delete");

        ArgumentCaptor<DomainEventEnvelope> captured =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder).record(captured.capture());
        DomainEventEnvelope envelope = captured.getValue();
        assertThat(envelope.type()).isEqualTo(MessagingNotificationEvents.MESSAGE_DELETED);
        assertThat(envelope.aggregateType()).isEqualTo("MESSAGING_MESSAGE");
        assertThat(envelope.aggregateSequence()).isEqualTo(3);
        assertThat(envelope.data().path("notificationTargetChanges").get(0).toString())
                .contains("\"ownerAppKey\":\"messaging\"")
                .contains("\"state\":\"DELETED\"")
                .contains("\"reason\":\"SOURCE_DELETED\"")
                .contains(messageId.toString());
    }

    @Test
    void notifiesOnlyMembersWhoExplicitlyChooseAllGroupMessages() {
        UUID conversationId = UUID.randomUUID();
        when(queries.members(1L, conversationId)).thenReturn(List.of(
                member(10L, "DEFAULT"),
                member(20L, "ALL"),
                member(30L, "MENTIONS"),
                member(40L, "MUTE")));

        events.messageCreated(
                subject(10L),
                conversation(conversationId, "GROUP", "INTERNAL"),
                new MessagingCommandRepository.MessageInsertResult(
                        UUID.randomUUID(), true, 7),
                new MessagingDtos.SendMessageRequest(
                        "채널 운영 현황을 공유합니다.", UUID.randomUUID(), null),
                null,
                "corr-7");

        ArgumentCaptor<DomainEventEnvelope> captured =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder).record(captured.capture());
        assertThat(captured.getValue().data().path("notificationIntents").get(0)
                .path("typeKey").asText())
                .isEqualTo(MessagingNotificationEvents.CHANNEL_MESSAGE);
        assertThat(captured.getValue().data().path("notificationIntents").get(0)
                .path("recipientUserIds").toString()).isEqualTo("[20]");
    }

    @Test
    void prioritizesMentionsAndThreadRepliesWithoutDuplicatingAllSubscribers() {
        UUID conversationId = UUID.randomUUID();
        UUID replyToMessageId = UUID.randomUUID();
        when(queries.members(1L, conversationId)).thenReturn(List.of(
                member(10L, "DEFAULT"),
                member(20L, "MENTIONS"),
                member(30L, "DEFAULT"),
                member(40L, "ALL"),
                member(50L, "MUTE")));

        events.messageCreated(
                subject(10L),
                conversation(conversationId, "GROUP", "INTERNAL"),
                new MessagingCommandRepository.MessageInsertResult(
                        UUID.randomUUID(), true, 8),
                new MessagingDtos.SendMessageRequest(
                        "@사용자20 확인 부탁드립니다.",
                        UUID.randomUUID(),
                        replyToMessageId,
                        List.of(),
                        List.of(20L)),
                30L,
                "corr-8");

        ArgumentCaptor<DomainEventEnvelope> captured =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder).record(captured.capture());
        var intents = captured.getValue().data().path("notificationIntents");

        assertThat(intents).hasSize(3);
        assertThat(intents.get(0).path("typeKey").asText())
                .isEqualTo(MessagingNotificationEvents.MENTION);
        assertThat(intents.get(0).path("recipientUserIds").toString()).isEqualTo("[20]");
        assertThat(intents.get(1).path("typeKey").asText())
                .isEqualTo(MessagingNotificationEvents.THREAD_REPLY);
        assertThat(intents.get(1).path("recipientUserIds").toString()).isEqualTo("[30]");
        assertThat(intents.get(2).path("typeKey").asText())
                .isEqualTo(MessagingNotificationEvents.CHANNEL_MESSAGE);
        assertThat(intents.get(2).path("recipientUserIds").toString()).isEqualTo("[40]");
    }

    private MessagingRequestContext.Subject subject(long userId) {
        return new MessagingRequestContext.Subject(
                userId, 1L, UUID.randomUUID(), "김민서", Set.of(), Set.of(), Set.of());
    }

    private MessagingDtos.ConversationSummary conversation(
            UUID conversationId, String type, String classification) {
        return new MessagingDtos.ConversationSummary(
                conversationId,
                "conversation-key",
                type,
                "AX 프로젝트",
                "업무 대화",
                "PRIVATE",
                classification,
                null,
                null,
                "ACTIVE",
                2,
                0,
                false,
                false,
                null,
                null,
                1);
    }

    private MessagingDtos.MemberSummary member(long userId, String notificationLevel) {
        return new MessagingDtos.MemberSummary(
                userId,
                UUID.randomUUID(),
                "사용자 " + userId,
                "user" + userId + "@example.test",
                null,
                null,
                "AVAILABLE",
                "MEMBER",
                "DIRECT",
                notificationLevel,
                false,
                false,
                null,
                0,
                null);
    }
}
