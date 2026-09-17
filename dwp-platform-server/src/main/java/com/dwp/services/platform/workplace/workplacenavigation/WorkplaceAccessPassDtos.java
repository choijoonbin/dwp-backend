package com.dwp.services.platform.workplace.workplacenavigation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.ProviderTruth;

public final class WorkplaceAccessPassDtos {
    private WorkplaceAccessPassDtos() { }

    public enum AccessPassState { ACTIVE, REVOKED, EXPIRED }
    public enum AccessPassCommandType { ISSUE, ROTATE, REVOKE }
    public enum AccessPassCommandState { SUCCEEDED, FAILED, RESULT_UNKNOWN }

    public record AccessPassPreviewRequest(
            @NotNull AccessPassCommandType commandType,
            UUID passId,
            @NotNull UUID siteId,
            @NotNull UUID floorId,
            @NotNull UUID resourceId,
            @NotNull UUID destinationPoiId,
            @Min(0) long expectedPassVersion) { }

    public record AccessPassPreview(
            UUID previewId,
            AccessPassCommandType commandType,
            UUID passId,
            UUID siteId,
            UUID floorId,
            UUID resourceId,
            UUID destinationPoiId,
            long expectedPassVersion,
            boolean nfcEnabled,
            boolean qrEnabled,
            boolean eligible,
            List<String> impact,
            List<String> limitations,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }

    public record ConfirmAccessPassCommandRequest(
            @NotNull UUID previewId,
            @Min(0) long expectedPassVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record AccessPassView(
            UUID passId,
            UUID sourceBookingId,
            UUID siteId,
            UUID floorId,
            UUID resourceId,
            UUID destinationPoiId,
            AccessPassState state,
            String credentialLastFour,
            boolean pairingAvailable,
            boolean nfcEnabled,
            boolean qrEnabled,
            String nfcProviderCode,
            Long nfcProviderConfigurationVersion,
            String nfcProviderEvidenceReference,
            String qrProviderCode,
            Long qrProviderConfigurationVersion,
            String qrProviderEvidenceReference,
            long version,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt,
            OffsetDateTime revokedAt,
            OffsetDateTime updatedAt) { }

    public record AccessPassContext(
            AccessPassView pass,
            List<ProviderTruth> providers,
            boolean bookingEligible,
            OffsetDateTime bookingEndsAt,
            OffsetDateTime evaluatedAt) { }

    public record AccessPassCommandReceipt(
            UUID commandId,
            UUID passId,
            AccessPassCommandType commandType,
            AccessPassCommandState state,
            String reason,
            String resultCode,
            long version,
            boolean recoveryByGetOnly,
            String statusHref,
            String correlationId,
            OffsetDateTime acceptedAt,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt) { }

    /** Raw values are populated only for the first successful ISSUE/ROTATE response. */
    public record AccessPassCommandResult(
            AccessPassCommandReceipt receipt,
            AccessPassView pass,
            String oneTimeCredential,
            String pairingCode,
            boolean replayed) { }

    public record AccessPassAuditEvent(
            UUID auditEventId,
            String action,
            UUID passId,
            String correlationId,
            OffsetDateTime occurredAt) { }

    public record AccessPassPairingRequest(
            @NotNull UUID passId,
            @NotBlank
            @Pattern(regexp = "^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{12}$")
            String pairingCode) { }

    public record AccessPassPairingReceipt(
            UUID pairingReceiptId,
            UUID passId,
            UUID deviceId,
            UUID siteId,
            UUID floorId,
            UUID resourceId,
            long passVersion,
            String correlationId,
            OffsetDateTime expiresAt,
            OffsetDateTime pairedAt,
            boolean replayed) { }

    record BookingWindow(UUID bookingId, OffsetDateTime endsAt) { }

    record AccessPassRow(
            UUID passId,
            long tenantId,
            long ownerUserId,
            UUID sourceBookingId,
            UUID siteId,
            UUID floorId,
            UUID resourceId,
            UUID destinationPoiId,
            AccessPassState state,
            String credentialSha256,
            String credentialLastFour,
            String pairingCodeHash,
            String pairingCodeSalt,
            int pairingAttemptCount,
            OffsetDateTime pairingLockedAt,
            OffsetDateTime pairingConsumedAt,
            UUID pairingDeviceId,
            boolean nfcEnabled,
            boolean qrEnabled,
            String nfcProviderCode,
            Long nfcProviderConfigurationVersion,
            String nfcProviderEvidenceReference,
            String qrProviderCode,
            Long qrProviderConfigurationVersion,
            String qrProviderEvidenceReference,
            long version,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt,
            OffsetDateTime revokedAt,
            OffsetDateTime updatedAt) { }

    record AccessPassPreviewRow(
            UUID previewId,
            long tenantId,
            long actorUserId,
            String idempotencyKey,
            String requestFingerprint,
            AccessPassCommandType commandType,
            UUID passId,
            UUID siteId,
            UUID floorId,
            UUID resourceId,
            UUID destinationPoiId,
            UUID sourceBookingId,
            long expectedPassVersion,
            boolean nfcEnabled,
            boolean qrEnabled,
            String nfcProviderCode,
            Long nfcProviderConfigurationVersion,
            String nfcProviderEvidenceReference,
            String qrProviderCode,
            Long qrProviderConfigurationVersion,
            String qrProviderEvidenceReference,
            boolean eligible,
            List<String> impact,
            List<String> limitations,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }

    record AccessPassCommandRow(
            UUID commandId,
            long tenantId,
            long actorUserId,
            UUID passId,
            UUID previewId,
            AccessPassCommandType commandType,
            String idempotencyKey,
            String requestFingerprint,
            AccessPassCommandState state,
            String reason,
            String resultCode,
            String correlationId,
            long version,
            OffsetDateTime acceptedAt,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt) { }

    record AccessPassPairingReceiptRow(
            UUID pairingReceiptId,
            long tenantId,
            UUID passId,
            UUID deviceId,
            String idempotencyKey,
            String correlationId,
            String requestFingerprint,
            UUID siteId,
            UUID floorId,
            UUID resourceId,
            long passVersion,
            OffsetDateTime expiresAt,
            OffsetDateTime pairedAt) { }
}
