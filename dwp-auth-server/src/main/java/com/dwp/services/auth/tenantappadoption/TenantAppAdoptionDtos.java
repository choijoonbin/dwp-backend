package com.dwp.services.auth.tenantappadoption;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TenantAppAdoptionDtos {

    private TenantAppAdoptionDtos() {
    }

    public record CreateInstallationRequest(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,79}") String productKey,
            @NotBlank @Pattern(regexp = "APP\\.[A-Z][A-Z0-9_.-]{1,250}") String appResourceKey,
            @NotBlank @Pattern(regexp = "INTERNAL_AUTH_CONTROLLED|EXTERNAL_SERVICE")
                    String installationKind,
            @Positive Integer seatCapacity,
            @NotBlank @Size(min = 10, max = 1000) String justification) {
    }

    public record VersionedCommand(@NotNull @PositiveOrZero Long version) {
    }

    public record DecisionCommand(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    @Schema(name = "TenantAppAdoptionActivationCommand")
    public record ActivationCommand(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record CreateAssignmentRequest(
            @NotNull UUID installationId,
            @NotNull @Min(1) Long userId,
            @Future Instant validTo,
            @NotBlank @Size(min = 10, max = 1000) String justification) {
    }

    public record RevokeCommand(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record AdoptionProjection(
            Instant observedAt,
            String coverageState,
            List<String> includedOwners,
            List<String> exclusions,
            List<String> requestableAppResourceKeys,
            List<Installation> installations,
            int installationsLimit,
            boolean installationsHasMore) {

        public AdoptionProjection {
            includedOwners = List.copyOf(includedOwners);
            exclusions = List.copyOf(exclusions);
            requestableAppResourceKeys = List.copyOf(requestableAppResourceKeys);
            installations = List.copyOf(installations);
        }
    }

    public record AssignmentPage(
            List<Assignment> items,
            int limit,
            boolean hasMore) {

        public AssignmentPage {
            items = List.copyOf(items);
        }
    }

    public record Installation(
            UUID installationId,
            String productKey,
            String appResourceKey,
            String installationKind,
            String lifecycleState,
            String externalExecutorState,
            Integer seatCapacity,
            long reservedSeats,
            long activeSeats,
            String justification,
            Long requestedBy,
            Instant submittedAt,
            Long approvedBy,
            Instant approvedAt,
            String decisionReason,
            Long activatedBy,
            Instant activatedAt,
            UUID activationReceiptId,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<String> allowedActions) {

        public Installation {
            allowedActions = List.copyOf(allowedActions);
        }
    }

    @Schema(name = "TenantAppAdoptionAssignment")
    public record Assignment(
            UUID assignmentId,
            UUID installationId,
            String productKey,
            Long userId,
            String userDisplayName,
            String lifecycleState,
            int seatQuantity,
            String sourceType,
            String externalSettlementState,
            Instant validFrom,
            Instant validTo,
            String justification,
            Long requestedBy,
            Long approvedBy,
            Instant approvedAt,
            String decisionReason,
            Long activatedBy,
            Instant activatedAt,
            UUID activationReceiptId,
            Long revokedBy,
            Instant revokedAt,
            String revocationReason,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<String> allowedActions) {

        public Assignment {
            allowedActions = List.copyOf(allowedActions);
        }
    }
}
