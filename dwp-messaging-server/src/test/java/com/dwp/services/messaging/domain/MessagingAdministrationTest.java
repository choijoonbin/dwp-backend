package com.dwp.services.messaging.domain;

import com.dwp.services.messaging.realtime.MessagingEventRecorder;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingAdministrationTest {

    @Mock
    private MessagingQueryRepository queries;
    @Mock
    private MessagingCommandRepository commands;
    @Mock
    private MessagingEventRecorder events;

    @BeforeEach
    void setUp() {
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                100, 42, UUID.randomUUID(), "Admin", Set.of("MESSAGING_ADMIN"),
                Set.of("APP.MESSAGING:VIEW"), Set.of()));
    }

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void overviewProjectsConversationMetadataWithoutMessageContent() {
        UUID conversationId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-28T00:00:00Z");
        var lastMessage = new MessagingDtos.MessageSummary(
                UUID.randomUUID(), conversationId, 9, 200, UUID.randomUUID(), "Sender",
                "administrator must not receive this body", "TEXT", "USER", null,
                null, null, createdAt, 3, List.of(), 2,
                new MessagingDtos.ThreadRootPreview(
                        UUID.randomUUID(), "Root sender", "root body", null, createdAt));
        var conversation = new MessagingDtos.ConversationSummary(
                conversationId, "governed", "CHANNEL", "Governed channel", "topic",
                "PRIVATE", "INTERNAL", null, null, "ACTIVE", 4, 1,
                false, false, lastMessage, createdAt, 1);
        when(queries.adminMetrics(42)).thenReturn(new MessagingDtos.AdminMetrics(1, 0, 4, 1, 0));
        when(queries.policy(42)).thenReturn(new MessagingDtos.TenantPolicy(
                true, true, true, true, false, false, 365, 100, 1));
        when(queries.adminConversations(42, 12))
                .thenReturn(List.of(conversation));

        MessagingDtos.AdminOverview overview = new MessagingAdministration(
                queries, commands, events, null).overview();

        MessagingDtos.MessageSummary projected =
                overview.governedConversations().getFirst().lastMessage();
        assertThat(projected.body()).isEmpty();
        assertThat(projected.rootPreview()).isNull();
        assertThat(projected.reactions()).isEmpty();
        assertThat(projected.attachments()).isEmpty();
        assertThat(projected.mentions()).isEmpty();
    }
}
