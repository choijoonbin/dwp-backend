package com.dwp.services.platform.workplace.workplaceservices;

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

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.CommandState;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.ProviderState;

public final class WorkplaceServiceOperationsDtos {
    private WorkplaceServiceOperationsDtos() { }

    public enum ProviderLifecycleState { DRAFT, ACTIVE, SUSPENDED, RETIRED }
    public enum CapacityMode { UNBOUNDED, BUCKETED }
    public enum InspectionMode { NONE, OPERATOR, REQUESTER }
    public enum InspectionDecision { PASSED, FAILED }
    public enum InspectionActorRole { OPERATOR, REQUESTER }
    public enum ContactTargetType { ASSIGNEE, SERVICE_DESK }
    public enum AccessGrantState { ISSUED, REVOKED, EXPIRED, RESULT_UNKNOWN }

    @Schema(name = "WorkplaceServiceProviderProfile")
    public record ProviderProfile(
            UUID providerProfileId,
            String providerCode,
            String displayNameKo,
            String displayNameEn,
            String adapterType,
            ProviderLifecycleState lifecycleState,
            List<UUID> siteScope,
            List<String> capabilities,
            JsonNode support,
            boolean credentialBindingConfigured,
            long configurationVersion,
            ProviderState readiness,
            Long evidenceConfigurationVersion,
            String evidenceReference,
            OffsetDateTime evidenceObservedAt,
            OffsetDateTime evidenceReceivedAt,
            String evidenceErrorCode,
            long version,
            OffsetDateTime updatedAt) { }

    public record ProviderProfiles(List<ProviderProfile> items, OffsetDateTime generatedAt) { }

    @Schema(name = "WorkplaceServiceProviderCreateRequest")
    public record ProviderCreateRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,80}") String providerCode,
            @NotBlank @Size(max = 160) String displayNameKo,
            @NotBlank @Size(max = 160) String displayNameEn,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,80}") String adapterType,
            @NotNull @Size(max = 100) List<@NotNull UUID> siteScope,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 80) String> capabilities,
            @NotNull JsonNode support,
            @Size(max = 320) String credentialBindingReference,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    @Schema(name = "WorkplaceServiceProviderUpdateRequest")
    public record ProviderUpdateRequest(
            @Min(1) long expectedVersion,
            @NotBlank @Size(max = 160) String displayNameKo,
            @NotBlank @Size(max = 160) String displayNameEn,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,80}") String adapterType,
            @NotNull @Size(max = 100) List<@NotNull UUID> siteScope,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 80) String> capabilities,
            @NotNull JsonNode support,
            @Size(max = 320) String credentialBindingReference,
            boolean clearCredentialBinding,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record ProviderStateRequest(
            @Min(1) long expectedVersion,
            @NotNull ProviderLifecycleState lifecycleState,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record ProviderVerifyRequest(
            @Min(1) long expectedVersion,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record OperationsCommandReceipt(
            UUID commandId,
            CommandState state,
            String statusHref,
            boolean replayed,
            String correlationId,
            OffsetDateTime acceptedAt) { }

    public record ProviderCommandResult(
            ProviderProfile provider,
            OperationsCommandReceipt receipt) { }

    public record AssigneeProjection(
            String directorySubjectId,
            String displayName,
            boolean contactAvailable,
            List<String> capabilities,
            String directoryVersion,
            OffsetDateTime verifiedAt,
            OffsetDateTime freshUntil) { }

    public record AssigneeSearchResult(
            List<AssigneeProjection> items,
            OffsetDateTime generatedAt) { }

    public record AssigneeAssignmentRequest(
            @NotBlank @Size(max = 160) String directorySubjectId,
            @Min(1) long expectedTaskVersion,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record AssigneeAssignmentResult(
            UUID serviceOrderId,
            UUID fulfillmentTaskId,
            AssigneeProjection assignee,
            long taskVersion,
            OperationsCommandReceipt receipt) { }

    public record CapacityBucket(
            UUID capacityBucketId,
            UUID catalogItemId,
            String siteReference,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            int capacityLimit,
            int committedQuantity,
            int heldQuantity,
            int availableQuantity,
            String sourceVersion,
            OffsetDateTime sourceObservedAt,
            OffsetDateTime receivedAt,
            OffsetDateTime freshUntil,
            boolean fresh,
            long version) { }

    public record CapacityRange(
            UUID catalogItemId,
            String siteReference,
            OffsetDateTime from,
            OffsetDateTime to,
            CapacityMode mode,
            List<CapacityBucket> buckets,
            boolean complete,
            List<String> limitations,
            OffsetDateTime generatedAt) { }

    public record CapacityBucketInput(
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            @Min(0) @Max(1_000_000) int capacityLimit,
            @NotBlank @Size(max = 160) String sourceVersion,
            @NotNull OffsetDateTime sourceObservedAt) { }

    public record CapacityUpsertRequest(
            @NotBlank @Size(max = 160) String siteReference,
            @NotEmpty @Size(max = 1000) List<@Valid CapacityBucketInput> buckets,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record CapacityUpsertResult(
            CapacityRange capacity,
            OperationsCommandReceipt receipt) { }

    public record CapacityReservation(
            List<UUID> holdIds,
            OffsetDateTime expiresAt,
            OffsetDateTime capacityFreshUntil) { }

    public record InspectionAttempt(
            UUID inspectionAttemptId,
            UUID serviceOrderId,
            UUID serviceOrderLineId,
            UUID fulfillmentTaskId,
            InspectionMode mode,
            InspectionActorRole actorRole,
            InspectionDecision decision,
            JsonNode checklistSchema,
            JsonNode checklistResponses,
            List<UUID> evidenceAttachmentIds,
            String reason,
            boolean remediationRequired,
            OffsetDateTime createdAt) { }

    public record InspectionStatus(
            UUID serviceOrderId,
            UUID serviceOrderLineId,
            InspectionMode mode,
            boolean required,
            boolean fulfilledQuantityReady,
            boolean accepted,
            boolean remediationRequired,
            InspectionAttempt latestAttempt,
            OffsetDateTime generatedAt) { }

    public record InspectionAttemptRequest(
            @NotNull InspectionDecision decision,
            @NotNull JsonNode checklistResponses,
            @NotNull @Size(max = 20) List<@NotNull UUID> attachmentIds,
            @Min(1) long expectedOrderVersion,
            @Min(1) long expectedTaskVersion,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record InspectionCommandResult(
            InspectionStatus inspection,
            OperationsCommandReceipt receipt) { }

    public record AccessCredentialIssueRequest(
            @NotBlank @Size(max = 500) String stepUpReceipt,
            @Min(1) long expectedOrderVersion,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record AccessCredentialIssueResult(
            UUID grantId,
            String oneTimeCredential,
            OffsetDateTime expiresAt,
            boolean revealOnce,
            OperationsCommandReceipt receipt) { }

    public record AccessCredentialRevokeRequest(
            @Min(1) long expectedOrderVersion,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record AccessCredentialStatus(
            UUID grantId,
            AccessGrantState state,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt,
            OffsetDateTime revokedAt,
            OperationsCommandReceipt receipt) { }

    public record ContactRequest(
            @NotNull ContactTargetType target,
            UUID serviceOrderLineId,
            @Min(1) long expectedOrderVersion,
            @NotBlank @Size(max = 2000) String message,
            boolean explicitConfirmation,
            @NotBlank @Size(max = 500) String reason) { }

    public record ContactResult(
            UUID contactRequestId,
            ContactTargetType target,
            String resolvedTargetDisplayName,
            String state,
            OperationsCommandReceipt receipt) { }
}
