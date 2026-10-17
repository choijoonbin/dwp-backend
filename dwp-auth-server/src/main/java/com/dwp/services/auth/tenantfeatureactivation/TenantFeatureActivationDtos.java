package com.dwp.services.auth.tenantfeatureactivation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class TenantFeatureActivationDtos {

    private TenantFeatureActivationDtos() {
    }

    public record GlobalChange(
            UUID changeId,
            String featureKey,
            long policyVersion,
            long targetBundleVersion,
            String lifecycleState,
            String requestedBy,
            String approvedBy,
            String activatedBy,
            UUID activationReceiptId,
            long version,
            Instant updatedAt) {
    }

    public record TenantEnrollment(
            UUID changeId,
            Long tenantId,
            String featureKey,
            long policyVersion,
            String lifecycleState,
            Long requestedBy,
            Long approvedBy,
            Long activatedBy,
            UUID activationReceiptId,
            long version,
            Instant updatedAt) {
    }

    public record GlobalRequest(
            @Positive long targetBundleVersion,
            @NotBlank @Size(min = 10, max = 1000) String justification,
            @NotBlank @Size(max = 160) String requesterRef,
            @NotBlank @Size(max = 160) String correlationId) {
    }

    public record TenantRequest(
            @NotBlank @Size(min = 10, max = 1000) String justification,
            @NotNull @Positive Long requesterId,
            @NotBlank @Size(max = 160) String correlationId) {
    }

    public record GlobalVersionedCommand(
            @PositiveOrZero long expectedVersion,
            @NotBlank @Size(max = 160) String actorRef,
            @NotBlank @Size(max = 160) String correlationId) {
    }

    public record TenantVersionedCommand(
            @PositiveOrZero long expectedVersion,
            @NotNull @Positive Long actorId,
            @NotBlank @Size(max = 160) String correlationId) {
    }

    public record GlobalActivationCommand(
            @PositiveOrZero long expectedVersion,
            @NotBlank @Size(max = 160) String actorRef,
            @NotNull UUID receiptId,
            @NotBlank @Size(max = 160) String correlationId) {
    }

    public record TenantActivationCommand(
            @PositiveOrZero long expectedVersion,
            @NotNull @Positive Long actorId,
            @NotNull UUID receiptId,
            @NotBlank @Size(max = 160) String correlationId) {
    }

    public record GlobalRevocationCommand(
            @PositiveOrZero long expectedVersion,
            @NotBlank @Size(max = 160) String actorRef,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotBlank @Size(max = 160) String correlationId) {
    }

    public record TenantRevocationCommand(
            @PositiveOrZero long expectedVersion,
            @NotNull @Positive Long actorId,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotBlank @Size(max = 160) String correlationId) {
    }
}
