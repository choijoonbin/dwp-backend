package com.dwp.services.platform.workplace.connectorops;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkplaceConnectorOpsControllerTest {
    private final WorkplaceConnectorOpsService service = mock(WorkplaceConnectorOpsService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new WorkplaceConnectorOpsController(service)).build();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void operationsAndDetailAreNoStoreTenantScopedReads() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T03:00:00Z");
        ConnectorRuntimeTruth truth = new ConnectorRuntimeTruth(ConnectorKind.CALENDAR,
                "msgraph", true, RuntimeState.HEALTHY, ProviderReportedState.HEALTHY,
                List.of(Capability.HEALTH), 4, 4L, 7L, now.minusSeconds(2), now,
                now.minusSeconds(2), 2L, "opaque", 0L, 0L, null, null, now);
        when(service.operations(42)).thenReturn(new ConnectorOperations(List.of(truth), now));
        when(service.detail(42, ConnectorKind.CALENDAR)).thenReturn(truth);

        mvc.perform(get("/v1/admin/workplace/connectors/operations")
                        .header(WorkplaceConnectorOpsController.TENANT, 42)
                        .header(WorkplaceConnectorOpsController.PERMISSIONS, "ADMIN.WORKPLACE:VIEW"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.connectors[0].state").value("HEALTHY"));
        mvc.perform(get("/v1/admin/workplace/connectors/CALENDAR/operations")
                        .header(WorkplaceConnectorOpsController.TENANT, 42)
                        .header(WorkplaceConnectorOpsController.PERMISSIONS, "ADMIN.WORKPLACE:VIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtimeVersion").value(7));
    }

    @Test
    void replayStartReturnsReceiptLocationAndRequiresManagePlusElevated() throws Exception {
        UUID previewId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T03:00:00Z");
        ReplayStartRequest request = new ReplayStartRequest(previewId, 4, 7, "Recover", true);
        ReplayJob job = new ReplayJob(jobId, previewId, ConnectorKind.CALENDAR, "msgraph",
                ReplayState.QUEUED, "Recover", null, null, 4, 7, 1, now,
                null, null, now);
        String href = "/v1/admin/workplace/connectors/CALENDAR/replays/" + jobId;
        when(service.startReplay(42, 99, ConnectorKind.CALENDAR, "idem-1", request, "corr-1"))
                .thenReturn(new ReplayStartResponse(job,
                        new CommandReceipt(jobId, ReplayState.QUEUED, now, href, false, "corr-1")));

        mvc.perform(post("/v1/admin/workplace/connectors/CALENDAR/replays")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WorkplaceConnectorOpsController.TENANT, 42)
                        .header(WorkplaceConnectorOpsController.USER, 99)
                        .header(WorkplaceConnectorOpsController.PERMISSIONS, "ADMIN.WORKPLACE:MANAGE")
                        .header(WorkplaceConnectorOpsController.ACCESS_MODE, "ELEVATED")
                        .header(WorkplaceConnectorOpsController.IDEMPOTENCY, "idem-1")
                        .header(WorkplaceConnectorOpsController.CORRELATION, "corr-1")
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", href))
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.receipt.state").value("QUEUED"));

        assertThatThrownBy(() -> WorkplaceConnectorOpsController.requirePermission(
                "ADMIN.WORKPLACE:CREATE", "ADMIN.WORKPLACE:MANAGE"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> WorkplaceConnectorOpsController.requireElevated("NORMAL"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_REQUIRED));
    }

    @Test
    void replayPreviewAndStatusRequireManagePlusElevated() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T03:00:00Z");
        UUID previewId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ReplayPreviewRequest request = new ReplayPreviewRequest(
                now.minusHours(1), now, true, 500, 4, 7);
        ReplayPreview preview = new ReplayPreview(
                previewId, ConnectorKind.CALENDAR, "msgraph",
                request.from(), request.to(), true, 500, 27, true, List.of(),
                4, 7, now.plusMinutes(10), now);
        ReplayJob job = new ReplayJob(
                jobId, previewId, ConnectorKind.CALENDAR, "msgraph",
                ReplayState.RESULT_UNKNOWN, "Recover", "provider-op-7", null,
                4, 7, 3, now.minusMinutes(3), now.minusMinutes(2), null, now);
        when(service.preview(42, 99, ConnectorKind.CALENDAR, "preview-idem-1", request, "corr-preview"))
                .thenReturn(preview);
        when(service.replay(42, ConnectorKind.CALENDAR, jobId)).thenReturn(job);

        mvc.perform(post("/v1/admin/workplace/connectors/CALENDAR/replays:preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WorkplaceConnectorOpsController.TENANT, 42)
                        .header(WorkplaceConnectorOpsController.USER, 99)
                        .header(WorkplaceConnectorOpsController.PERMISSIONS,
                                "ADMIN.WORKPLACE:MANAGE")
                        .header(WorkplaceConnectorOpsController.ACCESS_MODE, "ELEVATED")
                        .header(WorkplaceConnectorOpsController.IDEMPOTENCY, "preview-idem-1")
                        .header(WorkplaceConnectorOpsController.CORRELATION, "corr-preview")
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.previewId").value(previewId.toString()));
        verify(service).preview(42, 99, ConnectorKind.CALENDAR,
                "preview-idem-1", request, "corr-preview");

        mvc.perform(get("/v1/admin/workplace/connectors/CALENDAR/replays/{jobId}", jobId)
                        .header(WorkplaceConnectorOpsController.TENANT, 42)
                        .header(WorkplaceConnectorOpsController.PERMISSIONS,
                                "ADMIN.WORKPLACE:MANAGE")
                        .header(WorkplaceConnectorOpsController.ACCESS_MODE, "ELEVATED"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.state").value("RESULT_UNKNOWN"));
    }
}
