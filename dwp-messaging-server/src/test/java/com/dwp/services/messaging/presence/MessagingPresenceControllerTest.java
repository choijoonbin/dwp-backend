package com.dwp.services.messaging.presence;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MessagingPresenceControllerTest {

    @Test
    void heartbeatReturnsNoContentAndRefreshesTheCurrentSubjectLease() throws Exception {
        MessagingPresenceService presence = mock(MessagingPresenceService.class);
        when(presence.heartbeat()).thenReturn(true);
        when(presence.leaseTiming())
                .thenReturn(new MessagingPresenceService.LeaseTiming(75, 20));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new MessagingPresenceController(presence))
                .build();

        mvc.perform(put("/v1/presence/heartbeat"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(
                        MessagingPresenceController.LEASE_TTL_HEADER, "75"))
                .andExpect(header().string(
                        MessagingPresenceController.REFRESH_AFTER_HEADER, "20"));

        verify(presence).heartbeat();
    }

    @Test
    void heartbeatReturnsServiceUnavailableWhenTheLeaseCannotBeRenewed() throws Exception {
        MessagingPresenceService presence = mock(MessagingPresenceService.class);
        when(presence.heartbeat()).thenReturn(false);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new MessagingPresenceController(presence))
                .build();

        mvc.perform(put("/v1/presence/heartbeat"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist(
                        MessagingPresenceController.LEASE_TTL_HEADER))
                .andExpect(header().doesNotExist(
                        MessagingPresenceController.REFRESH_AFTER_HEADER));
    }
}
