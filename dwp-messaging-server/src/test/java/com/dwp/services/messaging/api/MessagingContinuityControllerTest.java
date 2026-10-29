package com.dwp.services.messaging.api;

import com.dwp.services.messaging.domain.MessagingContinuityDtos;
import com.dwp.services.messaging.domain.MessagingContinuityService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MessagingContinuityControllerTest {

    @Test
    void draftContractCarriesStructuredMentionsAndOptimisticVersion() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID mentionedPersonPublicId = UUID.randomUUID();
        MessagingContinuityService service = mock(MessagingContinuityService.class);
        var saved = new MessagingContinuityDtos.DraftResponse(
                conversationId, null, "hello @Person", List.of(mentionedPersonPublicId), 4,
                OffsetDateTime.parse("2026-10-28T02:00:00Z"));
        when(service.saveDraft(
                conversationId, null,
                new MessagingContinuityDtos.SaveDraftRequest(
                        "hello @Person", List.of(mentionedPersonPublicId), 3))).thenReturn(saved);
        MockMvc mvc = standaloneSetup(new MessagingContinuityController(service)).build();

        mvc.perform(put("/v1/conversations/{conversationId}/draft", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"body":"hello @Person",\
                                 "mentionedPersonPublicIds":["%s"],"expectedVersion":3}
                                """.formatted(mentionedPersonPublicId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value("hello @Person"))
                .andExpect(jsonPath("$.data.mentionedPersonPublicIds[0]")
                        .value(mentionedPersonPublicId.toString()))
                .andExpect(jsonPath("$.data.mentionedUserIds").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(4));
    }

    @Test
    void scheduledMessageContractSupportsQueryAndVersionedCancellation() throws Exception {
        UUID scheduledId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID mentionedPersonPublicId = UUID.randomUUID();
        MessagingContinuityService service = mock(MessagingContinuityService.class);
        var item = new MessagingContinuityDtos.ScheduledMessageResponse(
                scheduledId, conversationId, "later", null, List.of(mentionedPersonPublicId),
                OffsetDateTime.parse("2026-12-01T00:00:00Z"), "Asia/Seoul",
                "PENDING", 0, null, null, null, null, 2,
                OffsetDateTime.parse("2026-10-28T00:00:00Z"),
                OffsetDateTime.parse("2026-10-28T00:00:00Z"), "Conversation", null);
        when(service.scheduled("PENDING", null, 20))
                .thenReturn(new MessagingContinuityDtos.ScheduledMessagePage(
                        List.of(item), 20, false, null));
        when(service.cancel(scheduledId, 2, "corr-cancel")).thenReturn(
                new MessagingContinuityDtos.ScheduledMessageResponse(
                        scheduledId, conversationId, "later", null, List.of(),
                        item.scheduledFor(), "Asia/Seoul", "CANCELLED", 0,
                        null, null, null, null, 3, item.createdAt(), item.updatedAt(),
                        "Conversation", null));
        MockMvc mvc = standaloneSetup(new MessagingContinuityController(service)).build();

        mvc.perform(get("/v1/scheduled-messages").param("status", "PENDING").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].scheduledMessageId")
                        .value(scheduledId.toString()))
                .andExpect(jsonPath("$.data.items[0].mentionedPersonPublicIds[0]")
                        .value(mentionedPersonPublicId.toString()))
                .andExpect(jsonPath("$.data.items[0].mentionedUserIds").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].displayTimeZone").value("Asia/Seoul"));
        mvc.perform(delete("/v1/scheduled-messages/{scheduledMessageId}", scheduledId)
                        .header("X-Correlation-ID", "corr-cancel")
                        .param("version", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.version").value(3));
        verify(service).cancel(scheduledId, 2, "corr-cancel");
    }

    @Test
    void elapsedScheduleRequestStillReachesTheServiceForIdempotentReplay() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        OffsetDateTime elapsed = OffsetDateTime.parse("2020-01-01T00:00:00Z");
        MessagingContinuityService service = mock(MessagingContinuityService.class);
        var request = new MessagingContinuityDtos.ScheduleMessageRequest(
                "replay", requestKey, null, List.of(), List.of(), elapsed, "UTC");
        var response = new MessagingContinuityDtos.ScheduledMessageResponse(
                UUID.randomUUID(), conversationId, "replay", null, List.of(), elapsed, "UTC",
                "SENT", 1, null, null, UUID.randomUUID(), elapsed, 1,
                elapsed.minusDays(1), elapsed, "Conversation", null);
        when(service.schedule(conversationId, request, "corr-replay")).thenReturn(response);
        MockMvc mvc = standaloneSetup(new MessagingContinuityController(service)).build();

        mvc.perform(post("/v1/conversations/{conversationId}/scheduled-messages", conversationId)
                        .header("X-Correlation-ID", "corr-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"body":"replay","idempotencyKey":"%s",\
                                 "attachmentIds":[],"mentionedPersonPublicIds":[],\
                                 "scheduledFor":"%s","displayTimeZone":"UTC"}
                                """.formatted(requestKey, elapsed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduledMessageId")
                        .value(response.scheduledMessageId().toString()));
        verify(service).schedule(conversationId, request, "corr-replay");
    }
}
