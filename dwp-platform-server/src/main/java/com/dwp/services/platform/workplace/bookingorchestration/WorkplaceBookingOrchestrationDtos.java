package com.dwp.services.platform.workplace.bookingorchestration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.DecimalMin;
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

import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;

public final class WorkplaceBookingOrchestrationDtos {
    private WorkplaceBookingOrchestrationDtos() { }

    public enum IntentState { PREVIEWED, HELD, CONFIRMING, COMPLETED, EXPIRED }
    public enum IntentItemDecision { AVAILABLE, ALTERNATIVES_AVAILABLE, UNAVAILABLE, POLICY_DENIED }
    public enum HoldState { ACTIVE, BATCHED, CONSUMED, RELEASED, EXPIRED }
    public enum FailurePolicy { KEEP_SUCCEEDED, COMPENSATE_ALL }
    public enum BookingAuthority { WORKPLACE, CALENDAR }
    public enum BatchState {
        ACCEPTED, PROCESSING, SUCCEEDED, PARTIAL, FAILED,
        COMPENSATING, COMPENSATED, RESULT_UNKNOWN
    }
    public enum BatchItemState {
        PENDING, PROCESSING, SUCCEEDED, FAILED, RESULT_UNKNOWN,
        COMPENSATION_PENDING, COMPENSATED, COMPENSATION_FAILED
    }
    public enum WaitlistState { ACTIVE, OFFERED, CONFIRMING, CONFIRMED, CANCELLED, EXPIRED }
    public enum OfferState { OFFERED, ACCEPTING, ACCEPTED, EXPIRED, WITHDRAWN, RESULT_UNKNOWN }
    public enum PromotionEvaluationState { PENDING, NO_MATCH, BLOCKED, MATCHED }
    public enum NotificationChannel { IN_APP, EMAIL, PUSH }
    public enum ConstraintEvaluationState { SATISFIED, UNSATISFIED, UNSUPPORTED }
    public enum PricingMode { MANAGED, NOT_APPLICABLE }

    @Schema(name = "WorkplaceAuthorizedBookingBeneficiary")
    public record AuthorizedBeneficiary(
            long beneficiaryUserId,
            UUID beneficiaryPersonPublicId,
            String displayName,
            UUID delegationGrantId,
            List<ResourceType> resourceTypes,
            OffsetDateTime validUntil,
            boolean self) { }

    @Schema(name = "WorkplaceAuthorizedBookingBeneficiaries")
    public record AuthorizedBeneficiaries(
            List<AuthorizedBeneficiary> beneficiaries,
            OffsetDateTime generatedAt) { }

    @Schema(name = "WorkplaceBookingIntentItemRequest")
    public record IntentItemRequest(
            @NotBlank @Size(max = 120) String clientItemKey,
            @NotNull @Min(1) Long beneficiaryUserId,
            UUID beneficiaryPersonPublicId,
            @NotBlank @Size(max = 160) String beneficiaryDisplayName,
            UUID delegationGrantId,
            @NotNull ResourceType resourceType,
            UUID preferredResourceId,
            UUID siteId,
            UUID floorId,
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            @Size(max = 500) String purpose,
            boolean visibleToColleagues,
            boolean accessibleOnly,
            @NotNull @Size(max = 20)
                    List<@NotBlank @Size(max = 80) String> requiredFeatures) { }

    @Schema(name = "WorkplaceBookingIntentPreviewRequest")
    public record IntentPreviewRequest(
            @NotEmpty @Size(max = 50) List<@Valid IntentItemRequest> items,
            @Min(30) @Max(300) Integer requestedHoldTtlSeconds,
            boolean allowAlternatives,
            @NotNull @Size(max = 20)
                    List<@Valid TeamPlacementConstraint> teamPlacementConstraints,
            @NotBlank @Size(max = 500) String reason) {
        public IntentPreviewRequest(
                List<IntentItemRequest> items,
                Integer requestedHoldTtlSeconds,
                boolean allowAlternatives,
                String reason) {
            this(items, requestedHoldTtlSeconds, allowAlternatives, List.of(), reason);
        }
    }

    @Schema(name = "WorkplaceTeamPlacementConstraint")
    public record TeamPlacementConstraint(
            @NotBlank @Size(max = 80) String groupKey,
            @NotEmpty @Size(min = 2, max = 50)
                    List<@NotBlank @Size(max = 120) String> clientItemKeys,
            boolean sameNeighborhood,
            boolean adjacentSeats,
            @DecimalMin("0.0") BigDecimal minimumDistanceMeters,
            @DecimalMin("0.0") BigDecimal maximumDistanceMeters) { }

