package com.dwp.services.approval.routingdirectory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.core.exception.GlobalExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkflowStudioControllerTest {
    private final WorkflowStudioEndpointService endpoints = mock(WorkflowStudioEndpointService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkflowStudioController(endpoints))
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();

    @Test
    void canvasDiffDryRunAndRetirementUseExactVersionFencedBoundaries() throws Exception {
        UUID workflowId = UUID.randomUUID();
        mvc.perform(get("/v1/admin/workflows/{id}/studio-v3", workflowId))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/admin/workflows/{id}/studio-v3/versions/diff", workflowId)
                        .param("fromVersion", "1").param("toVersion", "2"))
                .andExpect(status().isOk());
        String canvas = """
                {"expectedVersion":2,"definition":{"schemaContract":"DWP_APPROVAL_WORKFLOW_QUORUM_V2",
                 "schemaVersion":2,"slaMinutes":60,"stages":[{"key":"MANAGER","name":"Manager",
                 "candidateRole":"APPROVAL_MANAGER","quorum":{"mode":"ANY"},"slaMinutes":30,
                 "predecessors":[]}]}}
                """;
        mvc.perform(put("/v1/admin/workflows/{id}/studio-v3/canvas", workflowId)
                        .contentType(MediaType.APPLICATION_JSON).content(canvas)
                        .header("X-DWP-Expected-Object-Version", "2")
                        .header("Idempotency-Key", "canvas-save")
                        .header("X-DWP-Expected-Decision-Revision", "decision"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/admin/workflows/{id}/studio-v3/dry-run", workflowId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":3}")
                        .header("X-DWP-Expected-Object-Version", "3")
                        .header("Idempotency-Key", "canvas-dry-run")
                        .header("X-DWP-Expected-Decision-Revision", "decision"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/admin/workflows/{id}/studio-v3/retire", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":3,\"acknowledgedActiveReferences\":0}")
                        .header("X-DWP-Expected-Object-Version", "3")
                        .header("Idempotency-Key", "canvas-retire")
                        .header("X-DWP-Expected-Decision-Revision", "decision"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/workflows/{id}/studio-v3/retire", workflowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":3,\"acknowledgedActiveReferences\":0}")
                        .header("X-DWP-Expected-Object-Version", "3")
                        .header("X-DWP-Step-Up-Challenge", "signed")
                        .header("Idempotency-Key", "canvas-retire")
                        .header("X-DWP-Expected-Decision-Revision", "decision"))
                .andExpect(status().isOk());

        verify(endpoints).studio(workflowId);
        verify(endpoints).diff(workflowId, 1, 2);
        verify(endpoints).save(eq(workflowId), any(), any());
        verify(endpoints).dryRun(eq(workflowId), any(), any());
        verify(endpoints).retire(eq(workflowId), any(), any());
    }

    @Test
    void bodyHeaderVersionMismatchStopsBeforeService() throws Exception {
        UUID workflowId = UUID.randomUUID();
        mvc.perform(post("/v1/admin/workflows/{id}/studio-v3/dry-run", workflowId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":3}")
                        .header("X-DWP-Expected-Object-Version", "4")
                        .header("Idempotency-Key", "canvas-stale")
                        .header("X-DWP-Expected-Decision-Revision", "decision"))
                .andExpect(status().isConflict());
    }
}
