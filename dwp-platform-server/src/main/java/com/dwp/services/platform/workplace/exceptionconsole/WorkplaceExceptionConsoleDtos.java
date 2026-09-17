package com.dwp.services.platform.workplace.exceptionconsole;

import com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WorkplaceExceptionConsoleDtos {
    private WorkplaceExceptionConsoleDtos() { }

    public enum ExceptionSource { SAFETY, BOOKING, CONNECTOR }
    public enum ExceptionSeverity { CRITICAL, ERROR, WARNING }
    public enum ExceptionStatus { ACTIVE, RECOVERING }
    public enum ExceptionAction { OPEN_SAFETY, REVIEW_BOOKING, REPLAY_CONNECTOR }
    public enum GuardrailStatus { HEALTHY, WARNING, BREACHED, UNKNOWN }

    public record ExceptionItem(
            String exceptionId,
            ExceptionSource source,
            ExceptionSeverity severity,
            ExceptionStatus status,
            String title,
            String impact,
            String code,
            OffsetDateTime detectedAt,
            long version,
            List<String> evidence,
            ExceptionAction action,
            String actionHref,
            WorkplaceConnectorOpsDtos.ConnectorKind connectorKind,
            Long configurationVersion,
            Long runtimeVersion) { }

    public record ExceptionSummary(
            int active,
            int critical,
            int warning,
            int error,
            long concurrencyConflicts24h,
            long deadLetterQueueDepth,
            BigDecimal automaticRecoveryPercent,
            BigDecimal slaCompliancePercent) { }

    public record Guardrail(
            String code,
            String name,
            String scope,
            String threshold,
            String observedValue,
            GuardrailStatus status,
            String enforcement) { }

    public record ExceptionConsole(
            ExceptionSummary summary,
            List<ExceptionItem> exceptions,
            List<Guardrail> guardrails,
            OffsetDateTime generatedAt,
            String externalTelemetryUrl) { }

    public record RecoveryPreviewRequest(
            @NotNull OffsetDateTime from,
            @NotNull OffsetDateTime to,
            @Min(1) int maximumRecords) { }

    public record RecoveryPreview(
            String exceptionId,
            WorkplaceConnectorOpsDtos.ReplayPreview replay,
            String impactSummary) { }

    public record RecoveryStartRequest(
            @NotNull UUID previewId,
            @Min(0) long configurationVersion,
            @Min(0) long runtimeVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record RecoveryReceipt(
            String exceptionId,
            WorkplaceConnectorOpsDtos.ReplayStartResponse recovery) { }

    public record ExportPreviewRequest(@NotBlank @Size(max = 500) String purpose) { }

    public record ExportPreview(
            UUID previewId,
            int rowCount,
            String purpose,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) { }

    public record ExportStartRequest(
            @NotNull UUID previewId,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ExportReceipt(
            UUID commandId,
            int rowCount,
            OffsetDateTime acceptedAt,
            OffsetDateTime expiresAt,
            String downloadHref,
            boolean idempotentReplay,
            String correlationId) { }
}