    @Schema(name = "WorkplaceTeamPlacementConstraintEvidence")
    public record TeamPlacementConstraintEvidence(
            String groupKey,
            List<String> clientItemKeys,
            ConstraintEvaluationState state,
            String code,
            String message,
            List<UUID> selectedResourceIds) { }

    @Schema(name = "WorkplaceBookingCandidate")
    public record BookingCandidate(
            UUID resourceId,
            UUID calendarResourceId,
            String name,
            ResourceType resourceType,
            UUID siteId,
            UUID floorId,
            String timeZone,
            boolean accessible,
            List<String> features,
            String neighborhood,
            boolean preferred,
            long resourceVersion) { }

    @Schema(name = "WorkplaceBookingIntentItemPreview")
    public record IntentItemPreview(
            UUID intentItemId,
            String clientItemKey,
            long actorUserId,
            long beneficiaryUserId,
            UUID beneficiaryPersonPublicId,
            String beneficiaryDisplayName,
            UUID delegationGrantId,
            ResourceType resourceType,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            IntentItemDecision decision,
            String decisionCode,
            List<BookingCandidate> candidates,
            long version) { }

    @Schema(name = "WorkplaceBookingIntentPreview")
    public record BookingIntentPreview(
            UUID intentId,
            IntentState state,
            long actorUserId,
            int requestedHoldTtlSeconds,
            boolean allowAlternatives,
            String reason,
            List<IntentItemPreview> items,
            List<TeamPlacementConstraint> teamPlacementConstraints,
            List<TeamPlacementConstraintEvidence> placementConstraintEvidence,
            long version,
            OffsetDateTime createdAt) { }

    public record HoldSelection(
            @NotNull UUID intentItemId,
            @NotNull UUID resourceId,
            @NotNull @Min(1) Long expectedItemVersion,
            @NotNull @Min(0) Long expectedResourceVersion) { }

