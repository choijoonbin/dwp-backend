package com.dwp.services.messaging.api;

import com.dwp.services.messaging.realtime.MessagingStreamService;
import com.dwp.services.messaging.realtime.MessagingTypingService;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MessagingRealtimeControllerTest {

    @AfterEach
    void clearRequestContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void streamUsesTheQueryCursorBeforeTheLastEventIdHeader() {
        MessagingStreamService streams = mock(MessagingStreamService.class);
        MessagingRequestContext.Subject subject = subject();
        MessagingRequestContext.set(subject);
        MessagingRealtimeController controller = new MessagingRealtimeController(
                streams, mock(MessagingTypingService.class));

        controller.stream("42", "41");

        verify(streams).open(subject, "42");
    }

    @Test
    void streamFallsBackToTheLastEventIdHeader() {
        MessagingStreamService streams = mock(MessagingStreamService.class);
        MessagingRequestContext.Subject subject = subject();
        MessagingRequestContext.set(subject);
        MessagingRealtimeController controller = new MessagingRealtimeController(
                streams, mock(MessagingTypingService.class));

        controller.stream(null, "41");

        verify(streams).open(subject, "41");
    }

    @Test
    void typingEndpointReturnsNoContentAndDelegatesTheValidatedContract() throws Exception {
        MessagingTypingService typing = mock(MessagingTypingService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MessagingRealtimeController(
                        mock(MessagingStreamService.class), typing))
                .build();
        UUID conversationId = UUID.randomUUID();

        mvc.perform(post("/v1/conversations/{conversationId}/typing", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"started\":true}"))
                .andExpect(status().isNoContent());

        verify(typing).change(conversationId, true);
    }

    @Test
    void typingEndpointRejectsAnOmittedState() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MessagingRealtimeController(
                        mock(MessagingStreamService.class), mock(MessagingTypingService.class)))
                .build();

        mvc.perform(post("/v1/conversations/{conversationId}/typing", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private MessagingRequestContext.Subject subject() {
        return new MessagingRequestContext.Subject(
                101, 7, UUID.randomUUID(), "Test User",
                Set.of("WORKSPACE_MEMBER"), Set.of("APP.MESSAGING:VIEW"), Set.of());
    }

}
