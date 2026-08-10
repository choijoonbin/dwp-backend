package com.dwp.services.provider;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ProviderDtos {

    private ProviderDtos() {
    }

    public record Metric(String key, long count) {
    }

    public record EstateOverview(
            long organizations,
            long tenants,
            long activeTenants,
            long provisioningTenants,
            long suspendedTenants,
            long failedTenants,
            long openOperations,
            long activeSupportSessions,
            List<Metric> regions,
            List<Metric> serviceTiers) {
    }

    public record OperatorProfile(
            Long operatorId,
            Long authUserId,
            String displayName,
            Set<String> roles,
            Set<String> permissions) {
    }

    public record OrganizationSummary(
            UUID organizationId,
            String organizationKey,
            String displayName,
            String legalName,
            String customerReference,
            String lifecycleState,
            int schemaVersion,
            String attributes,
            long version) {
    }

    public record TenantSummary(
            UUID tenantId,
            UUID organizationId,
            String organizationKey,
            String organizationName,
            String tenantKey,
            String displayName,
            String environmentKey,
            String serviceTier,
            String dataRegion,
            String isolationModel,
            String defaultLocale,
            String timeZone,
            String lifecycleState,
            String onboardingState,
            Long authTenantId,
            int schemaVersion,
            String configuration,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<EntitlementSummary> entitlements,
            List<ServiceInstanceSummary> services,
            List<TenantDomainSummary> domains,
            List<TenantAdministratorSummary> administrators) {
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

    public record ServiceInstanceSummary(
            UUID serviceInstanceId,
            String serviceKey,
            String serviceName,
            String deploymentCell,
            String dataRegion,
            String lifecycleState,
            String externalResourceId,
            Integer appliedSchemaVersion,
            String healthSnapshot,
            Instant lastReconciledAt,
            long version) {
    }

    public record TenantDomainSummary(
            UUID domainId,
            String domainName,
            String domainType,
            String verificationMethod,
            String verificationState,
            boolean primaryDomain,
            Instant verifiedAt,
            Instant lastCheckedAt,
            long version) {
    }

    public record TenantAdministratorSummary(
            UUID tenantAdministratorId,
            Long authUserId,
            String principal,
            String email,
            String displayName,
            String roleCode,
            String lifecycleState,
            boolean primaryAdministrator,
            Instant lastInvitedAt,
            Instant activatedAt,
            long version) {
    }

    public record RegionSummary(
            String regionKey,
            String displayName,
            String jurisdictionCode,
            String residencyClass,
            String lifecycleState) {
    }

    public record OperationStep(
            Long stepId,
            int order,
            String stepKey,
            String lifecycleState,
            String targetService,
            String externalReference,
            String redactedResult,
            int attemptCount,
            String lastErrorCode,
            String lastErrorMessage,
            Instant nextRetryAt,
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
            Instant createdAt,
            long version,
            List<OperationStep> steps) {
    }

    public record SupportSessionSummary(
            UUID supportSessionId,
            UUID tenantId,
            String tenantKey,
            String tenantName,
            Long operatorId,
            String operatorName,
            String lifecycleState,
            String justification,
            List<String> scopes,
            Instant startedAt,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant revokedAt,
            long version) {
    }

    public record SupportSessionGrant(
            SupportSessionSummary session,
            String sessionToken) {
    }

    public record AuditEventSummary(
            UUID auditEventId,
            Long operatorId,
            String operatorName,
            UUID tenantId,
            String tenantKey,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String correlationId,
            String redactedSnapshot,
            Instant occurredAt) {
    }

    public record AdministratorInvitation(
            UUID tenantAdministratorId,
            Long authTenantId,
            Long authUserId,
            String principal,
            String activationToken,
            String activationPath,
            Instant expiresAt) {
    }

    public record DomainChallenge(
            TenantDomainSummary domain,
            String recordName,
            String recordType,
            String recordValue) {
    }

    public record PageResult<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    public record OnboardingPlanRequest(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,79}") String organizationKey,
            @NotBlank @Size(max = 240) String organizationName,
            @Size(max = 320) String legalName,
            @Size(max = 120) String customerReference,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,79}") String tenantKey,
            @NotBlank @Size(max = 240) String displayName,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,31}") String environmentKey,
            @NotBlank @Pattern(regexp = "STANDARD|ENTERPRISE|REGULATED") String serviceTier,
            @NotBlank @Pattern(regexp = "[a-z0-9-]{2,40}") String dataRegion,
            @NotBlank @Pattern(regexp = "POOL|BRIDGE|SILO") String isolationModel,
            @NotBlank @Pattern(regexp = "[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*") String defaultLocale,
            @NotBlank @Size(max = 80) String timeZone,
            @Pattern(regexp = "(?i)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}") String primaryDomain,
            @NotBlank @Size(max = 200) String initialAdminDisplayName,
            @NotBlank @Email @Size(max = 255) String initialAdminEmail,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._@+-]{3,255}") String initialAdminPrincipal,
            @NotNull @Size(min = 1, max = 100)
            List<@Pattern(regexp = "[a-z][a-z0-9.-]{1,119}") String> entitlementKeys,
            @NotBlank @Size(max = 1000) String justification) {
    }

    public record ExecuteOperationRequest(
            @NotBlank @Size(min = 64, max = 64) String planHash,
            @NotNull @Min(0) Long version) {
    }

    public record RetryOperationRequest(
            @NotBlank @Size(max = 1000) String justification,
            @NotNull @Min(0) Long version) {
    }

    public record LifecycleRequest(
            @NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED") String state,
            @NotBlank @Size(max = 1000) String justification,
            @NotNull @Min(0) Long version) {
    }

    public record ReplaceEntitlementsRequest(
            @NotNull @Size(min = 1, max = 100)
            List<@Pattern(regexp = "[a-z][a-z0-9.-]{1,119}") String> entitlementKeys,
            @NotBlank @Size(max = 1000) String justification,
            @NotNull @Min(0) Long version) {
    }

    public record CreateDomainRequest(
            @NotBlank
            @Pattern(regexp = "(?i)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")
            String domainName,
            @NotBlank @Pattern(regexp = "LOGIN|EMAIL|CUSTOM") String domainType,
            boolean primaryDomain) {
    }

    public record VerifyDomainRequest(
            @NotBlank @Size(max = 1000) String justification,
            @NotNull @Min(0) Long version) {
    }

    public record CreateSupportSessionRequest(
            @NotNull UUID tenantId,
            @NotNull @Size(min = 1, max = 3)
            List<@Pattern(regexp = "TENANT_CONFIGURATION_READ|TENANT_CONFIGURATION_WRITE|WORKFORCE_READ") String> scopes,
            @NotNull @Min(5) @Max(60) Integer durationMinutes,
            @NotBlank @Size(max = 1000) String justification) {
    }

    public record RevokeSupportSessionRequest(
            @NotBlank @Size(max = 1000) String justification,
            @NotNull @Min(0) Long version) {
    }

    public record IssueAdministratorInvitationRequest(
            @NotNull @Min(15) @Max(10080) Integer expiresInMinutes,
            @NotBlank @Size(max = 1000) String justification) {
    }
}
