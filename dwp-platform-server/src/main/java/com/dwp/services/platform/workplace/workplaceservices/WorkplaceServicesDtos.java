package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.CapacityMode;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.CapacityReservation;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.InspectionMode;

public final class WorkplaceServicesDtos {
    private WorkplaceServicesDtos() { }

    public enum ServiceCategory { CATERING, AV, ROOM_LAYOUT, IT_SUPPORT, CLEANING }
    public enum ReservationAuthority { WORKPLACE, CALENDAR }
    public enum ProviderState {
        NOT_CONFIGURED, CONFIGURED_UNVERIFIED, READY, DEGRADED, STALE
    }
    public enum OrderState {
        SUBMITTED, ACCEPTED, IN_PREPARATION, PARTIALLY_FULFILLED, FULFILLED,
        BLOCKED, DELAYED, CANCELLED, RESULT_UNKNOWN
    }
    public enum WorkState {
        SUBMITTED, ACCEPTED, IN_PREPARATION, PARTIALLY_FULFILLED, FULFILLED,
        BLOCKED, DELAYED, CANCELLED, NOT_CONFIGURED, RESULT_UNKNOWN
    }
    public enum ReservationImpact { NONE, RECONFIRMATION_REQUIRED, CANCELLATION_REVIEW }
    public enum CommandState { ACCEPTED, SUCCEEDED, FAILED, RESULT_UNKNOWN }
    public enum RefundScope { FULL, PARTIAL, NONE }
    public enum LineAdjustmentState {
        CANCELLATION_PENDING, RECONCILIATION_PENDING,
        CANCELLATION_SUCCEEDED, REFUNDED, REFUND_NOT_CONFIGURED,
        FAILED, RESULT_UNKNOWN
    }
    public enum AttachmentScanState {
        NOT_CONFIGURED, QUARANTINED, CLEAN, INFECTED, ERROR
    }
    public enum AttachmentScanVerdict { CLEAN, INFECTED, ERROR }

    @Schema(name = "WorkplaceServiceCatalogItem")
    public record ServiceCatalogItem(
            UUID catalogItemId,
            String serviceCode,
            ServiceCategory category,
            String nameKo,
            String nameEn,
            String descriptionKo,
            String descriptionEn,
            ProviderState providerState,
            String providerCode,
            JsonNode optionSchema,
            List<String> supportedResourceTypes,
            BigDecimal unitPrice,
            String currency,
            int minimumQuantity,
            int maximumQuantity,
            int orderCutoffMinutes,
            int cancellationCutoffMinutes,
            int slaResponseMinutes,
            int slaFulfillmentLeadMinutes,
            String cancellationPolicyKo,
            String cancellationPolicyEn,
            CapacityMode capacityMode,
            int capacityFreshnessSeconds,
            InspectionMode inspectionMode,
            JsonNode inspectionChecklistSchema,
            boolean requiresAttendeeCount,
            boolean requiresCostCenter,
            long version) { }

    @Schema(name = "WorkplaceServiceCatalog")
    public record ServiceCatalog(
            ReservationAuthority reservationAuthority,
            UUID reservationId,
            long reservationVersion,
            OffsetDateTime reservationStartsAt,
            OffsetDateTime reservationEndsAt,
            String siteReference,
            String resourceReference,
            String resourceType,
            List<ServiceCatalogItem> items,
            OffsetDateTime generatedAt) { }

    @Schema(name = "WorkplaceServiceCatalogAdminItem")
    public record ServiceCatalogAdminItem(
            ServiceCatalogItem item,
            List<UUID> siteScope,
            String lifecycleState,
            OffsetDateTime updatedAt) { }

    public record ServiceCatalogAdminItems(
            List<ServiceCatalogAdminItem> items,
            OffsetDateTime generatedAt) { }

