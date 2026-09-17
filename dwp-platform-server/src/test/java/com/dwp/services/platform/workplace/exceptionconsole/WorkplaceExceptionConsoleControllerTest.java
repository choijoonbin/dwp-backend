package com.dwp.services.platform.workplace.exceptionconsole;

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

import static com.dwp.services.platform.workplace.exceptionconsole.WorkplaceExceptionConsoleDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkplaceExceptionConsoleControllerTest {
    private final WorkplaceExceptionConsoleService service = mock(WorkplaceExceptionConsoleService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new WorkplaceExceptionConsoleController(service)).build();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void readIsNoStoreAndRequiresView() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T03:00:00Z");
        when(service.console(42)).thenReturn(new ExceptionConsole(
                new ExceptionSummary(0, 0, 0, 0, 0, 0, null, null),
                List.of(), List.of(), now, null));
        mvc.perform(get("/v1/admin/workplace/exceptions")
                        .header(WorkplaceExceptionConsoleController.TENANT, 42)
                        .header(WorkplaceExceptionConsoleController.PERMISSIONS,
                                "ADMIN.WORKPLACE:VIEW"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.summary.active").value(0));
    }

    @Test
    void exportUsesPreviewConfirmReceiptAndActorBoundDownload() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T03:00:00Z");
        UUID previewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        ExportPreviewRequest previewRequest = new ExportPreviewRequest("Incident review");
        ExportStartRequest startRequest = new ExportStartRequest(previewId, "Incident review", true);
        when(service.previewExport(42, 99, "preview-key", previewRequest, "corr"))
                .thenReturn(new ExportPreview(previewId, 2, "Incident review", now, now.plusMinutes(10)));
        when(service.startExport(42, 99, "export-key", startRequest, "corr"))
                .thenReturn(new ExportReceipt(commandId, 2, now, now.plusHours(1),
                        "/v1/admin/workplace/exceptions/exports/" + commandId + "/content",
                        false, "corr"));
        when(service.downloadExport(42, 99, commandId)).thenReturn("a,b\n1,2\n");

        mvc.perform(post("/v1/admin/workplace/exceptions/exports:preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WorkplaceExceptionConsoleController.TENANT, 42)
                        .header(WorkplaceExceptionConsoleController.USER, 99)
                        .header(WorkplaceExceptionConsoleController.PERMISSIONS,
                                "ADMIN.WORKPLACE:MANAGE")
                        .header(WorkplaceExceptionConsoleController.ACCESS_MODE, "ELEVATED")
                        .header(WorkplaceExceptionConsoleController.IDEMPOTENCY, "preview-key")
                        .header(WorkplaceExceptionConsoleController.CORRELATION, "corr")
                        .content(mapper.writeValueAsBytes(previewRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowCount").value(2));
        mvc.perform(post("/v1/admin/workplace/exceptions/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WorkplaceExceptionConsoleController.TENANT, 42)
                        .header(WorkplaceExceptionConsoleController.USER, 99)
                        .header(WorkplaceExceptionConsoleController.PERMISSIONS,
                                "ADMIN.WORKPLACE:MANAGE")
                        .header(WorkplaceExceptionConsoleController.ACCESS_MODE, "ELEVATED")
                        .header(WorkplaceExceptionConsoleController.IDEMPOTENCY, "export-key")
                        .header(WorkplaceExceptionConsoleController.CORRELATION, "corr")
                        .content(mapper.writeValueAsBytes(startRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commandId").value(commandId.toString()));
        mvc.perform(get("/v1/admin/workplace/exceptions/exports/{commandId}/content", commandId)
                        .header(WorkplaceExceptionConsoleController.TENANT, 42)
                        .header(WorkplaceExceptionConsoleController.USER, 99)
                        .header(WorkplaceExceptionConsoleController.PERMISSIONS,
                                "ADMIN.WORKPLACE:MANAGE")
                        .header(WorkplaceExceptionConsoleController.ACCESS_MODE, "ELEVATED"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
    }

    @Test
    void sensitiveCommandsRequireManageAndCurrentElevation() {
        assertThatThrownBy(() -> WorkplaceExceptionConsoleController.requirePermission(
                "ADMIN.WORKPLACE:VIEW", "ADMIN.WORKPLACE:MANAGE"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> WorkplaceExceptionConsoleController.requireElevated("NORMAL"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_REQUIRED));
    }
}
