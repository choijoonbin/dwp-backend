package com.dwp.services.platform.workplace.workplacevisits;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkplaceVisitDtos {
    private WorkplaceVisitDtos() { }

    public enum ReservationAuthority { WORKPLACE, CALENDAR }
    public enum VisitState {
        DRAFT, PREVIEWED, INVITED, APPROVAL_PENDING, APPROVED, ACCESS_PENDING,
        READY, ARRIVED, CHECKED_OUT, REJECTED, CANCELLED, ACCESS_FAILED,
        OVERSTAY, RESULT_UNKNOWN
    }
    public enum ProviderKind { VISITOR, ACCESS }
    public enum ProviderTruthState {
        NOT_CONFIGURED, CONFIGURED_UNVERIFIED, READY, DEGRADED, STALE
    }
    public enum CommandState { ACCEPTED, SUCCEEDED, FAILED, RESULT_UNKNOWN }
    public enum KioskState {
        READY, OFFLINE, UNREGISTERED, WRONG_SITE, PRIVACY_NOTICE_REQUIRED,
        PROVIDER_UNAVAILABLE, HELP_REQUESTED, RETIRED
    }
    public enum ExceptionKind {
        APPROVAL_PENDING, ACCESS_FAILED, HOST_UNRESPONSIVE, OVERSTAY, RESULT_UNKNOWN
    }

    public record ReservationReference(
            @NotNull ReservationAuthority authority,
            @NotNull UUID id,
            @Min(0) long version) { }

    public record GuestRefInput(
            @NotBlank @Size(max = 320)
            @Pattern(regexp = "^[A-Za-z0-9._~:/-]{8,320}$") String opaqueRef,
            @NotBlank @Size(max = 160) String maskedLabel,
            @NotBlank @Size(max = 500) String purpose,
            @NotEmpty @Size(max = 12)
            Map<@Pattern(regexp = "^[a-z][a-zA-Z0-9]{1,39}$") String,
                    @NotNull OffsetDateTime> fieldRetentionExpiresAt) { }

    public record GuestRefView(
            String opaqueRef,
            String maskedLabel,
            String purpose,
            Map<String, OffsetDateTime> fieldRetentionExpiresAt) { }

    public record ProviderTruth(
            ProviderKind kind,
            ProviderTruthState state,
            long configurationVersion,
            Long observedConfigurationVersion,
            String evidenceReference,
            OffsetDateTime lastSuccessAt,
            OffsetDateTime sourceAt,
            OffsetDateTime receivedAt,
            String limitationCode,
            String manualOwner,
            String manualProcedure) { }

    public record VisitPreviewRequest(
            @Valid @NotNull ReservationReference reservation,
            @NotBlank @Size(max = 80) String visitType,
            @NotNull UUID siteId,
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            @NotEmpty @Size(max = 32) List<@NotNull UUID> zoneIds,
            @Valid @NotEmpty @Size(max = 50) List<GuestRefInput> guests,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record VisitPreview(
            UUID previewId,
            long version,
            ReservationReference reservation,
            String visitType,
            UUID siteId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            List<UUID> zoneIds,
            int guestCount,
            boolean approvalRequired,
            boolean ndaRequired,
            boolean identityVerificationRequired,
            List<String> minimumCollectionFields,
            ProviderTruth visitorProvider,
            ProviderTruth accessProvider,
            boolean eligible,
            List<String> limitations,
            OffsetDateTime expiresAt,
            OffsetDateTime generatedAt) { }

    public record CreateVisitRequest(
            @NotNull UUID previewId,
            @Min(1) long expectedPreviewVersion,
            @Valid @NotEmpty @Size(max = 50) List<GuestRefInput> guests,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record VersionCommand(
            @Min(1) long expectedVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ApprovalCommand(
            @Min(1) long expectedVersion,
            boolean approved,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record TimelineItem(
            UUID eventId,
            String eventType,
            VisitState state,
            String detailCode,
            OffsetDateTime occurredAt) { }

    public record RequesterVisit(
            UUID visitId,
            ReservationReference reservation,
            String visitType,
            UUID siteId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            List<UUID> zoneIds,
            List<GuestRefView> guests,
            VisitState state,
            long version,
            boolean recoveryByGetOnly,
            String recoveryHref,
            List<TimelineItem> timeline,
            OffsetDateTime updatedAt) { }

    public record AdminVisit(
            UUID visitId,
            long requesterUserId,
            ReservationReference reservation,
            String visitType,
            UUID siteId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            List<UUID> zoneIds,
            List<GuestRefView> guests,
            VisitState state,
            long version,
            String providerOperationEvidenceReference,
            String limitationCode,
            List<TimelineItem> timeline,
            OffsetDateTime updatedAt) { }

    public record KioskVisit(
            UUID visitId,
            String maskedLabel,
            String purpose,
            UUID siteId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            VisitState state,
            long version) { }

    public record VisitPage<T>(List<T> items, OffsetDateTime generatedAt) { }

    public record CommandReceipt(
            UUID commandId,
            UUID visitId,
            CommandState state,
            String statusHref,
            boolean replayed,
            String correlationId,
            OffsetDateTime acceptedAt) { }

    public record VisitCommandResult(RequesterVisit visit, CommandReceipt receipt) { }
    public record AdminVisitCommandResult(AdminVisit visit, CommandReceipt receipt) { }

    public record ManagementReceipt(
            UUID commandId, String resourceType, UUID resourceId, long resourceVersion,
            boolean replayed, String correlationId, OffsetDateTime acceptedAt) { }
    public record ManagementResult<T>(T item, ManagementReceipt receipt) { }

    public record VisitException(
            UUID visitId,
            ExceptionKind kind,
            VisitState state,
            String maskedGuestLabel,
            UUID siteId,
            OffsetDateTime startsAt,
            long version,
            String limitationCode,
            OffsetDateTime updatedAt) { }

    public record VisitPolicyRequest(
            @NotBlank @Size(max = 80) String visitType,
            boolean approvalRequired,
            boolean ndaRequired,
            boolean identityVerificationRequired,
            @NotNull LocalTime allowedFrom,
            @NotNull LocalTime allowedUntil,
            @NotEmpty @Size(max = 12) List<@NotBlank @Size(max = 40) String> minimumCollectionFields,
            @Min(1) @Max(3650) int retentionDays,
            @Min(0) long expectedVersion,
            boolean active,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record VisitPolicy(
            UUID policyId, String visitType, boolean approvalRequired, boolean ndaRequired,
            boolean identityVerificationRequired, LocalTime allowedFrom, LocalTime allowedUntil,
            List<String> minimumCollectionFields, int retentionDays, boolean active,
            long version, OffsetDateTime updatedAt) { }

    public record PolicyImpactPreview(
            UUID policyId, long currentVersion, long affectedFutureVisits,
            List<String> warnings, OffsetDateTime generatedAt) { }

    public record AccessZoneRequest(
            @NotNull UUID siteId,
            @NotBlank @Size(max = 80) String zoneCode,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 40) String accessLevel,
            @NotBlank @Size(max = 120) String providerMappingReference,
            @NotEmpty @Size(max = 32) List<@NotBlank @Size(max = 80) String> allowedVisitTypes,
            @Min(0) long expectedVersion,
            boolean active,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record AccessZone(
            UUID zoneId, UUID siteId, String zoneCode, String name, String accessLevel,
            String providerMappingReference, List<String> allowedVisitTypes,
            boolean active, long version, OffsetDateTime updatedAt) { }

    public record ProviderBindingRequest(
            @NotNull ProviderKind kind,
            @NotBlank @Size(max = 80) String providerCode,
            @Min(1) long configurationVersion,
            @NotBlank @Size(max = 160) String manualOwner,
            @NotBlank @Size(max = 1000) String manualProcedure,
            @Min(0) long expectedVersion,
            boolean active,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ProviderEvidenceRequest(
            @Min(1) long expectedVersion,
            @Min(1) long observedConfigurationVersion,
            @NotNull ProviderTruthState reportedState,
            @NotBlank @Size(max = 320) String evidenceReference,
            @NotNull OffsetDateTime sourceAt,
            @NotNull OffsetDateTime receivedAt,
            OffsetDateTime lastSuccessAt,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ProviderBinding(
            UUID bindingId, ProviderKind kind, String providerCode,
            long configurationVersion, Long observedConfigurationVersion,
            ProviderTruthState state, String evidenceReference, OffsetDateTime lastSuccessAt,
            OffsetDateTime sourceAt, OffsetDateTime receivedAt, String manualOwner,
            String manualProcedure, boolean active, long version, OffsetDateTime updatedAt) { }

    public record KioskDeviceRequest(
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String deviceIdentitySha256,
            @NotNull UUID siteId,
            UUID policyId,
            @NotBlank @Size(max = 80) String privacyNoticeVersion,
            @Min(0) long expectedVersion,
            boolean active,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record KioskHeartbeatRequest(
            @Min(1) long expectedVersion,
            @NotBlank @Size(max = 80) String privacyNoticeVersion,
            boolean privacyNoticeAccepted,
            @NotNull OffsetDateTime observedAt) { }

    public record KioskDevice(
            UUID deviceId, UUID siteId, UUID policyId, String privacyNoticeVersion,
            boolean privacyNoticeAccepted, OffsetDateTime lastHeartbeatAt, boolean helpRequested,
            KioskState state, boolean active, long version, OffsetDateTime updatedAt) { }

    record ReservationSnapshot(
            ReservationAuthority authority, UUID id, long version, long ownerUserId,
            OffsetDateTime startsAt, OffsetDateTime endsAt, UUID siteId, boolean active) { }
}
