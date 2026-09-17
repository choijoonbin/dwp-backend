package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WorkplaceExperienceCollaborationDtos {
    private WorkplaceExperienceCollaborationDtos() {}

    public enum PlanMode { OFFICE, REMOTE, OFF }
    public enum Visibility { PRIVATE, SITE, FLOOR, RESOURCE }
    /**
     * External authorities that can contribute data to the Workplace experience.
     *
     * <p>The connector configuration is deliberately provider neutral.  A configured
     * connector remains unverified until an approved adapter owns the runtime signal;
     * adding the source here only makes its configuration and ownership visible to an
     * administrator.</p>
     */
    public enum ConnectorKind {
        CALENDAR,
        ACTUAL_PRESENCE,
        ACCESS_CONTROL,
        SIGNAGE,
        VISITOR,
        VEHICLE,
        FACILITY_WORK_ORDER
    }
    public enum ConnectionState { NOT_CONFIGURED, DISABLED, CONFIGURED_UNVERIFIED }

    public record WorkPlan(UUID planId, LocalDate planDate, PlanMode mode, UUID siteId,
                           UUID floorId, UUID resourceId, UUID groupRef,
                           Visibility visibility, long version) {}
    public record WorkPlanRequest(@NotNull LocalDate planDate, @NotNull PlanMode mode,
                                  UUID siteId, UUID floorId, UUID resourceId, UUID groupRef,
                                  @NotNull Visibility visibility, @Min(0) Long version) {}
    public record SharedWorkPlan(UUID planId, long userId, String displayName,
                                 LocalDate planDate, PlanMode mode, UUID siteId,
                                 UUID floorId, UUID resourceId, Visibility visibility,
                                 String source) {}
    public record SharingPreference(boolean optIn, Visibility visibility, long version) {}
    public record SharingPreferenceRequest(boolean optIn, @NotNull Visibility visibility,
                                           @NotNull @Min(0) Long version) {}
    public record SharingPolicy(boolean sharingEnabled, Visibility maximumVisibility,
                                long version) {}
    public record ShareableGroup(UUID groupRef, String displayName) {}
    public record SharingPolicyRequest(boolean sharingEnabled,
                                       @NotNull Visibility maximumVisibility,
                                       @NotNull @Min(0) Long version,
                                       @NotBlank @Size(max = 500) String reason,
                                       boolean confirmed) {}
    public record ConnectorStatus(ConnectorKind kind, String provider, ConnectionState status,
                                  String configurationReference, OffsetDateTime lastVerifiedAt,
                                  long version) {}
    public record ConnectorRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,80}") String provider,
            boolean enabled,
            @Pattern(regexp = "[A-Za-z0-9._:/-]{1,160}") String configurationReference,
            @NotNull @Min(0) Long version,
            @NotBlank @Size(max = 500) String reason, boolean confirmed) {}
    public record CollaborationOverview(List<WorkPlan> ownPlans,
                                         List<SharedWorkPlan> sharedPlans,
                                         SharingPreference preference, SharingPolicy policy,
                                         List<ShareableGroup> shareableGroups,
                                         ConnectorStatus actualPresence,
                                         OffsetDateTime generatedAt) {}
    public record PrivacySummary(long bookingRetentionDays, long legalHoldCount,
                                 long anonymizedBookingCount, long expiredEligibleBookingCount,
                                 long facilityRequestEligibleRetentionCount, long facilityClosureEligibleRetentionCount,
                                 long facilityRequestsPurgedCount, long facilityClosuresPurgedCount) {}
    public record GovernanceOverview(SharingPolicy policy, List<ConnectorStatus> connectors,
                                     PrivacySummary privacy, OffsetDateTime generatedAt) {}
    public record AccessRuleChangeRequest(
            @NotNull @Valid WorkplaceSpatialGovernanceDtos.SiteAccessRuleRequest proposed,
            @Size(max = 500) String reason, boolean confirmed) {}
    public record DelegationChangeRequest(
            @NotNull @Valid WorkplaceSpatialGovernanceDtos.DelegatedAdminScopeRequest proposed,
            @Size(max = 500) String reason, boolean confirmed) {}
    public record BookingPolicyChangeRequest(@Valid @NotNull WorkplaceDtos.PolicyRequest proposed,
            @Size(max = 500) String reason, boolean confirmed) {}
    public record PolicyOverrideChangeRequest(@Valid @NotNull WorkplaceSpatialGovernanceDtos.PolicyOverrideRequest proposed,
            @Size(max = 500) String reason, boolean confirmed) {}

    public record GovernanceChangeReview(String targetType, UUID targetId, JsonNode current,
                                         JsonNode proposed,
                                         WorkplaceSpatialGovernanceDtos.SiteAccessDecision currentActorAccess,
                                         List<String> knownImpact, List<String> warnings,
                                         OffsetDateTime evaluatedAt,
                                         WorkplaceSpatialGovernanceDtos.SiteAccessDecision proposedActorAccess) {
        public GovernanceChangeReview(String targetType, UUID targetId, JsonNode current, JsonNode proposed,
                WorkplaceSpatialGovernanceDtos.SiteAccessDecision currentActorAccess, List<String> knownImpact,
                List<String> warnings, OffsetDateTime evaluatedAt) {
            this(targetType, targetId, current, proposed, currentActorAccess, knownImpact, warnings, evaluatedAt, null);
        }
    }
    public record ResourcePhoto(UUID resourceId, String url, String altText,
                                String contentType, long sizeBytes, String sha256, long version) {}
    public record MutationResult(boolean removed) {}
}