    @Schema(name = "WorkplaceServiceCatalogCreateRequest")
    public record CatalogCreateRequest(
            @NotBlank @Size(max = 80) String serviceCode,
            @NotNull ServiceCategory category,
            @NotBlank @Size(max = 160) String nameKo,
            @NotBlank @Size(max = 160) String nameEn,
            @Size(max = 1000) String descriptionKo,
            @Size(max = 1000) String descriptionEn,
            @NotBlank @Size(max = 80) String providerCode,
            @NotNull @Size(max = 100) List<@NotNull UUID> siteScope,
            @NotNull JsonNode optionSchema,
            @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 40) String> supportedResourceTypes,
            @NotNull @DecimalMin("0.00") @DecimalMax("499999999999.99")
                @Digits(integer = 12, fraction = 2) BigDecimal unitPrice,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Min(1) @Max(1000) int minimumQuantity,
            @Min(1) @Max(1000) int maximumQuantity,
            @Min(0) @Max(525600) int orderCutoffMinutes,
            @Min(0) @Max(525600) int cancellationCutoffMinutes,
            @Min(1) @Max(525600) int slaResponseMinutes,
            @Min(0) @Max(525600) int slaFulfillmentLeadMinutes,
            @NotBlank @Size(max = 1000) String cancellationPolicyKo,
            @NotBlank @Size(max = 1000) String cancellationPolicyEn,
            @NotNull CapacityMode capacityMode,
            @Min(30) @Max(86400) int capacityFreshnessSeconds,
            @NotNull InspectionMode inspectionMode,
            @NotNull JsonNode inspectionChecklistSchema,
            boolean requiresAttendeeCount,
            boolean requiresCostCenter,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    @Schema(name = "WorkplaceServiceCatalogUpdateRequest")
    public record CatalogUpdateRequest(
            @Min(1) long expectedVersion,
            @NotNull ServiceCategory category,
            @NotBlank @Size(max = 160) String nameKo,
            @NotBlank @Size(max = 160) String nameEn,
            @Size(max = 1000) String descriptionKo,
            @Size(max = 1000) String descriptionEn,
            @NotBlank @Size(max = 80) String providerCode,
            @NotNull @Size(max = 100) List<@NotNull UUID> siteScope,
            @NotNull JsonNode optionSchema,
            @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 40) String> supportedResourceTypes,
            @NotNull @DecimalMin("0.00") @DecimalMax("499999999999.99")
                @Digits(integer = 12, fraction = 2) BigDecimal unitPrice,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Min(1) @Max(1000) int minimumQuantity,
            @Min(1) @Max(1000) int maximumQuantity,
            @Min(0) @Max(525600) int orderCutoffMinutes,
            @Min(0) @Max(525600) int cancellationCutoffMinutes,
            @Min(1) @Max(525600) int slaResponseMinutes,
            @Min(0) @Max(525600) int slaFulfillmentLeadMinutes,
            @NotBlank @Size(max = 1000) String cancellationPolicyKo,
            @NotBlank @Size(max = 1000) String cancellationPolicyEn,
            @NotNull CapacityMode capacityMode,
            @Min(30) @Max(86400) int capacityFreshnessSeconds,
            @NotNull InspectionMode inspectionMode,
            @NotNull JsonNode inspectionChecklistSchema,
            boolean requiresAttendeeCount,
            boolean requiresCostCenter,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    @Schema(name = "WorkplaceServiceCatalogStateRequest")
    public record CatalogStateRequest(
            @Min(1) long expectedVersion,
            boolean active,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record CatalogCommandReceipt(
            UUID commandId,
            UUID catalogItemId,
            CommandState state,
            String statusHref,
            boolean replayed,
            String correlationId,
            OffsetDateTime acceptedAt) { }

    public record CatalogCommandResult(
            ServiceCatalogAdminItem item,
            CatalogCommandReceipt receipt) { }

    public record ServiceLineRequest(
            @NotNull UUID catalogItemId,
            @Min(1) @Max(1000) int quantity,
            @NotNull JsonNode options) { }

    @Schema(name = "WorkplaceServiceOrderPreviewRequest")
    public record PreviewRequest(
            @NotNull ReservationAuthority reservationAuthority,
            @Min(0) long expectedReservationVersion,
            @Min(1) @Max(10000) int attendeeCount,
            @Size(max = 80) String costCenter,
            @Size(max = 2000) String specialRequest,
            @NotEmpty @Size(max = 20) List<@Valid ServiceLineRequest> lines) { }

    public record PreviewLine(
            UUID catalogItemId,
            Long catalogVersion,
            String serviceCode,
            ServiceCategory category,
            String nameKo,
            String nameEn,
            ProviderState providerState,
            String providerCode,
            Long providerConfigurationVersion,
            List<UUID> siteScope,
            List<String> supportedResourceTypes,
            JsonNode optionSchema,
            Integer minimumQuantity,
            Integer maximumQuantity,
            Integer orderCutoffMinutes,
            Integer cancellationCutoffMinutes,
            Integer slaResponseMinutes,
            Integer slaFulfillmentLeadMinutes,
            String cancellationPolicyKo,
            String cancellationPolicyEn,
            CapacityMode capacityMode,
            int capacityFreshnessSeconds,
            InspectionMode inspectionMode,
            JsonNode inspectionChecklistSchema,
            int quantity,
            JsonNode options,
            BigDecimal unitPrice,
            String currency,
            BigDecimal estimatedCost,
            CapacityReservation capacityReservation,
            List<String> limitations) { }

    @Schema(name = "WorkplaceServiceOrderPreview")
    public record ServiceOrderPreview(
            UUID previewId,
            ReservationAuthority reservationAuthority,
            UUID reservationId,
            long reservationVersion,
            OffsetDateTime reservationStartsAt,
            OffsetDateTime reservationEndsAt,
            String siteReference,
            String resourceReference,
            int attendeeCount,
            String costCenter,
            String specialRequest,
            BigDecimal estimatedCost,
            String currency,
            boolean eligible,
            List<String> limitations,
            List<PreviewLine> lines,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }

    @Schema(name = "WorkplaceServiceOrderSubmitRequest")
    public record SubmitRequest(
            @NotNull UUID previewId,
            @Min(0) long expectedReservationVersion,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    @Schema(name = "WorkplaceServiceOrderCancelRequest")
    public record CancelRequest(
            @Min(1) long expectedVersion,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    @Schema(name = "WorkplaceServiceOrderMessageRequest")
    public record MessageRequest(
            @Min(1) long expectedVersion,
            @NotBlank @Size(max = 2000) String message,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    @Schema(name = "WorkplaceServiceOrderReconfirmRequest")
    public record ReconfirmRequest(
            @Min(1) long expectedVersion,
            @Min(0) long expectedReservationVersion,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record AttachmentUpload(
            String fileName,
            String contentType,
            long byteSize,
            String checksumSha256) { }

    @Schema(name = "WorkplaceServiceLineCancellationImpactRequest")
    public record LineCancellationImpactRequest(
            @Min(1) long expectedOrderVersion,
            @Min(1) long expectedLineVersion,
            @Min(1) int cancelQuantity,
            @NotBlank @Size(max = 500) String reason) { }

    @Schema(name = "WorkplaceServiceLineCancellationImpact")
    public record LineCancellationImpact(
            UUID cancellationPreviewId,
            UUID serviceOrderId,
            UUID serviceOrderLineId,
            long orderVersion,
            long lineVersion,
            int cancelQuantity,
            int fulfilledQuantity,
            int previouslyCancelledQuantity,
            int remainingQuantity,
            RefundScope refundScope,
            BigDecimal refundableAmount,
            String currency,
            boolean eligible,
            String reason,
            OffsetDateTime expiresAt,
            OffsetDateTime generatedAt) { }

    @Schema(name = "WorkplaceServiceLineCancellationRequest")
    public record LineCancellationRequest(
            @NotNull UUID cancellationPreviewId,
            @Min(1) long expectedOrderVersion,
            @Min(1) long expectedLineVersion,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record LineAdjustment(
            UUID lineAdjustmentId,
            UUID serviceOrderId,
            UUID serviceOrderLineId,
            UUID cancellationPreviewId,
            int cancelQuantity,
            RefundScope refundScope,
            BigDecimal refundableAmount,
            BigDecimal refundedAmount,
            String currency,
            LineAdjustmentState state,
            String providerOperationReference,
            String refundReceiptReference,
            String resultDetail,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) { }

    public record LineAdjustmentCommandResult(
            LineAdjustment adjustment,
            ServiceOrder order,
            CommandReceipt receipt) { }

    @Schema(name = "WorkplaceServiceLineAdjustmentReconcileRequest")
    public record LineAdjustmentReconcileRequest(
            @Min(1) long expectedVersion,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    @Schema(name = "WorkplaceServiceAttachmentScanRequest")
    public record AttachmentScanRequest(
            @Min(1) long expectedVersion,
            @NotNull AttachmentScanVerdict verdict,
            @NotBlank @Size(max = 320) String scannerEvidenceReference,
            @Size(max = 1000) String detail,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record AttachmentScanCommandResult(
            ServiceOrderAttachment attachment,
            CommandReceipt receipt) { }

    @Schema(name = "WorkplaceServiceFulfillmentUpdateRequest")
    public record FulfillmentUpdateRequest(
            @Min(1) long expectedVersion,
            @NotNull WorkState state,
            Long assigneeUserId,
            @Size(max = 320) String externalFulfillmentReference,
            @Size(max = 120) String blockerCode,
            @Size(max = 1000) String blockerDetail,
            @Size(max = 1000) String resultDetail,
            @Min(0) Integer fulfilledQuantity,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ServiceOrderLine(
            UUID serviceOrderLineId,
            UUID catalogItemId,
            String serviceCode,
            ServiceCategory category,
            String nameKo,
            String nameEn,
            ProviderState providerState,
            String providerCode,
            long catalogVersion,
            long providerConfigurationVersion,
            List<UUID> siteScope,
            List<String> supportedResourceTypes,
            int quantity,
            JsonNode options,
            JsonNode optionSchema,
            BigDecimal unitPrice,
            BigDecimal estimatedCost,
            String currency,
            int minimumQuantity,
            int maximumQuantity,
            int orderCutoffMinutes,
            int cancellationCutoffMinutes,
            String cancellationPolicyKo,
            String cancellationPolicyEn,
            int slaResponseMinutes,
            int slaFulfillmentLeadMinutes,
            InspectionMode inspectionMode,
            JsonNode inspectionChecklistSchema,
            WorkState state,
            int fulfilledQuantity,
            int cancelledQuantity,
            BigDecimal refundedAmount,
            String blockerCode,
            long version) { }

    public record FulfillmentTask(
            UUID fulfillmentTaskId,
            UUID serviceOrderLineId,
            WorkState state,
            ProviderState providerState,
            String providerCode,
            Long assigneeUserId,
            String assigneeDirectorySubjectId,
            String assigneeDisplayName,
            String assigneeSecondaryLabel,
            OffsetDateTime responseDueAt,
            OffsetDateTime dueAt,
            OffsetDateTime providerReceiptAt,
            OffsetDateTime acceptedAt,
            OffsetDateTime completedAt,
            String externalFulfillmentReference,
            String blockerCode,
            String blockerDetail,
            String resultDetail,
            long responseRemainingSeconds,
            long fulfillmentRemainingSeconds,
            boolean responseBreached,
            boolean fulfillmentBreached,
            long version,
            OffsetDateTime updatedAt) { }

    public record ServiceOrderEvent(
            UUID eventId,
            String eventType,
            long actorUserId,
            JsonNode detail,
            OffsetDateTime occurredAt) { }

    public record ServiceOrderMessage(
            UUID messageId,
            long authorUserId,
            String authorDisplayName,
            String authorRole,
            String message,
            OffsetDateTime createdAt) { }

    public record RequesterServiceOrderEvent(
            UUID eventId,
            String eventType,
            JsonNode detail,
            OffsetDateTime occurredAt) { }

    public record RequesterServiceOrderMessage(
            UUID messageId,
            String authorDisplayName,
            String authorRole,
            String message,
            OffsetDateTime createdAt) { }

    public record ServiceOrderAttachment(
            UUID attachmentId,
            String fileName,
            String contentType,
            long byteSize,
            AttachmentScanState scanState,
            long scanVersion,
            String scannerEvidenceReference,
            String scanDetail,
            OffsetDateTime scannedAt,
            OffsetDateTime createdAt) { }

    @Schema(name = "WorkplaceServiceOrder")
    public record ServiceOrder(
            UUID serviceOrderId,
            long requesterUserId,
            ReservationAuthority reservationAuthority,
            UUID reservationId,
            long reservationVersion,
            Long currentReservationVersion,
            OffsetDateTime reservationStartsAt,
            OffsetDateTime reservationEndsAt,
            String siteReference,
            String resourceReference,
            int attendeeCount,
            String costCenter,
            BigDecimal estimatedCost,
            String currency,
            String specialRequest,
            OrderState state,
            ReservationImpact reservationImpact,
            boolean reconfirmationRequired,
            String providerOperationReference,
            String resultDetail,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            List<ServiceOrderLine> lines,
            List<FulfillmentTask> tasks,
            long eventCount,
            long messageCount,
            long attachmentCount) { }

    public record ServiceOrders(
            List<ServiceOrder> items,
            String nextCursor,
            boolean hasMore,
            OffsetDateTime generatedAt) { }

    public record ServiceOrderEventsPage(
            List<ServiceOrderEvent> items,
            String nextCursor,
            boolean hasMore,
            OffsetDateTime generatedAt) { }

    public record RequesterServiceOrderEventsPage(
            List<RequesterServiceOrderEvent> items,
            String nextCursor,
            boolean hasMore,
            OffsetDateTime generatedAt) { }

    public record ServiceOrderMessagesPage(
            List<ServiceOrderMessage> items,
            String nextCursor,
            boolean hasMore,
            OffsetDateTime generatedAt) { }

    public record RequesterServiceOrderMessagesPage(
            List<RequesterServiceOrderMessage> items,
            String nextCursor,
            boolean hasMore,
            OffsetDateTime generatedAt) { }

    public record ServiceOrderAttachmentsPage(
            List<ServiceOrderAttachment> items,
            String nextCursor,
            boolean hasMore,
            OffsetDateTime generatedAt) { }

    public record CommandReceipt(
            UUID commandId,
            UUID serviceOrderId,
            CommandState state,
            String statusHref,
            boolean replayed,
            String correlationId,
            OffsetDateTime acceptedAt) { }

    public record ServiceOrderCommandResult(ServiceOrder order, CommandReceipt receipt) { }

    record ReservationSnapshot(
            ReservationAuthority authority,
            UUID reservationId,
            long version,
            long ownerUserId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String siteReference,
            String resourceReference,
            String resourceType,
            boolean active) { }
}
