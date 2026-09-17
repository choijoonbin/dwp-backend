package com.dwp.services.approval.incidents;

import com.dwp.core.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IncidentControllerTest {
    private final IncidentEndpointService endpoints = mock(IncidentEndpointService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new IncidentController(endpoints))
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
            .build();

    @Test
    void mapsSuccessForbiddenAndUnavailableWithoutInventingAResponse() throws Exception {
        when(endpoints.incidents()).thenReturn(List.of());
        mvc.perform(get("/v1/admin/operations/incidents"))
                .andExpect(status().isOk());

        when(endpoints.incident(org.mockito.ArgumentMatchers.any()))
                .thenThrow(IncidentRejected.forbidden("revoked"));
        mvc.perform(get("/v1/admin/operations/incidents/{id}", UUID.randomUUID()))
                .andExpect(status().isForbidden());

        when(endpoints.plan(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(IncidentRejected.unavailable("authority unavailable"));
        mvc.perform(get("/v1/admin/operations/incidents/{id}/recovery-plans/{plan}",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void rejectsBodyHeaderVersionMismatch() throws Exception {
        UUID incidentId = UUID.randomUUID();
        mvc.perform(post("/v1/admin/operations/incidents/{id}/status", incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-DWP-Expected-Object-Version", "1")
                        .header("X-DWP-Step-Up-Challenge", "signed")
                        .header("Idempotency-Key", "incident-version")
                        .header("X-DWP-Expected-Decision-Revision", "decision")
                        .content("""
                                {"status":"INVESTIGATING","expectedVersion":2,
                                 "summary":"triage started","evidenceSha256":"%s"}
                                """.formatted("a".repeat(64))))
                .andExpect(status().isConflict());
    }

    @Test
    void crossScopeDetailFailsClosed() throws Exception {
        UUID incidentId = UUID.randomUUID();
        when(endpoints.incident(incidentId)).thenThrow(
                IncidentRejected.unavailable("not in selected resource set"));
        mvc.perform(get("/v1/admin/operations/incidents/{id}", incidentId))
                .andExpect(status().isServiceUnavailable());
    }
}
