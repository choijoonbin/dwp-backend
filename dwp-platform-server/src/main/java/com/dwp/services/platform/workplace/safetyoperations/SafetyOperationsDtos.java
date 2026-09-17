package com.dwp.services.platform.workplace.safetyoperations;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SafetyOperationsDtos {
    private SafetyOperationsDtos() { }

    public enum Severity { ADVISORY, URGENT, CRITICAL }
    public enum IncidentState { ACTIVE, CLOSURE_PENDING, CLOSED, CANCELLED }
    public enum AudienceSourceKind {
        RESERVATION, ACTUAL_PRESENCE, VISITOR, SCHEDULED_VISITOR
    }
    public enum FreshnessState { FRESH, STALE, UNKNOWN }
    public enum AvailabilityState { AVAILABLE, PARTIAL, UNAVAILABLE }
    public enum DeliveryChannel { APP_PUSH, SMS, EMAIL, EBS, BLE_MESH }
    public enum DispatchState {
        ACCEPTED, DISPATCHING, PARTIAL, SUCCEEDED, FAILED, RESULT_UNKNOWN
    }
    public enum AttemptState {
        QUEUED, DISPATCHING, DELIVERED, DELIVERY_FAILED, OFFLINE_QUEUED, RESULT_UNKNOWN
    }
    public enum SafetyResponseState { SAFE, NEEDS_HELP }
    public enum MessageDirection { USER_TO_COMMAND, COMMAND_TO_USER, COMMAND_BROADCAST }
    public enum CommandState { ACCEPTED, RUNNING, SUCCEEDED, FAILED, RESULT_UNKNOWN }
    public enum ConnectorKind { EMERGENCY_119, EBS, BLE_MESH, WORM, GOVERNMENT_LOG }
    public enum ProviderReportedState { READY, DEGRADED, UNAVAILABLE }
    public enum ConnectorTruthState {
        NOT_CONFIGURED, CONFIGURED_UNVERIFIED, READY, DEGRADED, STALE
    }
    public enum ExportFormat { PDF, CSV }

    public record SourceSummary(
            AudienceSourceKind source,
            int candidateCount,
            int includedCount,
            int excludedCount,
            int unknownCount,
            Double coveragePercent,
            FreshnessState freshness,
            AvailabilityState availability,
            OffsetDateTime sourceAt,
            OffsetDateTime receivedAt) { }

    public record AudienceMember(
            UUID audienceMemberId,
            String subjectKeySha256,
            Long subjectUserId,
            String maskedLabel,
            List<AudienceSourceKind> sources,
            boolean included,
            String exclusionCode,
            boolean unknownIdentity) { }

    public record AudienceSnapshot(
            UUID audienceSnapshotId,
            int totalCandidates,
            int deduplicatedCount,
            int excludedCount,
            int unknownCount,
            int finalTargetCount,
            List<SourceSummary> sources,
            List<AudienceMember> members,
            OffsetDateTime asOf) { }

    public record ActivationPreviewRequest(
            @NotBlank @Size(max = 40) String incidentType,
            @NotNull Severity severity,
            @NotNull UUID siteId,
            @NotNull @Size(max = 100) List<@NotNull UUID> floorIds,
            @NotNull @Size(max = 500) List<@NotNull UUID> zoneIds,
            @NotBlank @Size(max = 1000) String message,
            @NotBlank @Size(max = 1000) String safetyAction,
            @Size(max = 500) String assemblyPoint,
            @NotEmpty @Size(max = 5) List<@NotNull DeliveryChannel> channels,
            @NotNull @Size(max = 5000)
            List<@Pattern(regexp = "^[0-9a-f]{64}$") String> excludedSubjectKeys,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ActivationPreview(
            UUID activationPreviewId,
            String incidentType,
            Severity severity,
            UUID siteId,
            List<UUID> floorIds,
            List<UUID> zoneIds,
            String message,
            String safetyAction,
            String assemblyPoint,
            List<DeliveryChannel> channels,
            AudienceSnapshot audience,
            List<ConnectorTruth> connectorTruth,
            boolean eligible,
            List<String> limitations,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }

    public record ActivateIncidentRequest(
            @NotNull UUID activationPreviewId,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record DispatchSummary(
            UUID dispatchBatchId,
            DispatchState state,
            int attemptCount,
            int deliveredCount,
            int failedCount,
            int unknownCount,
            List<DeliveryChannel> channels,
            OffsetDateTime updatedAt) { }

    public record ResponseSummary(int safe, int needsHelp, int noResponse) { }
    public record AssemblySummary(int confirmed, int pending) { }

    public record Incident(
            UUID incidentId,
            String incidentNumber,
            String incidentType,
            Severity severity,
            IncidentState state,
            UUID siteId,
            List<UUID> floorIds,
            List<UUID> zoneIds,
            String message,
            String safetyAction,
            String assemblyPoint,
            List<DeliveryChannel> channels,
            AudienceSnapshot audience,
            ResponseSummary responses,
            AssemblySummary assembly,
            List<DispatchSummary> dispatches,
            List<ConnectorTruth> connectorTruth,
            long version,
            OffsetDateTime activatedAt,
            OffsetDateTime closedAt,
            OffsetDateTime updatedAt) { }

    public record SafetySheet(
            UUID incidentId,
            String incidentNumber,
            Severity severity,
            String message,
            String safetyAction,
            String assemblyPoint,
            List<String> scopeLabels,
            SafetyResponseState currentResponse,
            String accessibleAlternativeContact,
            long version,
            OffsetDateTime asOf) { }

    public record SafetyResponseRequest(
            @Min(1) long expectedIncidentVersion,
            @NotNull SafetyResponseState response,
            @Size(max = 500) String assistanceNote,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record MessageRequest(
            @Min(1) long expectedIncidentVersion,
            Long targetUserId,
            @NotBlank @Size(max = 1000) String body,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record AssemblyConfirmationRequest(
            @Min(1) long expectedIncidentVersion,
            @Pattern(regexp = "^[0-9a-f]{64}$") @NotBlank String subjectKeySha256,
            Long subjectUserId,
            boolean confirmed,
            @NotNull OffsetDateTime observedAt,
            @NotBlank @Size(max = 320) String evidenceReference,
            @Min(0) long expectedAssemblyVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record AssemblyConfirmation(
            UUID assemblyConfirmationId,
            UUID incidentId,
            String subjectKeySha256,
            Long subjectUserId,
            boolean confirmed,
            OffsetDateTime observedAt,
            long confirmedBy,
            String evidenceReference,
            long version,
            OffsetDateTime updatedAt) { }

    public record IncidentMessage(
            UUID messageId,
            UUID incidentId,
            Long targetUserId,
            MessageDirection direction,
            String maskedBody,
            OffsetDateTime createdAt) { }

    public record ScopeRevisionPreviewRequest(
            @Min(1) long expectedIncidentVersion,
            @NotNull @Size(max = 100) List<@NotNull UUID> floorIds,
            @NotNull @Size(max = 500) List<@NotNull UUID> zoneIds,
            @NotBlank @Size(max = 1000) String message,
            @NotNull @Size(max = 5000)
            List<@Pattern(regexp = "^[0-9a-f]{64}$") String> excludedSubjectKeys,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ScopeRevisionPreview(
            UUID scopeRevisionId,
            UUID incidentId,
            long incidentVersion,
            List<UUID> previousFloorIds,
            List<UUID> previousZoneIds,
            List<UUID> proposedFloorIds,
            List<UUID> proposedZoneIds,
            String proposedMessage,
            AudienceSnapshot audience,
            int newlyIncluded,
            int noLongerIncluded,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }

    public record ApplyScopeRevisionRequest(
            @NotNull UUID scopeRevisionId,
            @Min(1) long expectedIncidentVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ResendRequest(
            @Min(1) long expectedIncidentVersion,
            @NotEmpty @Size(max = 5) List<@NotNull DeliveryChannel> channels,
            @NotEmpty @Size(max = 5) List<@NotNull AttemptState> retryStates,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ClosurePreview(
            UUID closurePreviewId,
            UUID incidentId,
            long incidentVersion,
            int needsHelpCount,
            int noResponseCount,
            int deliveredCount,
            int failedOrUnknownCount,
            boolean eligible,
            List<String> warnings,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }

    public record ClosurePreviewRequest(
            @Min(1) long expectedIncidentVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ClosureRequestInput(
            @NotNull UUID closurePreviewId,
            @Min(1) long expectedIncidentVersion,
            @Min(1) long designatedApproverId,
            @NotBlank @Size(max = 1000) String closureReason,
            @NotBlank @Size(max = 2000) String followUpActions,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ClosureRequest(
            UUID closureRequestId,
            UUID incidentId,
            long requestedBy,
            long designatedApproverId,
            String closureReason,
            String followUpActions,
            String state,
            long version,
            OffsetDateTime requestedAt) { }

    public record ClosureApprovalInput(
            @Min(1) long expectedIncidentVersion,
            @Min(1) long expectedClosureVersion,
            boolean approved,
            @NotBlank @Size(max = 1000) String approvalReason,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record PostIncidentReport(
            UUID reportId,
            UUID incidentId,
            Map<String, Object> summary,
            long version,
            OffsetDateTime generatedAt) { }

    public record GuardedExportRequest(
            @Min(1) long expectedIncidentVersion,
            @NotNull ExportFormat format,
            @NotBlank @Size(max = 500) String purpose,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record GuardedExport(
            UUID exportId,
            UUID incidentId,
            ExportFormat format,
            String purpose,
            String reason,
            long requestedBy,
            String correlationId,
            String stepUpEvidence,
            String contentType,
            String sha256,
            long sizeBytes,
            String downloadHref,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) { }

    public record ExportContent(
            GuardedExport metadata,
            byte[] payload) { }

    public record CommandReceipt(
            UUID commandId,
            CommandState state,
            String statusHref,
            boolean idempotentReplay,
            String correlationId,
            OffsetDateTime acceptedAt) { }

    public record IncidentCommandResult(Incident incident, CommandReceipt receipt) { }
    public record ActivationPreviewResult(
            ActivationPreview preview, CommandReceipt receipt) { }
    public record ScopePreviewCommandResult(
            ScopeRevisionPreview preview, CommandReceipt receipt) { }
    public record ClosurePreviewCommandResult(
            ClosurePreview preview, CommandReceipt receipt) { }
    public record ResponseCommandResult(SafetySheet sheet, CommandReceipt receipt) { }
    public record MessageCommandResult(IncidentMessage message, CommandReceipt receipt) { }
    public record AssemblyCommandResult(
            AssemblyConfirmation confirmation, CommandReceipt receipt) { }
    public record ClosureCommandResult(ClosureRequest closure, CommandReceipt receipt) { }
    public record ExportCommandResult(GuardedExport export, CommandReceipt receipt) { }
    public record ConnectorCommandResult(ConnectorTruth connector, CommandReceipt receipt) { }

    public record ConnectorTruth(
            ConnectorKind kind,
            String providerCode,
            ConnectorTruthState state,
            long configurationVersion,
            Long observedConfigurationVersion,
            String evidenceReference,
            OffsetDateTime sourceAt,
            OffsetDateTime receivedAt,
            OffsetDateTime lastSuccessAt,
            String errorCode,
            long version,
            OffsetDateTime evaluatedAt) { }

    public record ConnectorConfigurationRequest(
            @NotNull ConnectorKind kind,
            @NotBlank @Size(max = 80) String providerCode,
            @Min(1) long configurationVersion,
            @Min(0) long expectedVersion,
            boolean configured,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ConnectorObservation(
            long tenantId,
            @NotNull ConnectorKind kind,
            @NotBlank @Size(max = 80) String providerCode,
            @Min(1) long observedConfigurationVersion,
            @NotNull ProviderReportedState reportedState,
            @NotBlank @Size(max = 320) String evidenceReference,
            @NotNull OffsetDateTime sourceAt,
            @NotNull OffsetDateTime receivedAt,
            OffsetDateTime lastSuccessAt,
            @Size(max = 120) String errorCode) { }

    public record PresenceObservation(
            UUID observationId,
            long tenantId,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String subjectKeySha256,
            Long subjectUserId,
            @NotBlank @Size(max = 160) String maskedLabel,
            @NotNull UUID siteId,
            UUID floorId,
            UUID zoneId,
            @NotBlank @Pattern(regexp = "PRESENT|DEPARTED|UNKNOWN") String presenceState,
            @NotBlank @Size(max = 320) String sourceReference,
            @NotBlank @Size(max = 320) String evidenceReference,
            @NotNull OffsetDateTime sourceAt,
            @NotNull OffsetDateTime receivedAt,
            @Min(1) long sequence) { }

    public record DispatchOutcome(
            long tenantId,
            @NotNull UUID dispatchAttemptId,
            @NotNull AttemptState state,
            @Size(max = 320) String providerOperationReference,
            @Size(max = 120) String resultCode,
            @Size(max = 320) String evidenceReference,
            OffsetDateTime sourceAt,
            @NotNull OffsetDateTime receivedAt) { }
}
