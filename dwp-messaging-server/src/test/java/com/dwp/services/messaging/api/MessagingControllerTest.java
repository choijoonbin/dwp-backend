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
    void conversationListSerializesCursorAndOffsetCompatibilityContract() throws Exception {
        UUID conversationId = UUID.randomUUID();
        MessagingService service = mock(MessagingService.class);
        MessagingDtos.ConversationSummary conversation = new MessagingDtos.ConversationSummary(
                conversationId, "channel:cursor", "CHANNEL", "Cursor channel", null,
                "PRIVATE", "INTERNAL", null, null, "ACTIVE", 3, 1,
                false, true, null, CREATED_AT, 2);
        MessagingDtos.ConversationPage page = new MessagingDtos.ConversationPage(
                List.of(conversation), 4, 0, 1, true, "cursor-v1", null);
        when(service.conversations("CHANNELS", "risk", 0, 1, "previous-cursor"))
                .thenReturn(page);
        when(service.conversations("ALL", "", 0, 30))
                .thenReturn(new MessagingDtos.ConversationPage(
                        List.of(conversation), 1, 0, 30));
        MockMvc mvc = standaloneSetup(new MessagingController(service)).build();

        mvc.perform(get("/v1/conversations")
                        .param("scope", "CHANNELS")
                        .param("q", "risk")
                        .param("pageSize", "1")
                        .param("cursor", "previous-cursor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].conversationId")
                        .value(conversationId.toString()))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value("cursor-v1"));
        verify(service).conversations("CHANNELS", "risk", 0, 1, "previous-cursor");

        mvc.perform(get("/v1/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].conversationId")
                        .value(conversationId.toString()))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.pageSize").value(30))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        verify(service).conversations("ALL", "", 0, 30);
    }

    @Test
    void peopleDirectorySerializesCursorAndOffsetCompatibilityContract() throws Exception {
        MessagingService service = mock(MessagingService.class);
        MessagingDtos.PersonSummary person = new MessagingDtos.PersonSummary(
                200,
                UUID.randomUUID(),
                "kim@example.com",
                "Kim Person",
                "Risk Analyst",
                "Risk Operations",
                "AVAILABLE");
        MessagingDtos.PersonPage page = new MessagingDtos.PersonPage(
                List.of(person),
                42,
                true,
                "S2ltIFBlcnNvbg.200",
                14,
                new MessagingDtos.PresenceMetadata("LIVE", CREATED_AT, 75, 20));
        when(service.peoplePage(
                "kim", "risk", "analyst", "AVAILABLE", null, 12, 2))
                .thenReturn(page);
        MockMvc mvc = standaloneSetup(new MessagingController(service)).build();

        mvc.perform(get("/v1/people/page")
                        .param("q", "kim")
                        .param("organization", "risk")
                        .param("jobTitle", "analyst")
                        .param("presence", "AVAILABLE")
                        .param("offset", "12")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].userId").value(200))
                .andExpect(jsonPath("$.data.total").value(42))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value("S2ltIFBlcnNvbg.200"))
                .andExpect(jsonPath("$.data.nextOffset").value(14))
                .andExpect(jsonPath("$.data.presence.authority").value("LIVE"));
        verify(service).peoplePage(
                "kim", "risk", "analyst", "AVAILABLE", null, 12, 2);
    }

    @Test
    void legacyPeopleSearchKeepsItsArrayResponseForRollingClients() throws Exception {
        MessagingService service = mock(MessagingService.class);
        MessagingDtos.PersonSummary person = new MessagingDtos.PersonSummary(
                200,
                UUID.randomUUID(),
                "kim@example.com",
                "Kim Person",
                "Risk Analyst",
                "Risk Operations",
                "UNKNOWN");
        when(service.people("kim", 20)).thenReturn(List.of(person));
        MockMvc mvc = standaloneSetup(new MessagingController(service)).build();

        mvc.perform(get("/v1/people").param("q", "kim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value(200))
                .andExpect(jsonPath("$.data.items").doesNotExist());
        verify(service).people("kim", 20);
    }

    @Test
    void exactMessageSerializesAnAuthorizedReplyOutsideTheRootHistoryPage() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID rootMessageId = UUID.randomUUID();
        MessagingService service = mock(MessagingService.class);
        MessagingDtos.MessageSummary reply = new MessagingDtos.MessageSummary(
                messageId, conversationId, 42, 100, UUID.randomUUID(), "Test User",
                "exact reply", "TEXT", "USER", rootMessageId, null, null, CREATED_AT, 3,
                List.of(), 0, null);
        when(service.message(conversationId, messageId)).thenReturn(reply);
        MockMvc mvc = standaloneSetup(new MessagingController(service)).build();

        mvc.perform(get(
                        "/v1/conversations/{conversationId}/messages/{messageId}",
                        conversationId, messageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.data.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.data.replyToMessageId").value(rootMessageId.toString()))
                .andExpect(jsonPath("$.data.body").value("exact reply"));
        verify(service).message(conversationId, messageId);
    }

    @Test
    void internalWorkSourceSerializesOnlyBoundedAuthoritativeProvenance() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        MessagingService service = mock(MessagingService.class);
        when(service.workSource(conversationId, messageId)).thenReturn(
                new MessagingDtos.WorkSourceMessage(conversationId, messageId,
                        "DWP Product Room", "김채원 책임", CREATED_AT, null,
                        "공지 초안을 정리해 주세요", 4));
        MockMvc mvc = standaloneSetup(new MessagingInternalWorkSourceController(service)).build();

        mvc.perform(get(
                        "/internal/v1/work-sources/conversations/{conversationId}/messages/{messageId}",
                        conversationId, messageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.data.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.data.channelName").value("DWP Product Room"))
                .andExpect(jsonPath("$.data.senderName").value("김채원 책임"))
                .andExpect(jsonPath("$.data.excerpt").value("공지 초안을 정리해 주세요"))
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.body").doesNotExist());
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

    @Test
    void adminOverviewSerializesEffectiveRuntimeReadinessAndFreshness() throws Exception {
        MessagingService service = mock(MessagingService.class);
        MessagingDtos.RuntimeCapability retention = new MessagingDtos.RuntimeCapability(
                "RETENTION_ENFORCEMENT", "database-worker", true, true, false,
                "BLOCKED", "LEGAL_HOLD_AUTHORITY_UNAVAILABLE", CREATED_AT,
                "CURRENT", 2L);
        when(service.adminOverview()).thenReturn(new MessagingDtos.AdminOverview(
                CREATED_AT,
                new MessagingDtos.AdminMetrics(1, 1, 2, 3, 0),
                new MessagingDtos.TenantPolicy(
                        true, true, true, true, false, false, 365, 100, 4),
                List.of(),
                new MessagingDtos.RuntimeReadiness(
                        CREATED_AT, "BLOCKED", List.of(retention))));
        MockMvc mvc = standaloneSetup(new MessagingController(service)).build();

        mvc.perform(get("/v1/admin/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtimeReadiness.overallState").value("BLOCKED"))
                .andExpect(jsonPath("$.data.runtimeReadiness.capabilities[0].capability")
                        .value("RETENTION_ENFORCEMENT"))
                .andExpect(jsonPath("$.data.runtimeReadiness.capabilities[0].provider")
                        .value("database-worker"))
                .andExpect(jsonPath("$.data.runtimeReadiness.capabilities[0].configured")
                        .value(true))
                .andExpect(jsonPath("$.data.runtimeReadiness.capabilities[0].effective")
                        .value(false))
                .andExpect(jsonPath("$.data.runtimeReadiness.capabilities[0].freshness")
                        .value("CURRENT"))
                .andExpect(jsonPath("$.data.runtimeReadiness.capabilities[0].pendingWorkCount")
                        .value(2));
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
