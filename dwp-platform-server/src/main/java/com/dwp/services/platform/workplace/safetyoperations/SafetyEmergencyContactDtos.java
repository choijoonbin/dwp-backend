package com.dwp.services.platform.workplace.safetyoperations;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.CommandReceipt;

public final class SafetyEmergencyContactDtos {
    private SafetyEmergencyContactDtos() { }

    public enum EmergencyContactKind { HOTLINE, RADIO, PUBLIC_EMERGENCY }
    public enum EmergencyContactActionMode { TEL_URI, GOVERNED_HANDOFF }
    public enum EmergencyContactProviderState {
        NOT_CONFIGURED, CONFIGURED_UNVERIFIED, READY, DEGRADED, STALE
    }

    public record EmergencyContactView(
            UUID contactId,
            EmergencyContactKind kind,
            String displayNameKo,
            String displayNameEn,
            EmergencyContactActionMode actionMode,
            String telUri,
            boolean directTelAllowed,
            EmergencyContactProviderState providerState,
            String providerCode,
            Long providerConfigurationVersion,
            boolean active,
            int sortOrder,
            long version,
            OffsetDateTime evaluatedAt) { }

    public record EmergencyContactConfigurationRequest(
            @NotNull EmergencyContactKind kind,
            @NotBlank @Size(max = 160) String displayNameKo,
            @NotBlank @Size(max = 160) String displayNameEn,
            @NotNull EmergencyContactActionMode actionMode,
            @Pattern(regexp = "^tel:\\+[1-9][0-9]{6,14}$") String telUri,
            boolean directTelAllowed,
            boolean active,
            @Min(0) @Max(10000) int sortOrder,
            @Min(0) long expectedVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record EmergencyContactConfigurationResult(
            EmergencyContactView contact,
            CommandReceipt receipt) { }

    public record EmergencyHandoffPreviewRequest(
            @NotNull UUID contactId,
            @Min(1) long expectedIncidentVersion,
            @Min(1) long expectedContactVersion,
            @NotBlank @Size(max = 500) String reason) { }

    public record EmergencyHandoffPreview(
            UUID previewId,
            UUID incidentId,
            UUID contactId,
            long expectedIncidentVersion,
            long expectedContactVersion,
            EmergencyContactProviderState providerState,
            String providerCode,
            Long providerConfigurationVersion,
            boolean eligible,
            List<String> impact,
            List<String> limitations,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }

    public record EmergencyHandoffPreviewResult(
            EmergencyHandoffPreview preview,
            CommandReceipt receipt) { }

    public record ConfirmEmergencyHandoffRequest(
            @NotNull UUID previewId,
            @Min(1) long expectedIncidentVersion,
            @Min(1) long expectedContactVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ReconcileEmergencyHandoffRequest(
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record EmergencyHandoffReceipt(
            UUID handoffId,
            UUID commandId,
            UUID previewId,
            UUID incidentId,
            UUID contactId,
            SafetyOperationsDtos.CommandState state,
            String resultCode,
            String providerOperationReference,
            String providerEvidenceReference,
            long version,
            String statusHref,
            String correlationId,
            OffsetDateTime acceptedAt,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt,
            boolean idempotentReplay) { }

    record EmergencyContactRow(
            UUID contactId,
            long tenantId,
            EmergencyContactKind kind,
            String displayNameKo,
            String displayNameEn,
            EmergencyContactActionMode actionMode,
            String telUri,
            boolean directTelAllowed,
            String connectorKind,
            boolean active,
            int sortOrder,
            long version,
            long updatedBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) { }

    record EmergencyHandoffPreviewRow(
            UUID previewId,
            long tenantId,
            UUID commandId,
            long actorUserId,
            UUID incidentId,
            UUID contactId,
            long expectedIncidentVersion,
            long expectedContactVersion,
            EmergencyContactProviderState providerState,
            String providerCode,
            Long providerConfigurationVersion,
            String providerEvidenceReference,
            boolean eligible,
            List<String> limitations,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }

    record EmergencyHandoffRow(
            UUID handoffId,
            long tenantId,
            UUID commandId,
            UUID previewId,
            UUID incidentId,
            UUID contactId,
            SafetyOperationsDtos.CommandState state,
            String providerCode,
            long providerConfigurationVersion,
            String providerOperationReference,
            String providerEvidenceReference,
            String resultCode,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt) { }
}
