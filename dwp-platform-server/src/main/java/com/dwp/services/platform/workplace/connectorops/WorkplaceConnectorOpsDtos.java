package com.dwp.services.platform.workplace.connectorops;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WorkplaceConnectorOpsDtos {
    private WorkplaceConnectorOpsDtos() { }

    public enum ConnectorKind {
        CALENDAR,
        ACTUAL_PRESENCE,
        ACCESS_CONTROL,
        SIGNAGE,
        VISITOR,
        VEHICLE,
        FACILITY_WORK_ORDER
    }

    public enum ProviderReportedState { HEALTHY, DEGRADED, UNAVAILABLE }

    /** A state derived by DWP. Provider payloads can never set this value directly. */
    public enum RuntimeState {
        NOT_CONFIGURED,
        DISABLED,
        CONFIGURED_UNVERIFIED,
        HEALTHY,
        DEGRADED,
        STALE,
        REPLAYING
    }

    public enum Capability { HEALTH, CHECKPOINT, RETRY_QUEUE, REPLAY }

    public enum ReplayState {
        QUEUED,
        DISPATCHING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        RESULT_UNKNOWN
    }

    public record ConnectorRuntimeTruth(
            ConnectorKind kind,
            String provider,
            boolean enabled,
            RuntimeState state,
            ProviderReportedState providerReportedState,
            List<Capability> capabilities,
            long configurationVersion,
            Long observedConfigurationVersion,
            Long runtimeVersion,
            OffsetDateTime sourceObservedAt,
            OffsetDateTime receivedAt,
            OffsetDateTime lastSuccessAt,
            Long lagSeconds,
            String checkpointReference,
            Long retryQueueDepth,
            Long deadLetterQueueDepth,
            String errorCode,
            UUID activeReplayJobId,
            OffsetDateTime evaluatedAt) { }

    public record ConnectorOperations(
            List<ConnectorRuntimeTruth> connectors,
            OffsetDateTime generatedAt) { }

    public record ReplayPreviewRequest(
            @NotNull OffsetDateTime from,
            @NotNull OffsetDateTime to,
            boolean failedOnly,
            @Min(1) @Max(100_000) int maximumRecords,
            @Min(0) long configurationVersion,
            @Min(0) long runtimeVersion) { }

    public record ReplayPreview(
            UUID previewId,
            ConnectorKind kind,
            String provider,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean failedOnly,
            int maximumRecords,
            long estimatedRecords,
            boolean eligible,
            List<String> limitations,
            long configurationVersion,
            long runtimeVersion,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }

    public record ReplayStartRequest(
            @NotNull UUID previewId,
            @Min(0) long configurationVersion,
            @Min(0) long runtimeVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ReplayJob(
            UUID jobId,
            UUID previewId,
            ConnectorKind kind,
            String provider,
            ReplayState state,
            String reason,
            String providerOperationReference,
            String resultSummary,
            long configurationVersion,
            long runtimeVersion,
            long version,
            OffsetDateTime requestedAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime updatedAt) { }

    public record CommandReceipt(
            UUID commandId,
            ReplayState state,
            OffsetDateTime acceptedAt,
            String statusHref,
            boolean idempotentReplay,
            String correlationId) { }

    public record ReplayStartResponse(ReplayJob job, CommandReceipt receipt) { }

    /**
     * A provider-owned observation. It is accepted only through the provider event adapter,
     * never through an administrator HTTP endpoint.
     */
    public record ProviderObservation(
            UUID observationId,
            long tenantId,
            ConnectorKind kind,
            @NotBlank String source,
            @NotBlank String provider,
            @Min(0) long configurationVersion,
            @NotBlank String adapterId,
            @NotBlank String adapterVersion,
            @NotNull ProviderReportedState reportedState,
            @NotNull List<Capability> capabilities,
            @NotNull OffsetDateTime sourceObservedAt,
            @NotNull OffsetDateTime receivedAt,
            OffsetDateTime lastSuccessAt,
            @Min(0) Long lagSeconds,
            @Size(max = 320) String checkpointReference,
            @Min(0) Long retryQueueDepth,
            @Min(0) Long deadLetterQueueDepth,
            @Size(max = 120) String errorCode,
            @Min(1) long sequence,
            @NotBlank @Size(max = 128) String payloadFingerprint) { }
}
