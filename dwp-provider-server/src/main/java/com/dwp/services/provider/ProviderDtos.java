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

    public record ActionItem(
            String itemId,
            String category,
            String severity,
            String title,
            String detail,
            UUID tenantId,
            String targetId,
            Instant createdAt,
            String route) {
    }

    public record RecentActivity(
            UUID auditEventId,
            String action,
            String category,
            String outcome,
            String operatorName,
            String tenantKey,
            String targetType,
            String targetId,
            Instant occurredAt) {
    }

    public record ServicePosture(
            String serviceKey,
            String displayName,
            String criticality,
            long totalInstances,
            long healthyInstances,
            long pendingInstances,
            long degradedInstances,
            long failedInstances,
            long impactedTenants,
            Instant lastReconciledAt) {
    }

    public record CellPosture(
            UUID deploymentCellId,
            String cellKey,
            String displayName,
            String regionKey,
            String lifecycleState,
            int placementCapacity,
            long tenantCount,
            long serviceInstances,
            long healthyInstances,
            double saturationPct,
            String healthState) {
    }

    public record CommandCenter(
            Instant generatedAt,
            String operatingState,
            EstateOverview estate,
            long activeIncidents,
            long expiringSubscriptions,
            List<ActionItem> actionQueue,
            List<ServicePosture> services,
            List<CellPosture> cells,
            List<RecentActivity> recentActivity) {
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

    public record SubscriptionSummary(
            UUID subscriptionId,
            String planKey,
            int planVersion,
            String planName,
            String lifecycleState,
            Instant startsAt,
            Instant endsAt,
            String contractReference,
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
            SubscriptionSummary subscription,
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

    public record OperationApprovalSummary(
            UUID operationApprovalId,
            UUID operationId,
            UUID tenantId,
            String tenantName,
            String operationType,
            String riskTier,
            String gateKey,
            int gateOrder,
            String lifecycleState,
            String requiredRoleCode,
            boolean separationOfDuties,
            Long requestedBy,
            String requestedByName,
            Long decidedBy,
            String decidedByName,
            String requestReason,
            String decisionReason,
            Instant requestedAt,
            Instant decidedAt,
            Instant expiresAt,
            long version) {
    }

    public record IncidentUpdateSummary(
            UUID incidentUpdateId,
            String lifecycleState,
            String message,
            String visibility,
            String operatorName,
            Instant createdAt) {
    }

    public record ServiceIncidentSummary(
            UUID incidentId,
            String incidentKey,
            String title,
            String severity,
            String lifecycleState,
            String impactScope,
            String serviceKey,
            String regionKey,
            UUID deploymentCellId,
            UUID tenantId,
            String tenantName,
            String customerImpact,
            String publicSummary,
            String ownerName,
            Instant detectedAt,
            Instant startedAt,
            Instant resolvedAt,
            long version,
            List<IncidentUpdateSummary> updates) {
    }

    public record ServiceHealthOverview(
            Instant generatedAt,
            String operatingState,
            long totalInstances,
            long healthyInstances,
            long pendingInstances,
            long degradedInstances,
            long failedInstances,
            long impactedTenants,
            List<ServicePosture> services,
            List<CellPosture> cells,
            List<ServiceIncidentSummary> incidents) {
    }

    public record ServicePlanPortfolio(
            String planKey,
            int planVersion,
            String planName,
            String serviceTier,
            String lifecycleState,
            long organizations,
            long tenants) {
    }

    public record SubscriptionPortfolio(
            UUID subscriptionId,
            UUID organizationId,
            String organizationKey,
            String organizationName,
            String planKey,
            String planName,
            String serviceTier,
            String lifecycleState,
            Instant startsAt,
            Instant endsAt,
            String contractReference,
            long tenants,
            long activeEntitlements) {
    }

    public record EntitlementAdoption(
            Long entitlementId,
            String entitlementKey,
            String name,
            String entitlementType,
            long assignedTenants,
            long eligibleTenants) {
    }

    public record CommercialOverview(
            Instant generatedAt,
            long activeSubscriptions,
            long trialSubscriptions,
            long expiringSubscriptions,
            long uncontractedOrganizations,
            List<ServicePlanPortfolio> plans,
            List<SubscriptionPortfolio> subscriptions,
            List<EntitlementAdoption> entitlements) {
    }

    public record AuditInsights(
            Instant generatedAt,
            long events24Hours,
            long failed24Hours,
            long denied24Hours,
            long privilegedAccess24Hours,
            List<Metric> outcomes,
            List<Metric> categories) {
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
            String accessMode,
            String approvalReference,
            boolean customerApprovalRequired,
            String riskTier,
            Instant startedAt,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant revokedAt,
            long version) {
    }

    public record SupportScopeSummary(
            String scopeCode,
            String displayName,
            String riskTier,
            boolean requiresCustomerApproval,
            String lifecycleState) {
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
            String eventCategory,
            String outcome,
            String correlationId,
            String redactedSnapshot,
            Instant occurredAt) {
    }

    public record AdministratorInvitation(
            UUID tenantAdministratorId,
            Long authTenantId,
            Long authUserId,
            String email,
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

    public record DecideOperationApprovalRequest(
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @NotBlank @Size(max = 1000) String reason,
            @NotNull @Min(0) Long version) {
    }

    public record CreateIncidentRequest(
            @NotBlank @Size(max = 240) String title,
            @NotBlank @Pattern(regexp = "SEV1|SEV2|SEV3|SEV4") String severity,
            @NotBlank @Pattern(regexp = "GLOBAL|REGION|CELL|SERVICE|TENANT") String impactScope,
            @Size(max = 80) String serviceKey,
            @Size(max = 40) String regionKey,
            UUID deploymentCellId,
            UUID tenantId,
            @NotBlank @Size(max = 2000) String customerImpact,
            @Size(max = 2000) String publicSummary,
            @NotBlank @Size(max = 2000) String initialUpdate) {
    }

    public record UpdateIncidentRequest(
            @NotBlank @Pattern(regexp = "IDENTIFIED|MONITORING|RESOLVED|CLOSED") String state,
            @NotBlank @Size(max = 2000) String message,
            @NotBlank @Pattern(regexp = "INTERNAL|CUSTOMER") String visibility,
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
            @NotNull @Size(min = 1, max = 20)
            List<@Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String> scopes,
            @NotNull @Min(5) @Max(60) Integer durationMinutes,
            @NotBlank @Size(max = 1000) String justification,
            @Size(max = 160) String approvalReference,
            boolean emergencyAccess) {
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
