package com.dwp.services.platform.workplace.exceptionconsole;

import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceRepository;
import com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos;
import com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.exceptionconsole.WorkplaceExceptionConsoleDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class WorkplaceExceptionConsoleServiceTest {
    private final WorkplaceExceptionConsoleRepository repository =
            mock(WorkplaceExceptionConsoleRepository.class);
    private final WorkplaceConnectorOpsService connectors = mock(WorkplaceConnectorOpsService.class);
    private final WorkplaceSpatialGovernanceRepository audits =
            mock(WorkplaceSpatialGovernanceRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-17T03:00:00Z");
    private WorkplaceExceptionConsoleService service;

    @BeforeEach
    void setUp() {
        service = new WorkplaceExceptionConsoleService(repository, connectors, audits, objectMapper,
                Clock.fixed(Instant.parse("2026-09-17T03:00:00Z"), ZoneOffset.UTC),
                "http://unsafe.example.test");
        when(repository.activeSafety(42)).thenReturn(List.of());
        when(repository.bookingFailures(42)).thenReturn(List.of());
        when(repository.connectorFailures(42)).thenReturn(List.of());
        when(repository.recoveryStats(42)).thenReturn(
                new WorkplaceExceptionConsoleRepository.RecoveryStats(0, 0, 0));
    }

    @Test
    void aggregatesAuthoritativeSourcesWithoutInventingPercentagesOrTelemetryUrl() {
        UUID incidentId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        when(repository.activeSafety(42)).thenReturn(List.of(
                new WorkplaceExceptionConsoleRepository.SafetyRow(incidentId, "INC-7", "FIRE",
                        "CRITICAL", "ACTIVE", "Fire interlock", "Evacuate", now.minusMinutes(2), 3)));
        when(repository.bookingFailures(42)).thenReturn(List.of(
                new WorkplaceExceptionConsoleRepository.BookingRow(itemId, batchId,
                        "RESULT_UNKNOWN", "ERR_CONCURRENT_BOOKING_409", false, true,
                        now.minusMinutes(1), 4, "redacted")));
        when(repository.recoveryStats(42)).thenReturn(
                new WorkplaceExceptionConsoleRepository.RecoveryStats(1, 99, 100));

        ExceptionConsole result = service.console(42);

        assertThat(result.summary().active()).isEqualTo(2);
        assertThat(result.summary().critical()).isEqualTo(2);
        assertThat(result.summary().automaticRecoveryPercent()).isNull();
        assertThat(result.summary().slaCompliancePercent()).isNull();
        assertThat(result.externalTelemetryUrl()).isNull();
        assertThat(result.exceptions()).extracting(ExceptionItem::actionHref)
                .contains("/workplace/admin/safety?incident=" + incidentId,
                        "/workplace/planner?batch=" + batchId + "&step=RESULT");
        assertThat(service.detail(42, "safety:" + incidentId.toString().toUpperCase()))
                .extracting(ExceptionItem::exceptionId).isEqualTo("SAFETY:" + incidentId);
    }

    @Test
    void csvNeutralizesSpreadsheetFormulaCells() {
        when(repository.activeSafety(42)).thenReturn(List.of(
                new WorkplaceExceptionConsoleRepository.SafetyRow(UUID.randomUUID(), "INC-8", "FIRE",
                        "CRITICAL", "ACTIVE", "=HYPERLINK(\"https://bad\")", "+CMD", now, 1)));

        String csv = service.exportCsv(42);

        assertThat(csv).contains("\"'=HYPERLINK(\"\"https://bad\"\")\"");
        assertThat(csv).contains("\"'+CMD\"");
    }

    @Test
    void exportIsActorBoundIdempotentAuditedAndImmutableAfterPreview() {
        ExportPreview created = service.previewExport(42, 99, "preview-key",
                new ExportPreviewRequest("Incident review"), "corr");

        ArgumentCaptor<WorkplaceExceptionConsoleRepository.ExportPreviewRow> saved =
                ArgumentCaptor.forClass(WorkplaceExceptionConsoleRepository.ExportPreviewRow.class);
        verify(repository).saveExportPreview(saved.capture());
        assertThat(created.previewId()).isEqualTo(saved.getValue().previewId());
        assertThat(saved.getValue().contentSha256()).matches("[0-9a-f]{64}");
        when(repository.exportPreviewByIdempotency(42, 99, "preview-key"))
                .thenReturn(saved.getValue());
        assertThatThrownBy(() -> service.previewExport(42, 99, "preview-key",
                new ExportPreviewRequest("Different purpose"), "corr"))
                .hasMessageContaining("different export preview");
    }

    @Test
    void recoveryDelegatesOnlyARecoverableConnectorException() {
        when(repository.connectorFailures(42)).thenReturn(List.of(
                new WorkplaceExceptionConsoleRepository.ConnectorRow("CALENDAR", "msgraph", true,
                        4, "DEGRADED", now, now.minusMinutes(3), 12L, 2L, 3L,
                        "ADAPTER_DLQ", 7L, null, null)));
        UUID previewId = UUID.randomUUID();
        var replay = new WorkplaceConnectorOpsDtos.ReplayPreview(previewId,
                WorkplaceConnectorOpsDtos.ConnectorKind.CALENDAR, "msgraph", now.minusHours(1),
                now, true, 100, 3, true, List.of(), 4, 7, now.plusMinutes(10), now);
        when(connectors.preview(eq(42L), eq(99L),
                eq(WorkplaceConnectorOpsDtos.ConnectorKind.CALENDAR), eq("key"), any(), eq("corr")))
                .thenReturn(replay);

        RecoveryPreview result = service.preview(42, 99, "CONNECTOR_CALENDAR", "key",
                new RecoveryPreviewRequest(now.minusHours(1), now, 100), "corr");

        assertThat(result.replay()).isEqualTo(replay);
        assertThat(result.exceptionId()).isEqualTo("CONNECTOR:CALENDAR");
    }
}
