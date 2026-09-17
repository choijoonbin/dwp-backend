package com.dwp.services.platform.workplace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkplaceResourceCommandDtos {
    private WorkplaceResourceCommandDtos() { }

    public enum ResourceCommandType {
        PARKING_EXTEND,
        PARKING_EXIT_STATUS,
        LOCKER_UNLOCK,
        NFC_KEY_RESEND,
        ROOM_PRE_ENTRY
    }

    public enum ResourceCommandProviderCapability { NFC, SPEED_GATE }

    public enum ResourceCommandProviderState {
        NOT_CONFIGURED,
        CONFIGURED_UNVERIFIED,
        READY,
        DEGRADED,
        STALE
    }

    public enum ResourceCommandAvailability {
        AVAILABLE,
        BOOKING_INACTIVE,
        PROVIDER_NOT_READY
    }

    public enum ResourceCommandState { SUCCEEDED, FAILED, RESULT_UNKNOWN }

    @Schema(name = "WorkplaceResourceCommandAction")
    public record CommandAction(
            ResourceCommandType commandType,
            ResourceCommandProviderCapability providerCapability,
            ResourceCommandProviderState providerState,
            ResourceCommandAvailability availability,
            String limitationCode,
            boolean elevatedConfirmationRequired) { }

    @Schema(name = "WorkplaceResourceCommandContext")
    public record CommandContext(
            UUID bookingId,
            UUID resourceId,
            WorkplaceTypes.ResourceType resourceType,
            long bookingVersion,
            List<CommandAction> actions,
            OffsetDateTime evaluatedAt) { }

    @Schema(name = "WorkplaceResourceCommandPreviewRequest")
    public record PreviewRequest(
            @NotNull ResourceCommandType commandType,
            @Min(0) long expectedBookingVersion,
            @NotNull @Size(max = 8) Map<@NotBlank @Size(max = 80) String,
                    @Size(max = 240) String> parameters) { }

    @Schema(name = "WorkplaceResourceCommandPreview")
    public record CommandPreview(
            UUID previewId,
            UUID bookingId,
            UUID resourceId,
            ResourceCommandType commandType,
            long expectedBookingVersion,
            Map<String, String> parameters,
            ResourceCommandProviderCapability providerCapability,
            ResourceCommandProviderState providerState,
            String providerCode,
            Long providerConfigurationVersion,
            boolean eligible,
            List<String> impact,
            List<String> limitations,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }

    @Schema(name = "WorkplaceResourceCommandExecuteRequest")
    public record ExecuteRequest(
            @NotNull UUID previewId,
            @Min(0) long expectedBookingVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    @Schema(name = "WorkplaceResourceCommandReconcileRequest")
    public record ReconcileRequest(
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    @Schema(name = "WorkplaceResourceCommandReceipt")
    public record CommandReceipt(
            UUID commandId,
            UUID previewId,
            UUID bookingId,
            UUID resourceId,
            ResourceCommandType commandType,
            ResourceCommandState state,
            String resultCode,
            String providerOperationReference,
            long version,
            String statusHref,
            String correlationId,
            OffsetDateTime acceptedAt,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt,
            boolean requeryRequired,
            boolean idempotentReplay) { }
}
