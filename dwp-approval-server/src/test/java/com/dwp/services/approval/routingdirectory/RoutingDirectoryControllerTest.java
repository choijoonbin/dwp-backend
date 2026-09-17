package com.dwp.services.approval.routingdirectory;

import com.dwp.core.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RoutingDirectoryControllerTest {
    private final RoutingDirectoryEndpointService endpoints =
            mock(RoutingDirectoryEndpointService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new RoutingDirectoryController(endpoints))
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
            .build();

    @Test
    void mapsSuccessForbiddenAndUnavailableWithoutInventingAResponse() throws Exception {
        when(endpoints.groups()).thenReturn(List.of());
        mvc.perform(get("/v1/admin/workflows/routing-directory/groups"))
                .andExpect(status().isOk());

        when(endpoints.resolvers()).thenThrow(RoutingDirectoryRejected.forbidden("revoked"));
        mvc.perform(get("/v1/admin/workflows/routing-directory/resolvers"))
                .andExpect(status().isForbidden());

        when(endpoints.impact(org.mockito.ArgumentMatchers.any()))
                .thenThrow(RoutingDirectoryRejected.unavailable("authority unavailable"));
        mvc.perform(get("/v1/admin/workflows/routing-directory/groups/{id}/retirement-impact",
                        UUID.randomUUID()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void rejectsBodyHeaderVersionMismatch() throws Exception {
        UUID groupId = UUID.randomUUID();
        mvc.perform(put("/v1/admin/workflows/routing-directory/groups/{id}", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-DWP-Expected-Object-Version", "1")
                        .header("X-DWP-Step-Up-Challenge", "signed")
                        .header("Idempotency-Key", "routing-version")
                        .header("X-DWP-Expected-Decision-Revision", "decision")
                        .content("""
                                {"groupId":"%s","groupKey":"GROUP.TEST","displayName":"Test",
                                 "description":"","lifecycle":"DRAFT",
                                 "effectiveFrom":"2026-09-16T00:00:00Z","members":[],
                                 "expectedVersion":2}
                                """.formatted(groupId)))
                .andExpect(status().isConflict());
    }

    @Test
    void crossScopeDetailFailsClosed() throws Exception {
        UUID groupId = UUID.randomUUID();
        when(endpoints.group(groupId)).thenThrow(
                RoutingDirectoryRejected.unavailable("not in selected resource set"));
        mvc.perform(get("/v1/admin/workflows/routing-directory/groups/{id}", groupId))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void lowRiskUpdateDoesNotRequireStepUpHeader() throws Exception {
        UUID groupId = UUID.randomUUID();
        mvc.perform(put("/v1/admin/workflows/routing-directory/groups/{id}", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-DWP-Expected-Object-Version", "1")
                        .header("Idempotency-Key", "routing-low-risk")
                        .header("X-DWP-Expected-Decision-Revision", "decision")
                        .content("""
                                {"groupId":"%s","groupKey":"GROUP.TEST",
                                 "displayName":"Test","description":"",
                                 "lifecycle":"DRAFT",
                                 "effectiveFrom":"2026-09-16T00:00:00Z","members":[],
                                 "expectedVersion":1}
                                """.formatted(groupId)))
                .andExpect(status().isOk());
        verify(endpoints).saveGroup(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.argThat(headers ->
                        headers.challenge() == null
                                && "routing-low-risk".equals(headers.idempotencyKey())));
    }

    @Test
    void highRiskPublishAndRetireStillRequireStepUpHeader() throws Exception {
        UUID groupId = UUID.randomUUID();
        mvc.perform(post("/v1/admin/workflows/routing-directory/groups/{id}/publish", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-DWP-Expected-Object-Version", "1")
                        .header("Idempotency-Key", "routing-publish")
                        .header("X-DWP-Expected-Decision-Revision", "decision")
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/workflows/routing-directory/groups/{id}/retire", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-DWP-Expected-Object-Version", "1")
                        .header("Idempotency-Key", "routing-retire")
                        .header("X-DWP-Expected-Decision-Revision", "decision")
                        .content("{\"expectedVersion\":1,\"acknowledgedImpact\":0}"))
                .andExpect(status().isBadRequest());
    }
}
