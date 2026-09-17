package com.dwp.services.approval.connectors;

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

class ConnectorControllerTest {
    private final ConnectorEndpointService endpoints = mock(ConnectorEndpointService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ConnectorController(endpoints))
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
            .build();

    @Test
    void mapsSuccessForbiddenAndUnavailableWithoutInventingAResponse() throws Exception {
        when(endpoints.connectors()).thenReturn(List.of());
        mvc.perform(get("/v1/admin/operations/connectors"))
                .andExpect(status().isOk());

        when(endpoints.connector(org.mockito.ArgumentMatchers.any()))
                .thenThrow(ConnectorRejected.forbidden("revoked"));
        mvc.perform(get("/v1/admin/operations/connectors/{id}", UUID.randomUUID()))
                .andExpect(status().isForbidden());

        when(endpoints.probe(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(ConnectorRejected.unavailable("authority unavailable"));
        mvc.perform(get("/v1/admin/operations/connectors/{id}/probes/{probe}",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void rejectsBodyHeaderVersionMismatch() throws Exception {
        UUID connectorId = UUID.randomUUID();
        mvc.perform(post("/v1/admin/operations/connectors/{id}/lifecycle", connectorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-DWP-Expected-Object-Version", "1")
                        .header("X-DWP-Step-Up-Challenge", "signed")
                        .header("Idempotency-Key", "connector-version")
                        .header("X-DWP-Expected-Decision-Revision", "decision")
                        .content("""
                                {"lifecycle":"DISABLED","expectedVersion":2,"reason":"maintenance"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void crossScopeDetailFailsClosed() throws Exception {
        UUID connectorId = UUID.randomUUID();
        when(endpoints.connector(connectorId)).thenThrow(
                ConnectorRejected.unavailable("not in selected resource set"));
        mvc.perform(get("/v1/admin/operations/connectors/{id}", connectorId))
                .andExpect(status().isServiceUnavailable());
    }
}
