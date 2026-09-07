package com.dwp.services.messaging.home;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.messaging.security.MessagingSecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class MessagingHomeAssetControllerTest {
    private final MessagingHomeAssetService service = mock(MessagingHomeAssetService.class);
    private final MockMvc mvc = standaloneSetup(new MessagingHomeAssetController(service))
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
            .addFilters(new MessagingSecurityFilter("home-test-token", new ObjectMapper().findAndRegisterModules()))
            .build();

    @Test
    void defaultAndExplicitLimitsReturnNoStoreEnvelope() throws Exception {
        when(service.recent(anyInt())).thenReturn(new MessagingHomeDtos.SharedAssetsResponse(
                OffsetDateTime.parse("2026-09-04T00:00:00Z"), List.of()));
        mvc.perform(auth(get("/v1/home/shared-assets")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items").isArray());
        mvc.perform(auth(get("/v1/home/shared-assets").param("limit", "20"))).andExpect(status().isOk());
        verify(service).recent(6);
        verify(service).recent(20);
    }

    @Test
    void rejectsMissingViewPermissionAndSpoofedIdentityBeforeDataService() throws Exception {
        mvc.perform(get("/v1/home/shared-assets").header("X-DWP-User-ID", "100"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/home/shared-assets").header("X-DWP-Service-Token", "home-test-token")
                        .header("X-DWP-User-ID", "100").header("X-DWP-Tenant-ID", "7"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("X-DWP-Service-Token", "home-test-token")
                .header("X-DWP-User-ID", "100").header("X-DWP-Tenant-ID", "7")
                .header("X-DWP-Permissions", "APP.MESSAGING:VIEW");
    }
}