    @Schema(name = "WorkplaceBookingHoldRequest")
    public record HoldRequest(
            @NotNull @Min(1) Long expectedIntentVersion,
            @NotEmpty @Size(max = 50) List<@Valid HoldSelection> selections,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    @Schema(name = "WorkplaceReservationHold")
    public record ReservationHold(
            UUID holdId,
            UUID intentItemId,
            UUID resourceId,
            HoldState state,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime expiresAt,
            long version) { }

    @Schema(name = "WorkplaceBookingHoldResponse")
    public record HoldResponse(
            UUID intentId,
            IntentState intentState,
            long intentVersion,
            OffsetDateTime serverTime,
            List<ReservationHold> holds) { }

    @Schema(name = "WorkplaceBookingIntentStatus")
    public record BookingIntentStatus(
            BookingIntentPreview intent,
            List<ReservationHold> holds,
            UUID latestBatchId,
            String latestBatchStatusUrl,
            OffsetDateTime serverTime) { }

    public record HoldReference(
            @NotNull UUID holdId,
            @NotNull @Min(1) Long expectedVersion) { }

    @Schema(name = "WorkplaceBookingBatchStartRequest")
    public record BatchStartRequest(
            @NotNull UUID intentId,
            @NotNull @Min(1) Long expectedIntentVersion,
            @NotEmpty @Size(max = 50) List<@Valid HoldReference> holds,
            @NotNull FailurePolicy failurePolicy,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    @Schema(name = "WorkplaceBookingBatchStartResponse")
    public record BatchStartResponse(
            UUID batchId,
            BatchState state,
            String statusUrl,
            long version,
            OffsetDateTime acceptedAt) { }

    @Schema(name = "WorkplaceBookingBatchItem")
    public record BookingBatchItem(
            UUID batchItemId,
            UUID intentItemId,
            UUID holdId,
            String clientItemKey,
            long beneficiaryUserId,
            UUID beneficiaryPersonPublicId,
            String beneficiaryDisplayName,
            UUID delegationGrantId,
            ResourceType resourceType,
            UUID resourceId,
            String resourceDisplayName,
            UUID siteId,
            UUID floorId,
            String timeZone,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            BookingAuthority authority,
            BatchItemState state,
            UUID ownerReferenceId,
            Long ownerVersion,
            String errorCode,
            String errorMessage,
            boolean compensationAvailable,
            boolean requeryRequired,
            long version,
            OffsetDateTime updatedAt) { }

    @Schema(name = "WorkplaceBookingBatch")
    public record BookingBatch(
            UUID batchId,
            UUID intentId,
            long actorUserId,
            BatchState state,
            FailurePolicy failurePolicy,
            String reason,
            List<BookingBatchItem> items,
            boolean terminal,
            boolean requeryRequired,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt) { }

    @Schema(name = "WorkplaceBookingBatchCompensationRequest")
    public record BatchCompensationRequest(
            @NotNull @Min(1) Long expectedBatchVersion,
            @Size(max = 50) List<@NotNull UUID> batchItemIds,
            boolean compensateAllSucceeded,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    @Schema(name = "WorkplaceBookingBatchReplanRequest")
    public record BatchReplanRequest(
            @NotNull @Min(1) Long expectedBatchVersion,
            @NotEmpty @Size(max = 50) List<@NotNull UUID> batchItemIds,
            @Min(30) @Max(300) Integer requestedHoldTtlSeconds,
            boolean allowAlternatives,
            @NotBlank @Size(max = 500) String reason) { }

    @Schema(name = "WorkplaceWaitlistConditions")
    public record WaitlistConditions(
            @Min(0) @Max(100000) Integer maximumDistanceMeters,
            OffsetDateTime earliestStart,
            OffsetDateTime latestEnd,
            @NotNull PricingMode pricingMode,
            @DecimalMin("0.0") BigDecimal maximumPrice,
            @Pattern(regexp = "[A-Z]{3}") String currency) {
        public WaitlistConditions(
                Integer maximumDistanceMeters,
                OffsetDateTime earliestStart,
                OffsetDateTime latestEnd) {
            this(maximumDistanceMeters, earliestStart, latestEnd,
                    PricingMode.NOT_APPLICABLE, null, null);
        }
    }

    @Schema(name = "WorkplaceWaitlistCreateRequest")
    public record WaitlistCreateRequest(
            @NotNull @Valid IntentItemRequest item,
            boolean autoConfirm,
            @NotNull @Valid WaitlistConditions conditions,
            @NotEmpty @Size(max = 3) List<@NotNull NotificationChannel> notificationChannels,
            @NotBlank @Size(max = 500) String reason) { }

    @Schema(name = "WorkplaceWaitlistUpdateRequest")
    public record WaitlistUpdateRequest(
            @NotNull @Min(1) Long expectedVersion,
            boolean autoConfirm,
            @NotNull @Valid WaitlistConditions conditions,
            @NotEmpty @Size(max = 3) List<@NotNull NotificationChannel> notificationChannels,
            @NotBlank @Size(max = 500) String reason) { }

    @Schema(name = "WorkplaceWaitlistCancelRequest")
    public record WaitlistCancelRequest(
            @NotNull @Min(1) Long expectedVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    @Schema(name = "WorkplaceAlternativeOffer")
    public record AlternativeOffer(
            UUID offerId,
            UUID resourceId,
            String resourceDisplayName,
            UUID siteId,
            UUID floorId,
            String timeZone,
            UUID holdId,
            OfferState state,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime expiresAt,
            UUID acceptedBatchId,
            long version) { }

    @Schema(name = "WorkplaceWaitlistEntry")
    public record WaitlistEntry(
            UUID waitlistEntryId,
            long actorUserId,
            long beneficiaryUserId,
            UUID beneficiaryPersonPublicId,
            String beneficiaryDisplayName,
            ResourceType resourceType,
            UUID preferredResourceId,
            UUID siteId,
            UUID floorId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String purpose,
            boolean autoConfirm,
            WaitlistConditions conditions,
            List<NotificationChannel> notificationChannels,
            WaitlistState state,
            PromotionEvaluationState promotionEvaluationState,
            String promotionDecisionCode,
            OffsetDateTime promotionEvaluatedAt,
            Integer rank,
            boolean rankVisible,
            AlternativeOffer offer,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) { }

    @Schema(name = "WorkplaceWaitlistPage")
    public record WaitlistPage(
            List<WaitlistEntry> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            OffsetDateTime generatedAt) { }

    @Schema(name = "WorkplaceAlternativeOfferAcceptRequest")
    public record AlternativeOfferAcceptRequest(
            @NotNull @Min(1) Long expectedOfferVersion,
            @NotNull FailurePolicy failurePolicy,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

}
