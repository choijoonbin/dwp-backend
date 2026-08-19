package com.dwp.services.messaging.api;

import com.dwp.services.messaging.domain.MessagingDtos;
import com.dwp.services.messaging.domain.MessagingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MessagingControllerTest {

    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse("2026-08-19T01:00:00Z");

    @Test
    void directConversationSerializesOnlyTheConversationSummary() throws Exception {
        UUID conversationId = UUID.randomUUID();
        MessagingService service = mock(MessagingService.class);
        MessagingDtos.ConversationSummary summary = new MessagingDtos.ConversationSummary(
                conversationId, "dm:100:200", "DIRECT", "Test User / Person", null,
                "PRIVATE", "INTERNAL", null, null, "ACTIVE", 2, 0,
                false, false, null, null, 3);
        when(service.createDirectConversation(
                new MessagingDtos.DirectConversationRequest(200L), "corr-direct"))
                .thenReturn(summary);
        MockMvc mvc = standaloneSetup(new MessagingController(service)).build();

        mvc.perform(post("/v1/direct-conversations")
                        .header("X-Correlation-ID", "corr-direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.data.conversationType").value("DIRECT"))
                .andExpect(jsonPath("$.data.members").doesNotExist())
                .andExpect(jsonPath("$.data.messages").doesNotExist())
                .andExpect(jsonPath("$.data.realtime").doesNotExist());
    }

    @Test
    void sendSerializesAConstantSizeMessageDelta() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        MessagingService service = mock(MessagingService.class);
        when(service.sendMessage(
                conversationId,
                new MessagingDtos.SendMessageRequest("hello", idempotencyKey, null),
                "corr-send"))
                .thenReturn(message(messageId, conversationId));
        MockMvc mvc = standaloneSetup(new MessagingController(service)).build();

        mvc.perform(post("/v1/conversations/{conversationId}/messages", conversationId)
                        .header("X-Correlation-ID", "corr-send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "body": "hello",
                                  "idempotencyKey": "%s"
                                }
                                """.formatted(idempotencyKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.data.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.data.sequence").value(41))
                .andExpect(jsonPath("$.data.body").value("hello"))
                .andExpect(jsonPath("$.data.conversation").doesNotExist())
                .andExpect(jsonPath("$.data.members").doesNotExist())
                .andExpect(jsonPath("$.data.messages").doesNotExist())
                .andExpect(jsonPath("$.data.realtime").doesNotExist());
    }

    @Test
    void messageHistorySerializesTheKeysetPageContract() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        MessagingService service = mock(MessagingService.class);
        MessagingDtos.MessagePage page = new MessagingDtos.MessagePage(
                List.of(message(messageId, conversationId)), true, 41L);
        when(service.messages(conversationId, 73L, 2)).thenReturn(page);
        when(service.messages(conversationId, null, 50)).thenReturn(page);
        MockMvc mvc = standaloneSetup(new MessagingController(service)).build();

        mvc.perform(get("/v1/conversations/{conversationId}/messages", conversationId)
                        .param("beforeSequence", "73")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.data.items[0].sequence").value(41))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextBeforeSequence").value(41));

        mvc.perform(get("/v1/conversations/{conversationId}/messages", conversationId))
                .andExpect(status().isOk());
        verify(service).messages(conversationId, null, 50);
    }

    @Test
    void reactionCommandsSerializeOnlyTheUpdatedMessage() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        MessagingService service = mock(MessagingService.class);
        MessagingDtos.MessageSummary message = message(messageId, conversationId);
        when(service.addReaction(
                conversationId, messageId, new MessagingDtos.ReactionRequest("thumbs-up")))
                .thenReturn(message);
        when(service.removeReaction(conversationId, messageId, "thumbs-up"))
                .thenReturn(message);
        MockMvc mvc = standaloneSetup(new MessagingController(service)).build();

        mvc.perform(post(
                        "/v1/conversations/{conversationId}/messages/{messageId}/reactions",
                        conversationId, messageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emoji\":\"thumbs-up\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.data.reactions[0].emoji").value("thumbs-up"))
                .andExpect(jsonPath("$.data.messages").doesNotExist());

        mvc.perform(delete(
                        "/v1/conversations/{conversationId}/messages/{messageId}/reactions/{emoji}",
                        conversationId, messageId, "thumbs-up"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.data.reactions[0].count").value(1))
                .andExpect(jsonPath("$.data.members").doesNotExist());
    }

    @Test
    void readCursorSerializesOnlyTheCurrentMonotonicCursor() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        MessagingService service = mock(MessagingService.class);
        MessagingDtos.ReadCursorResponse cursor = new MessagingDtos.ReadCursorResponse(
                conversationId, messageId, 41, CREATED_AT, 7);
        when(service.markRead(
                conversationId, new MessagingDtos.ReadCursorRequest(messageId)))
                .thenReturn(cursor);
        MockMvc mvc = standaloneSetup(new MessagingController(service)).build();

        mvc.perform(post("/v1/conversations/{conversationId}/read-cursor", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"%s\"}".formatted(messageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.data.lastReadMessageId").value(messageId.toString()))
                .andExpect(jsonPath("$.data.lastReadSequence").value(41))
                .andExpect(jsonPath("$.data.lastReadAt").exists())
                .andExpect(jsonPath("$.data.version").value(7))
                .andExpect(jsonPath("$.data.conversation").doesNotExist())
                .andExpect(jsonPath("$.data.members").doesNotExist())
                .andExpect(jsonPath("$.data.messages").doesNotExist());
    }

    private MessagingDtos.MessageSummary message(UUID messageId, UUID conversationId) {
        return new MessagingDtos.MessageSummary(
                messageId,
                conversationId,
                41,
                100,
                UUID.randomUUID(),
                "Test User",
                "hello",
                "TEXT",
                "USER",
                null,
                null,
                null,
                CREATED_AT,
                0,
                List.of(new MessagingDtos.ReactionSummary("thumbs-up", 1, true)),
                0,
                null);
    }
}
