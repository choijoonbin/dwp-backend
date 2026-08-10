package com.dwp.services.auth.provisioning;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuthTenantProvisioningDtos {

    private AuthTenantProvisioningDtos() {
    }

    public record ProvisionTenantRequest(
            @NotNull UUID providerTenantId,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,79}") String tenantKey,
            @NotBlank @Size(max = 240) String displayName,
            @NotBlank @Pattern(regexp = "[a-z0-9-]{2,40}") String dataRegion,
            @NotBlank @Pattern(regexp = "POOL|BRIDGE|SILO") String isolationModel,
            @NotBlank @Pattern(regexp = "[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*") String defaultLocale,
            @NotBlank @Size(max = 80) String timeZone,
            @NotBlank @Size(max = 200) String administratorDisplayName,
            @NotBlank @Email @Size(max = 255) String administratorEmail,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._@+-]{3,255}") String administratorPrincipal,
            @NotNull @Size(min = 1, max = 100) List<@NotBlank String> entitlementKeys) {
    }

    public record ProvisionTenantResponse(
            UUID providerTenantId,
            Long tenantId,
            Long administratorUserId,
            String administratorPrincipal,
            String lifecycleState,
            int schemaVersion) {
    }

    public record UpdateLifecycleRequest(
            @NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED|RETIRED") String lifecycleState) {
    }

    public record ReplaceEntitlementsRequest(
            @NotNull @Size(min = 1, max = 100) List<@NotBlank String> entitlementKeys) {
    }

    public record IssueInvitationRequest(
            @NotNull Long administratorUserId,
            @NotNull @Min(15) @Max(10080) Integer expiresInMinutes) {
    }

    public record InvitationResponse(
            Long tenantId,
            Long administratorUserId,
            String principal,
            String activationToken,
            Instant expiresAt) {
    }

    public record ActivationSummary(
            Long tenantId,
            String tenantKey,
            String tenantName,
            Long userId,
            String displayName,
            String email,
            String principal,
            Instant expiresAt) {
    }

    public record ActivateAccountRequest(
            @NotBlank @Size(min = 12, max = 128) String password) {
    }

    public record ActivateAccountResponse(
            Long tenantId,
            String tenantKey,
            String principal,
            String lifecycleState) {
    }
}
