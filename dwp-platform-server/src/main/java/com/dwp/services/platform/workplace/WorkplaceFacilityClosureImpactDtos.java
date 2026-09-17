package com.dwp.services.platform.workplace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WorkplaceFacilityClosureImpactDtos {
    private WorkplaceFacilityClosureImpactDtos() { }

    public enum ReservationOwner { WORKPLACE, CALENDAR }
    public enum ImpactAction { KEEP, CANCEL, REPLACE }
    public enum CommandState { ACCEPTED, EXECUTING, SUCCEEDED, PARTIAL, FAILED, RESULT_UNKNOWN }
    public enum ItemResultState { PENDING, SUCCEEDED, FAILED, RESULT_UNKNOWN }
    public enum NotificationState {
        NOT_REQUIRED, NOT_CONFIGURED, PENDING, RETRY_SCHEDULED, SENDING, PUBLISHED,
        RESULT_UNKNOWN, DEAD
    }

    public record CreateImpactPreview(
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            @NotNull @Min(0) Long resourceVersion) { }

    public record ReplacementCandidate(
            UUID workplaceResourceId,
            UUID ownerResourceId,
            String resourceName,
            UUID floorId,
            long resourceVersion,
            int rank) { }

    @Schema(name = "WorkplaceFacilityClosureImpactItem")
    public record ImpactItem(
            UUID previewItemId,
            ReservationOwner reservationOwner,
            UUID bookingId,
            UUID eventId,
            UUID sourceWorkplaceResourceId,
            UUID sourceOwnerResourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String bookingStatus,
            long bookingVersion,
            List<Long> recipientUserIds,
            String replacementBlockReason,
            List<ReplacementCandidate> replacementCandidates) { }

    public record ImpactPreview(
            UUID previewId,
            UUID resourceId,
            UUID siteId,
            ReservationOwner reservationOwner,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            long resourceVersion,
            long previewVersion,
            String confirmationToken,
            int affectedBookingCount,
            int affectedRecipientCount,
            OffsetDateTime expiresAt,
            OffsetDateTime generatedAt,
            List<ImpactItem> items) { }

    public record ImpactSelection(
            @NotNull UUID previewItemId,
            @NotNull ImpactAction action,
            @NotNull @Min(0) Long expectedBookingVersion,
            UUID replacementResourceId,
            @Min(0) Long expectedReplacementResourceVersion) { }

    public record ExecuteImpactCommand(
            @NotNull @Min(1) Long expectedPreviewVersion,
            @NotBlank @Size(max = 160) String confirmationToken,
            @NotBlank @Size(max = 500) String reason,
            boolean confirmed,
            @NotNull @Size(max = 1000) List<@Valid ImpactSelection> selections) { }

    public record ReconcileNotifications(
            @NotNull @Min(1) Long expectedCommandVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean confirmed) { }

    public record CommandItem(
            UUID commandItemId,
            UUID previewItemId,
            ReservationOwner reservationOwner,
            UUID bookingId,
            ImpactAction selectedAction,
            long expectedBookingVersion,
            UUID replacementWorkplaceResourceId,
            UUID replacementOwnerResourceId,
            Long replacementResourceVersion,
            ItemResultState resultState,
            String resultCode,
            Long resultingBookingVersion) { }

    public record NotificationDelivery(
            int recipientCount,
            int eventCount,
            NotificationState state,
            int pendingCount,
            int retryCount,
            int sendingCount,
            int publishedCount,
            int resultUnknownCount,
            int deadCount,
            boolean eventTransportConfigured,
            boolean reconciliationRequired,
            OffsetDateTime observedAt) { }

    public record ClosureCommand(
            UUID commandId,
            UUID previewId,
            UUID closureId,
            UUID resourceId,
            UUID siteId,
            CommandState state,
            long expectedPreviewVersion,
            String reason,
            int keptCount,
            int cancelledCount,
            int replacedCount,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt,
            NotificationDelivery notifications,
            List<CommandItem> items) { }

    public record AuditEntry(
            UUID commandEventId,
            String eventType,
            long actorUserId,
            String evidence,
            String correlationId,
            OffsetDateTime occurredAt) { }

    @Schema(name = "WorkplaceFacilityClosureCommandReceipt")
    public record CommandReceipt(
            ClosureCommand command,
            String owner,
            boolean bookingsMutated,
            boolean notificationScheduled,
            boolean notificationDispatchPublished,
            boolean externalDeliveryProven,
            List<AuditEntry> auditTrail) { }
}
