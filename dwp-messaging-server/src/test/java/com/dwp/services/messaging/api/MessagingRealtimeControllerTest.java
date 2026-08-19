package com.dwp.services.messaging.api;

import com.dwp.services.messaging.realtime.MessagingStreamService;
import com.dwp.services.messaging.realtime.MessagingTypingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MessagingRealtimeControllerTest {

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

}
