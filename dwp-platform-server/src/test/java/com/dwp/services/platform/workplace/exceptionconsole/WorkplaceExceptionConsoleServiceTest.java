package com.dwp.services.platform.workplace.exceptionconsole;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceRepository;
import com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos;
import com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

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
        when(repository.activeSafety(eq(42L), anyInt())).thenReturn(List.of());
        when(repository.bookingFailures(eq(42L), anyInt())).thenReturn(List.of());
        when(repository.connectorFailures(eq(42L), anyInt())).thenReturn(List.of());
        when(repository.deviceCommandFailures(eq(42L), anyInt())).thenReturn(List.of());
        when(repository.serviceOrderFailures(eq(42L), anyInt())).thenReturn(List.of());
        when(repository.serviceProviderFailures(eq(42L), anyInt())).thenReturn(List.of());
        when(repository.serviceRefundFailures(eq(42L), anyInt())).thenReturn(List.of());
        when(repository.visitProviderFailures(eq(42L), anyInt())).thenReturn(List.of());
        when(repository.accessPassFailures(eq(42L), anyInt())).thenReturn(List.of());
        when(repository.unresolvedCounts(42)).thenReturn(counts(0, 0, 0, 0, 0, 0, 0, 0, 0));
        when(repository.recoveryStats(42)).thenReturn(
                new WorkplaceExceptionConsoleRepository.RecoveryStats(0, 0, 0));
    }

    @Test
    void aggregatesAuthoritativeSourcesWithoutInventingPercentagesOrTelemetryUrl() {
        UUID incidentId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        var safety = new WorkplaceExceptionConsoleRepository.SafetyRow(incidentId, "INC-7", "FIRE",
                "CRITICAL", "ACTIVE", "Fire interlock", "Evacuate", now.minusMinutes(2), 3);
        var booking = new WorkplaceExceptionConsoleRepository.BookingRow(itemId, batchId,
                "RESULT_UNKNOWN", "ERR_CONCURRENT_BOOKING_409", false, true,
                now.minusHours(72), 4, "redacted");
        when(repository.activeSafety(eq(42L), anyInt())).thenReturn(List.of(safety));
        when(repository.bookingFailures(eq(42L), anyInt())).thenReturn(List.of(booking));
        when(repository.activeSafety(42, incidentId)).thenReturn(java.util.Optional.of(safety));
        when(repository.unresolvedCounts(42)).thenReturn(counts(1, 1, 0, 0, 0, 0, 0, 0, 0));
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
        assertThat(result.exceptions().stream()
                .filter(item -> item.source() == ExceptionSource.BOOKING)
                .findFirst().orElseThrow().ageSeconds()).isGreaterThan(24 * 60 * 60);
        assertThat(result.exceptions()).allSatisfy(item -> {
            assertThat(item.resolved()).isFalse();
            assertThat(item.resolvedAt()).isNull();
            assertThat(item.ageSeconds()).isPositive();
        });
    }

    @Test
    void includesOperationalUnknownAndRetryExhaustedWorkUntilResolved() {
        UUID deviceCommand = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID orderUnknown = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        UUID refund = UUID.randomUUID();
        UUID serviceOrder = UUID.randomUUID();
        UUID visitOutbox = UUID.randomUUID();
        UUID visit = UUID.randomUUID();
        UUID pass = UUID.randomUUID();
        UUID passSite = UUID.randomUUID();
        UUID passResource = UUID.randomUUID();
        var deviceRow = new WorkplaceExceptionConsoleRepository.OperationalRow(
                        deviceCommand, device, "REBOOT", "RESULT_UNKNOWN",
                        "PROVIDER_TIMEOUT", now.minusHours(30), 3, 10, "RESULT_UNKNOWN",
                        null, null);
        when(repository.deviceCommandFailures(eq(42L), anyInt())).thenReturn(List.of(deviceRow));
        when(repository.deviceCommandFailure(42, deviceCommand))
                .thenReturn(java.util.Optional.of(deviceRow));
        when(repository.serviceOrderFailures(eq(42L), anyInt())).thenReturn(List.of(
                new WorkplaceExceptionConsoleRepository.OperationalRow(
                        orderUnknown, reservation, "WORKPLACE", "RESULT_UNKNOWN",
                        "SERVICE_ORDER_RESULT_UNKNOWN", now.minusHours(29), 4, 0,
                        "RECONFIRMATION_REQUIRED", null, null)));
        when(repository.serviceProviderFailures(eq(42L), anyInt())).thenReturn(List.of(
                new WorkplaceExceptionConsoleRepository.ServiceProviderRow(
                        "catering-primary", "UNAVAILABLE", "PROVIDER_UNAVAILABLE",
                        now.minusHours(28), now.minusHours(28), 7, 6L, 9)));
        when(repository.serviceRefundFailures(eq(42L), anyInt())).thenReturn(List.of(
                new WorkplaceExceptionConsoleRepository.OperationalRow(
                        refund, serviceOrder, "CANCEL_REFUND", "REFUND_NOT_CONFIGURED",
                        null, now.minusHours(26), 2, 1, "FULL", null, null)));
        when(repository.visitProviderFailures(eq(42L), anyInt())).thenReturn(List.of(
                new WorkplaceExceptionConsoleRepository.OperationalRow(
                        visitOutbox, visit, "REQUEST_ACCESS", "DEAD_LETTER",
                        "ADAPTER_UNAVAILABLE", now.minusHours(25), 5, 7, "RESULT_UNKNOWN",
                        null, null)));
        when(repository.accessPassFailures(eq(42L), anyInt())).thenReturn(List.of(
                new WorkplaceExceptionConsoleRepository.OperationalRow(
                        pass, pass, "PAIR", "PAIRING_RETRY_EXHAUSTED",
                        "ACCESS_PASS_PAIRING_RETRY_EXHAUSTED", now.minusHours(24),
                        6, 5, "PAIRING", passSite, passResource)));
        when(repository.unresolvedCounts(42)).thenReturn(counts(0, 0, 0, 1, 1, 1, 1, 1, 1));

        ExceptionConsole result = service.console(42);

        assertThat(result.exceptions()).extracting(ExceptionItem::source)
                .containsExactlyInAnyOrder(ExceptionSource.DEVICE_COMMAND,
                        ExceptionSource.SERVICE_ORDER, ExceptionSource.SERVICE_PROVIDER,
                        ExceptionSource.SERVICE_REFUND, ExceptionSource.VISIT_PROVIDER,
                        ExceptionSource.ACCESS_PASS);
        assertThat(result.exceptions()).allSatisfy(item -> {
            assertThat(item.ageSeconds()).isGreaterThanOrEqualTo(24 * 60 * 60);
            assertThat(item.resolved()).isFalse();
            assertThat(item.resolvedAt()).isNull();
        });
        assertThat(result.exceptions().stream()
                .filter(item -> item.source() != ExceptionSource.SERVICE_ORDER
                        && item.source() != ExceptionSource.SERVICE_PROVIDER))
                .allMatch(item -> item.status() == ExceptionStatus.MANUAL_REVIEW);
        assertThat(result.exceptions()).extracting(ExceptionItem::actionHref)
                .contains(
                        "/workplace/admin/devices?device=" + device
                                + "&command=" + deviceCommand,
                        "/workplace/admin/service-fulfillment?order=" + orderUnknown,
                        "/workplace/admin/service-fulfillment?order=" + serviceOrder
                                + "&adjustment=" + refund,
                        "/workplace/admin/visits?visit=" + visit,
                        "/workplace/admin/service-providers?provider=catering-primary");
        assertThat(result.exceptions().stream()
                .filter(item -> item.source() == ExceptionSource.ACCESS_PASS)
                .findFirst().orElseThrow()).satisfies(item -> {
                    assertThat(item.actionHref()).isNull();
                    assertThat(item.evidence()).contains("PassId=" + pass);
                });
        assertThat(service.detail(42, "DEVICE_COMMAND:" + deviceCommand)
                .exceptionId()).isEqualTo("DEVICE_COMMAND:" + deviceCommand);
    }

    @Test
    void csvNeutralizesSpreadsheetFormulaCells() {
        when(repository.activeSafety(eq(42L), anyInt())).thenReturn(List.of(
                new WorkplaceExceptionConsoleRepository.SafetyRow(UUID.randomUUID(), "INC-8", "FIRE",
                        "CRITICAL", "ACTIVE", "=HYPERLINK(\"https://bad\")", "+CMD", now, 1)));
        when(repository.unresolvedCounts(42)).thenReturn(counts(1, 0, 0, 0, 0, 0, 0, 0, 0));

        String csv = service.exportCsv(42);

        assertThat(csv).contains("\"'=HYPERLINK(\"\"https://bad\"\")\"");
        assertThat(csv).contains("\"'+CMD\"");
    }

    @Test
    void reportsExactCountsAndTruncationAtThe101And251Boundaries() {
        var safety = new WorkplaceExceptionConsoleRepository.SafetyRow(
                UUID.randomUUID(), "INC-LIMIT", "FIRE", "CRITICAL", "ACTIVE",
                "Review", "Evacuate", now.minusMinutes(5), 1);
        var booking = new WorkplaceExceptionConsoleRepository.BookingRow(
                UUID.randomUUID(), UUID.randomUUID(), "RESULT_UNKNOWN", "PROVIDER_TIMEOUT",
                false, true, now.minusMinutes(4), 1, "redacted");
        when(repository.activeSafety(42, 100))
                .thenReturn(java.util.Collections.nCopies(100, safety));
        when(repository.bookingFailures(42, 250))
                .thenReturn(java.util.Collections.nCopies(250, booking));
        when(repository.unresolvedCounts(42))
                .thenReturn(counts(101, 251, 0, 0, 0, 0, 0, 0, 0));

        ExceptionConsole result = service.console(42);

        assertThat(result.summary().unresolvedCount()).isEqualTo(352);
        assertThat(result.summary().returnedCount()).isEqualTo(350);
        assertThat(result.summary().truncated()).isTrue();
        assertThat(result.sourceCoverage()).filteredOn(c -> c.source() == ExceptionSource.SAFETY)
                .singleElement().satisfies(c -> {
                    assertThat(c.unresolvedCount()).isEqualTo(101);
                    assertThat(c.returnedCount()).isEqualTo(100);
                    assertThat(c.truncated()).isTrue();
                });
        assertThat(result.sourceCoverage()).filteredOn(c -> c.source() == ExceptionSource.BOOKING)
                .singleElement().satisfies(c -> {
                    assertThat(c.unresolvedCount()).isEqualTo(251);
                    assertThat(c.returnedCount()).isEqualTo(250);
                    assertThat(c.truncated()).isTrue();
                });
    }

    @Test
    void consoleReadsCountsAndRowsFromOneRepeatableReadSnapshot() throws Exception {
        Transactional transaction = WorkplaceExceptionConsoleService.class
                .getMethod("console", long.class).getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    void detailDirectlyLoadsAnExceptionOmittedFromTheBoundedConsole() {
        UUID omittedId = UUID.randomUUID();
        var omitted = new WorkplaceExceptionConsoleRepository.SafetyRow(
                omittedId, "INC-OMITTED", "SECURITY", "CRITICAL", "ACTIVE",
                "Direct lookup", "Review", now.minusHours(2), 7);
        when(repository.activeSafety(42, omittedId)).thenReturn(java.util.Optional.of(omitted));

        assertThat(service.detail(42, "SAFETY:" + omittedId))
                .extracting(ExceptionItem::exceptionId, ExceptionItem::version)
                .containsExactly("SAFETY:" + omittedId, 7L);
        verify(repository, never()).unresolvedCounts(42);
    }

    @Test
    void exportExplicitlyRejectsMoreThan250UnresolvedRows() {
        when(repository.unresolvedCounts(42))
                .thenReturn(counts(251, 0, 0, 0, 0, 0, 0, 0, 0));

        assertThatThrownBy(() -> service.exportCsv(42))
                .isInstanceOfSatisfying(BaseException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    assertThat(error).hasMessageContaining("EXPORT_ROW_LIMIT_EXCEEDED")
                            .hasMessageContaining("unresolvedCount=251")
                            .hasMessageContaining("maximumRows=250");
                });
        verify(repository, never()).activeSafety(42, 251);
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
        var connector = new WorkplaceExceptionConsoleRepository.ConnectorRow(
                "CALENDAR", "msgraph", true, 4, "DEGRADED", now, now.minusMinutes(3),
                12L, 2L, 3L, "ADAPTER_DLQ", 7L, null, null);
        when(repository.connectorFailure(42, "CALENDAR"))
                .thenReturn(java.util.Optional.of(connector));
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

    private static WorkplaceExceptionConsoleRepository.SourceCounts counts(
            long safety, long booking, long connector, long deviceCommand, long serviceOrder,
            long serviceProvider, long serviceRefund, long visitProvider, long accessPass) {
        return new WorkplaceExceptionConsoleRepository.SourceCounts(
                safety, booking, connector, deviceCommand, serviceOrder, serviceProvider,
                serviceRefund, visitProvider, accessPass);
    }
}
