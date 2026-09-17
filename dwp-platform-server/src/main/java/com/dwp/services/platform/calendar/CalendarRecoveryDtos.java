package com.dwp.services.platform.calendar;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Wire contracts dedicated to restoring trashed events and their resource bookings. */
public final class CalendarRecoveryDtos {

    private CalendarRecoveryDtos() {
    }

    public enum RestoreOutcome {
        EVENT_AND_RESOURCES_RESTORED,
        EVENT_AND_RESOURCES_PARTIALLY_RESTORED,
        EVENT_AND_RESOURCE_REBOOK_REQUESTED,
        EVENT_ONLY_RESOURCE_REBOOK_REQUIRED,
        EVENT_ONLY_RESOURCE_CONFLICT,
        EVENT_ONLY_RESOURCE_ACCESS_REVOKED,
        EVENT_ONLY_RESOURCE_UNAVAILABLE,
        EVENT_ONLY_NO_PRIOR_RESOURCE
    }

    public enum RestoreReason {
        RESOURCE_REBOOKED,
        RESOURCE_APPROVAL_REQUIRED,
        ADDITIONAL_RESOURCES_REQUIRE_REBOOK,
        EXPLICIT_REBOOK_REQUIRED,
        RESOURCE_TIME_CONFLICT,
        RESOURCE_ACCESS_REVOKED,
        RESOURCE_NOT_AVAILABLE,
        RESOURCE_POLICY_BLOCKED,
        EVENT_TIME_IS_PAST,
        NO_PRIOR_RESOURCE
    }

    public record RestoreResourceBookingRequest(
            @NotNull @Min(0) Long eventVersion,
            @NotNull UUID resourceId,
            @NotNull @Min(0) Long bookingVersion,
            @NotNull UUID idempotencyKey) {
    }

    public record RestoreResourceResult(
            UUID resourceId,
            long bookingVersion,
            RestoreOutcome outcome,
            RestoreReason reason,
            boolean canRebook) {
    }

    /**
     * Keeps the former capability fields at the response root for wire compatibility while
     * making the event/resource restoration result explicit.
     */
    public record RestoreEventResponse(
            boolean canViewDetails,
            boolean canEdit,
            boolean canDelete,
            boolean canRestore,
            boolean canRespond,
            boolean canStar,
            RestoreOutcome outcome,
            RestoreReason reason,
            long eventVersion,
            List<RestoreResourceResult> resources) {

        public RestoreEventResponse(
                CalendarDtos.EventCapabilities capabilities,
                RestoreOutcome outcome,
                RestoreReason reason,
                long eventVersion,
                List<RestoreResourceResult> resources) {
            this(
                    capabilities.canViewDetails(),
                    capabilities.canEdit(),
                    capabilities.canDelete(),
                    capabilities.canRestore(),
                    capabilities.canRespond(),
                    capabilities.canStar(),
                    outcome,
                    reason,
                    eventVersion,
                    List.copyOf(resources));
        }
    }
}
