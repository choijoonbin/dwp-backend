package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceSpacePlanningBoardReportDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission.CATALOG_MANAGE;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission.CATALOG_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkplaceSpacePlanningBoardReportControllerTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-17T03:00:00Z");
    private static final String REVISION = "psr-" + "a".repeat(64);
    private final WorkplaceSpacePlanningBoardReportService service =
            mock(WorkplaceSpacePlanningBoardReportService.class);
    private final WorkplaceDelegatedAdminScopeGuard scopeGuard =
            mock(WorkplaceDelegatedAdminScopeGuard.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new WorkplaceSpacePlanningBoardReportController(service, scopeGuard)).build();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void previewIsNoStoreAndRequiresDedicatedExportPermission() throws Exception {
        UUID site = UUID.randomUUID();
        UUID scenario = UUID.randomUUID();
        UUID preview = UUID.randomUUID();
        PreviewRequest input = new PreviewRequest(scenario, 4L, ReportFormat.PDF,
                "Prepare the aggregate board pack");
        when(scopeGuard.scope(any(), eq(site), eq(CATALOG_VIEW))).thenReturn(null);
        when(service.preview(eq(7L), eq(101L), eq(site), eq("preview-key"),
                eq(input), eq("corr"), isNull())).thenReturn(new ReportPreview(
                preview, scenario, site, null, ReportFormat.PDF, 4, 1,
                UUID.randomUUID().toString(), "b".repeat(64), snapshot(scenario, site),
                NOW, NOW.plusMinutes(15), false));

        mvc.perform(post("/v1/admin/workplace/space-planning/reports:preview")
                        .param("siteId", site.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WorkplaceSpacePlanningBoardReportController.TENANT, 7)
                        .header(WorkplaceSpacePlanningBoardReportController.USER, 101)
                        .header(WorkplaceSpacePlanningBoardReportController.PERMISSIONS,
                                WorkplaceSpacePlanningBoardReportController.EXPORT)
                        .header(WorkplaceSpacePlanningBoardReportController.IDEMPOTENCY,
                                "preview-key")
                        .header(WorkplaceSpacePlanningBoardReportController.CORRELATION, "corr")
                        .content(mapper.writeValueAsBytes(input)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.previewId").value(preview.toString()))
                .andExpect(jsonPath("$.data.snapshot.personLevelDataIncluded").value(false));
    }

    @Test
    void executeAndContentRequireElevationAndReturnRealMimeMetadata() throws Exception {
        UUID site = UUID.randomUUID();
        UUID scenario = UUID.randomUUID();
        UUID preview = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        ExecuteRequest input = new ExecuteRequest(preview, 1L, 4L, token,
                "Issue the approved aggregate board pack", true);
        ReportReceipt receipt = new ReportReceipt(command, preview, scenario, site, null,
                ReportFormat.PDF, ReportCommandState.SUCCEEDED, 4, 1,
                "application/pdf", "board-report.pdf", 9, "c".repeat(64),
                "/v1/admin/workplace/space-planning/reports/" + command + "/content",
                NOW, NOW, NOW.plusHours(24), false, "corr");
        when(scopeGuard.scope(any(), eq(site), eq(CATALOG_MANAGE))).thenReturn(null);
        when(service.execute(eq(7L), eq(101L), eq(site), eq("execute-key"), eq(input),
                eq("corr"), eq(REVISION), isNull())).thenReturn(receipt);
        when(service.content(eq(7L), eq(101L), eq(site), eq(command), eq("download-corr"),
                eq(REVISION), isNull())).thenReturn(
                new ReportContent(receipt, "%PDF-1.4".getBytes()));

        mvc.perform(post("/v1/admin/workplace/space-planning/reports")
                        .param("siteId", site.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WorkplaceSpacePlanningBoardReportController.TENANT, 7)
                        .header(WorkplaceSpacePlanningBoardReportController.USER, 101)
                        .header(WorkplaceSpacePlanningBoardReportController.PERMISSIONS,
                                WorkplaceSpacePlanningBoardReportController.EXPORT)
                        .header(WorkplaceSpacePlanningBoardReportController.ACCESS_MODE,
                                "ELEVATED")
                        .header(WorkplaceSpacePlanningBoardReportController.DECISION_REVISION,
                                REVISION)
                        .header(WorkplaceSpacePlanningBoardReportController.IDEMPOTENCY,
                                "execute-key")
                        .header(WorkplaceSpacePlanningBoardReportController.CORRELATION, "corr")
                        .content(mapper.writeValueAsBytes(input)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.commandId").value(command.toString()))
                .andExpect(jsonPath("$.data.mimeType").value("application/pdf"));

        mvc.perform(get("/v1/admin/workplace/space-planning/reports/{commandId}/content",
                        command)
                        .param("siteId", site.toString())
                        .header(WorkplaceSpacePlanningBoardReportController.TENANT, 7)
                        .header(WorkplaceSpacePlanningBoardReportController.USER, 101)
                        .header(WorkplaceSpacePlanningBoardReportController.PERMISSIONS,
                                WorkplaceSpacePlanningBoardReportController.EXPORT)
                        .header(WorkplaceSpacePlanningBoardReportController.ACCESS_MODE,
                                "ELEVATED")
                        .header(WorkplaceSpacePlanningBoardReportController.DECISION_REVISION,
                                REVISION)
                        .header(WorkplaceSpacePlanningBoardReportController.CORRELATION,
                                "download-corr"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(content().contentTypeCompatibleWith("application/pdf"))
                .andExpect(content().bytes("%PDF-1.4".getBytes()));
    }

    @Test
    void dedicatedPermissionAndCurrentElevationFailClosed() {
        assertThatThrownBy(() -> WorkplaceSpacePlanningBoardReportController
                .requirePermission("ADMIN.WORKPLACE:MANAGE"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> WorkplaceSpacePlanningBoardReportController
                .authorizeElevated(WorkplaceSpacePlanningBoardReportController.EXPORT, "NORMAL"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_REQUIRED));
    }

    private ReportSnapshot snapshot(UUID scenario, UUID site) {
        return new ReportSnapshot(scenario, 4, "Capacity plan", "APPROVED", site,
                "SEOUL", "Seoul", null, null, NOW, NOW.plusDays(30), 100, 92,
                20, 18, 4, 6, new BigDecimal("73.4"), new BigDecimal("79.1"),
                new BigDecimal("84"), new BigDecimal("92"), "READY", "forecast-v1",
                new BigDecimal("120"), "kWh", new BigDecimal("52"), "kgCO2e",
                "factor-v1", "KR", 8, 3, false, 0, NOW);
    }
}
