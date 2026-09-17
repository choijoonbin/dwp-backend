package com.dwp.services.platform.workplace.workplaceassistant;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.BookingBatch;

public final class WorkplaceAssistantDtos {
    private WorkplaceAssistantDtos() { }

    public enum RequestState {
        SUGGESTED,
        VALIDATED,
        AWAITING_CONFIRMATION,
        PROCESSING,
        SUCCEEDED,
        PARTIAL,
        FAILED,
        RESULT_UNKNOWN
    }

    public enum SelectionMode { SELECTED, ALL }
    public enum ConfirmationFailurePolicy { KEEP_SUCCEEDED, COMPENSATE_ALL }
    public enum PolicyResult { UNVALIDATED, ALLOWED, DENIED, CONFLICT, UNAVAILABLE }
    public enum RedactionState { APPLIED, NOT_REQUIRED, RETAINED_CONTENT_DELETED }
    public enum GovernanceRedactionState { READY, BLOCKED }
    public enum FeedbackRating { HELPFUL, NOT_HELPFUL }
    public enum CommandState { ACCEPTED, SUCCEEDED, FAILED, RESULT_UNKNOWN }

    @Schema(name = "WorkplaceAssistantRequestedBookingItem")
    public record RequestedBookingItem(
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

    @Schema(name = "WorkplaceAssistantCreateRequest")
    public record CreateAssistantRequest(
            @NotBlank @Size(max = 4000) String requestText,
            @NotEmpty @Size(max = 50) List<@Valid RequestedBookingItem> requestedItems,
            boolean requestProcessingConsent,
            boolean feedbackUseConsent,
            @NotBlank @Size(max = 500) String reason) { }

    public record AlternativeOption(
            UUID resourceId,
            String displayName,
            ResourceType resourceType,
            UUID siteId,
            UUID floorId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            boolean waitlistEligible,
            String rationale) { }

    @Schema(name = "WorkplaceAssistantProposalItem")
    public record ProposalItem(
            UUID proposalItemId,
            RequestedBookingItem requestedItem,
            String rationale,
            List<String> constraintsUsed,
            List<String> exclusions,
            PolicyResult policyResult,
            List<String> conflicts,
            List<AlternativeOption> alternatives,
            UUID authoritativeIntentItemId,
            Long authoritativeIntentItemVersion,
            UUID selectedResourceId,
            Long selectedResourceVersion,
            String selectedResourceName,
            long version) { }

    public record AuthorityValidationSnapshot(
            UUID bookingIntentId,
            long bookingIntentVersion,
            String bookingIntentState,
            boolean allSelectedItemsValid,
            OffsetDateTime validatedAt,
            List<String> limitations) { }

    public record ConsentSnapshot(
            boolean requestProcessingConsent,
            boolean feedbackUseConsent,
            boolean tenantOptIn,
            boolean feedbackUseEnabled) { }

    @Schema(name = "WorkplaceAssistantRequest")
    public record AssistantRequest(
            UUID requestId,
            RequestState state,
            String redactedRequestText,
            RedactionState redactionState,
            ConsentSnapshot consent,
            String modelProviderReference,
            String modelVersion,
            String promptVersion,
            String toolVersion,
            List<ProposalItem> proposals,
            AuthorityValidationSnapshot validation,
            UUID bookingBatchId,
            String batchStatusHref,
            boolean requeryRequired,
            String lastResultCode,
            List<String> limitations,
            long version,
            OffsetDateTime retentionExpiresAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) { }

    @Schema(name = "WorkplaceAssistantValidateRequest")
    public record ValidateAssistantRequest(
            @Min(1) long expectedVersion,
            @NotNull SelectionMode selectionMode,
            @NotNull @Size(max = 50) List<@NotNull UUID> selectedProposalItemIds,
            @Min(30) @Max(300) Integer requestedHoldTtlSeconds,
            boolean allowAlternatives,
            @NotBlank @Size(max = 500) String reason) { }

    @Schema(name = "WorkplaceAssistantConfirmRequest")
    public record ConfirmAssistantRequest(
            @Min(1) long expectedVersion,
            @NotNull SelectionMode selectionMode,
            @NotNull @Size(max = 50) List<@NotNull UUID> selectedProposalItemIds,
            @NotNull ConfirmationFailurePolicy failurePolicy,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    @Schema(name = "WorkplaceAssistantExecution")
    public record AssistantExecution(
            UUID requestId,
            RequestState state,
            UUID bookingIntentId,
            UUID bookingBatchId,
            BookingBatch authoritativeBatch,
            boolean requeryRequired,
            String recoveryGuidance,
            OffsetDateTime refreshedAt,
            long requestVersion) { }

    @Schema(name = "WorkplaceAssistantFeedbackRequest")
    public record FeedbackRequest(
            @Min(1) long expectedVersion,
            @NotNull FeedbackRating rating,
            @Size(max = 2000) String comment,
            boolean allowModelImprovementUse,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record FeedbackReceipt(
            UUID feedbackId,
            UUID requestId,
            FeedbackRating rating,
            String redactedComment,
            boolean eligibleForModelImprovementUse,
            UUID auditEventId,
            OffsetDateTime createdAt) { }

    @Schema(name = "WorkplaceAssistantGovernance")
    public record AssistantGovernance(
            boolean tenantOptIn,
            boolean killSwitch,
            String modelProviderReference,
            String modelVersion,
            String promptVersion,
            String toolVersion,
            @Min(1) @Max(365) int retentionDays,
            boolean feedbackUseEnabled,
            GovernanceRedactionState redactionState,
            long version,
            OffsetDateTime updatedAt,
            Long updatedBy) { }

    @Schema(name = "WorkplaceAssistantGovernanceUpdateRequest")
    public record GovernanceUpdateRequest(
            @Min(0) long expectedVersion,
            boolean tenantOptIn,
            boolean killSwitch,
            @Size(max = 160) @Pattern(regexp = "[A-Za-z0-9._:/-]*")
                    String modelProviderReference,
            @Size(max = 120) String modelVersion,
            @Size(max = 120) String promptVersion,
            @Size(max = 120) String toolVersion,
            @Min(1) @Max(365) int retentionDays,
            boolean feedbackUseEnabled,
            @NotNull GovernanceRedactionState redactionState,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record CommandReceipt(
            UUID commandId,
            CommandState state,
            String statusHref,
            boolean replayed,
            String correlationId,
            String resultCode,
            OffsetDateTime acceptedAt,
            OffsetDateTime completedAt) { }

    public record AssistantCommandResult(
            AssistantRequest request,
            CommandReceipt receipt) { }

    public record GovernanceCommandResult(
            AssistantGovernance governance,
            CommandReceipt receipt) { }

    public record AuditEvent(
            UUID auditEventId,
            UUID requestId,
            String eventType,
            long actorUserId,
            JsonNode metadata,
            String correlationId,
            OffsetDateTime createdAt) { }

    public record AuditEvents(
            List<AuditEvent> items,
            OffsetDateTime generatedAt) { }
}
