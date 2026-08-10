package com.dwp.services.provider;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ProviderDtos {

    private ProviderDtos() {
    }

    public record TenantSummary(
            UUID tenantId,
            String tenantKey,
            String displayName,
            String serviceTier,
            String dataRegion,
            String isolationModel,
            String lifecycleState,
            String onboardingState,
            Long authTenantId,
            long version,
            List<EntitlementSummary> entitlements) {
    }

    public record EntitlementSummary(
            Long entitlementId,
            String entitlementKey,
            String name,
            String entitlementType,
            String lifecycleState,
            String configuration,
            long version) {
    }

    public record OperationStep(
            int order,
            String stepKey,
            String lifecycleState,
            String targetService,
            String externalReference,
            String redactedResult,
            Instant startedAt,
            Instant completedAt) {
    }

    public record OperationSummary(
            UUID operationId,
            UUID tenantId,
            String operationType,
            String lifecycleState,
            String riskTier,
            String planHash,
            String plan,
            String failureCode,
            String failureMessage,
            Instant startedAt,
            Instant completedAt,
            long version,
            List<OperationStep> steps) {
    }

    public record PageResult<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    public record OnboardingPlanRequest(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,79}") String tenantKey,
            @NotBlank @Size(max = 240) String displayName,
            @NotBlank @Pattern(regexp = "STANDARD|ENTERPRISE|REGULATED") String serviceTier,
            @NotBlank @Pattern(regexp = "[a-z0-9-]{2,40}") String dataRegion,
            @NotBlank @Pattern(regexp = "POOL|BRIDGE|SILO") String isolationModel,
            @NotNull @Size(max = 100) List<@Pattern(regexp = "[a-z][a-z0-9.-]{1,119}") String> entitlementKeys,
            @NotBlank @Size(max = 1000) String justification) {
    }

    public record ExecuteOperationRequest(
            @NotBlank @Size(min = 64, max = 64) String planHash,
            @NotNull @Min(0) Long version) {
    }

    public record LifecycleRequest(
            @NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED") String state,
            @NotBlank @Size(max = 1000) String justification,
            @NotNull @Min(0) Long version) {
    }

    public record ReplaceEntitlementsRequest(
            @NotNull @Size(max = 100) List<@Pattern(regexp = "[a-z][a-z0-9.-]{1,119}") String> entitlementKeys,
            @NotBlank @Size(max = 1000) String justification,
            @NotNull @Min(0) Long version) {
    }
}
