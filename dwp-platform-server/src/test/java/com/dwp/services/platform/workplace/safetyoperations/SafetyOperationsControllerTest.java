package com.dwp.services.platform.workplace.safetyoperations;

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

import static com.dwp.services.platform.workplace.safetyoperations.SafetyAdminController.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyUserController.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SafetyOperationsControllerTest {
    private final SafetyOperationsService operations = mock(SafetyOperationsService.class);
    private final SafetyConnectorService connectors = mock(SafetyConnectorService.class);
    private final SafetyPreviewService previews = mock(SafetyPreviewService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new SafetyUserController(operations),
            new SafetyAdminController(operations, connectors, previews)).build();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-16T12:00:00Z");

    @Test
    void persistedActivationPreviewRequiresCommandHeadersAndReturnsReceiptLocation()
            throws Exception {
        UUID previewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        CommandReceipt receipt = receipt(commandId,
                "/v1/admin/workplace/safety/activation-previews/" + previewId);
        AudienceSnapshot audience = new AudienceSnapshot(UUID.randomUUID(), 1, 1, 0, 0, 1,
                List.of(), List.of(), now);
        ActivationPreview preview = new ActivationPreview(previewId, "FIRE", Severity.CRITICAL,
                siteId, List.of(floorId), List.of(), "Evacuate", "Use north exit", "Lot A",
                List.of(DeliveryChannel.APP_PUSH), audience, List.of(), true, List.of(),
                now.plusMinutes(10), now);
        when(previews.activation(eq(42L), eq(7L), eq("preview-key"), any(), eq("corr-20")))
                .thenReturn(new ActivationPreviewResult(preview, receipt));

        mvc.perform(post("/v1/admin/workplace/safety/incidents:preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7)
                        .header(PERMISSIONS, ADMIN_MANAGE).header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "preview-key").header(CORRELATION, "corr-20")
                        .content(mapper.writeValueAsBytes(new ActivationPreviewRequest(
                                "FIRE", Severity.CRITICAL, siteId, List.of(floorId), List.of(),
                                "Evacuate", "Use north exit", "Lot A",
                                List.of(DeliveryChannel.APP_PUSH), List.of(),
                                "Validate audience", true))))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", receipt.statusHref()))
                .andExpect(jsonPath("$.data.preview.activationPreviewId")
                        .value(previewId.toString()))
                .andExpect(jsonPath("$.data.receipt.state").value("SUCCEEDED"));
    }

    @Test
    void assemblyConfirmationAndUserSafetyResponseRemainSeparateCommands() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID assemblyId = UUID.randomUUID();
        String subject = "a".repeat(64);
        AssemblyConfirmation confirmation = new AssemblyConfirmation(assemblyId, incidentId,
                subject, 19L, true, now, 7, "camera-log:7", 1, now);
        when(operations.confirmAssembly(eq(42L), eq(7L), eq(incidentId), eq("assembly-key"),
                any(), eq("corr-assembly"))).thenReturn(new AssemblyCommandResult(confirmation,
                receipt(UUID.randomUUID(), "/v1/admin/workplace/safety/incidents/" + incidentId)));
        SafetySheet sheet = new SafetySheet(incidentId, "INC-20", Severity.CRITICAL,
                "Evacuate", "Use north exit", "Lot A", List.of("SITE:1"),
                SafetyResponseState.SAFE, "Call security", 3, now);
        when(operations.respond(eq(42L), eq(19L), eq(incidentId), eq("response-key"),
                any(), eq("corr-response"))).thenReturn(new ResponseCommandResult(sheet,
                receipt(UUID.randomUUID(), "/v1/workplace/safety/incidents/" + incidentId)));

        mvc.perform(post("/v1/admin/workplace/safety/incidents/{id}/assembly-confirmations",
                        incidentId).contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7)
                        .header(PERMISSIONS, ADMIN_MANAGE).header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "assembly-key").header(CORRELATION, "corr-assembly")
                        .content(mapper.writeValueAsBytes(new AssemblyConfirmationRequest(
                                3, subject, 19L, true, now, "camera-log:7", 0,
                                "Observed at assembly point", true))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.confirmation.confirmed").value(true));

        mvc.perform(post("/v1/workplace/safety/incidents/{id}/responses", incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 19)
                        .header(PERMISSIONS, UPDATE).header(IDEMPOTENCY, "response-key")
                        .header(CORRELATION, "corr-response")
                        .content(mapper.writeValueAsBytes(new SafetyResponseRequest(
                                3, SafetyResponseState.SAFE, null, "I am safe", true))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.sheet.currentResponse").value("SAFE"));
    }

    @Test
    void adminMutationAndExportFailClosedWithoutPermissionOrStepUp() {
        assertThatThrownBy(() -> authorizeMutation(ADMIN_VIEW, "ELEVATED"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> authorizeMutation(ADMIN_MANAGE, "NORMAL"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_REQUIRED));
        assertThatThrownBy(() -> authorizeExport(ADMIN_EXPORT, "NORMAL"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_REQUIRED));
        assertThatThrownBy(() -> requireDecisionRevision("psr-invalid"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_REQUIRED));
    }

    @Test
    void exportPreservesVerifiedDecisionRevisionAsStepUpEvidence() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID exportId = UUID.randomUUID();
        String revision = "psr-" + "a".repeat(64);
        GuardedExport export = new GuardedExport(exportId, incidentId, ExportFormat.PDF,
                "Review", "Evidence", 7L, "corr-export", revision, "application/pdf",
                "b".repeat(64), 100, "/v1/admin/workplace/safety/exports/" + exportId
                + "/content", now, now.plusMinutes(15));
        when(operations.createExport(eq(42L), eq(7L), eq(incidentId), eq("export-key"),
                any(), eq("corr-export"), eq(revision))).thenReturn(new ExportCommandResult(
                export, receipt(UUID.randomUUID(), export.downloadHref())));

        mvc.perform(post("/v1/admin/workplace/safety/incidents/{id}/exports", incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7)
                        .header(PERMISSIONS, ADMIN_EXPORT).header(ACCESS_MODE, "ELEVATED")
                        .header(DECISION_REVISION, revision).header(IDEMPOTENCY, "export-key")
                        .header(CORRELATION, "corr-export")
                        .content(mapper.writeValueAsBytes(new GuardedExportRequest(
                                4, ExportFormat.PDF, "Review", "Evidence", true))))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", export.downloadHref()))
                .andExpect(jsonPath("$.data.export.stepUpEvidence").value(revision));
    }

    private CommandReceipt receipt(UUID id, String href) {
        return new CommandReceipt(id, CommandState.SUCCEEDED, href, false, "corr", now);
    }
}
