package com.dwp.services.auth.tenantsettings;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TenantSettingsDtos {

    private TenantSettingsDtos() {
    }

    public record AuthPolicyDraft(
            @NotBlank @Pattern(regexp = "LOCAL|SSO") String defaultLoginType,
            @NotEmpty @Size(max = 2) List<@Pattern(regexp = "LOCAL|SSO") String> allowedLoginTypes,
            @NotNull Boolean localLoginEnabled,
            @NotNull Boolean ssoLoginEnabled,
            @Size(max = 100) String ssoProviderKey,
            @NotNull Boolean requireMfa,
            @PositiveOrZero Integer tokenTtlSec) {
    }

    public record CreateAuthPolicyChangeRequest(
            @NotNull @Valid AuthPolicyDraft policy,
            @NotBlank @Size(min = 10, max = 1000) String justification) {
    }

    public record VersionedCommand(@NotNull @PositiveOrZero Long version) {
    }

    public record DecisionCommand(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record Impact(
            String confidence,
            Long populationCount,
            String coverage,
            Instant observedAt,
            List<String> exclusions) {

        public Impact {
            exclusions = List.copyOf(exclusions);
        }
    }

    public record ChangeSet(
            UUID changeSetId,
            String ownerType,
            String ownerRef,
            String lifecycleState,
            JsonNode beforeState,
            JsonNode proposedState,
            String beforeHash,
            String proposedHash,
            Impact impact,
            String justification,
            Long requestedBy,
            Instant submittedAt,
            Long decidedBy,
            Instant decidedAt,
            String decisionReason,
            Long publishedBy,
            Instant publishedAt,
            UUID publishReceiptId,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<String> allowedActions) {

        public ChangeSet {
            beforeState = beforeState.deepCopy();
            proposedState = proposedState.deepCopy();
            allowedActions = List.copyOf(allowedActions);
        }
    }

    public record AccessProjection(
            String snapshotId,
            Instant observedAt,
            ProjectionCoverage coverage,
            List<PrincipalAccess> principals,
            int page,
            int size,
            long totalElements,
            int totalPages) {

        public AccessProjection {
            principals = List.copyOf(principals);
        }
    }

    public record ProjectionCoverage(
            String state,
            List<String> includedOwners,
            List<String> exclusions,
            Instant freshestSourceUpdatedAt,
            List<OwnerCoverage> owners) {

        public ProjectionCoverage {
            includedOwners = List.copyOf(includedOwners);
            exclusions = List.copyOf(exclusions);
            owners = List.copyOf(owners);
        }
    }

    public record OwnerCoverage(
            String ownerKey,
            String state,
            String freshnessState,
            Instant observedAt,
            Instant sourceUpdatedAt,
            List<String> allowedActions,
            List<String> exclusions) {

        public OwnerCoverage {
            allowedActions = List.copyOf(allowedActions);
            exclusions = List.copyOf(exclusions);
        }
    }

    public record PrincipalAccess(
            Long userId,
            String displayName,
            String email,
            String status,
            boolean mfaEnabled,
            List<AccessGrant> grants,
            int pendingApprovalCount,
            Instant sourceUpdatedAt) {

        public PrincipalAccess {
            grants = List.copyOf(grants);
        }
    }

    public record AccessGrant(
            String entitlementType,
            String entitlementKey,
            String displayName,
            String sourceType,
            String sourceId,
            String sourceName,
            String scopeType,
            String scopeRef,
            String lifecycleState,
            Instant validFrom,
            Instant validTo,
            boolean privileged,
            Long requestedBy,
            Long approvedBy,
            Instant approvedAt,
            Long activatedBy,
            Instant activatedAt,
            String approvalLineageState) {
    }

    public record TenantGovernanceSnapshot(
            Instant observedAt,
            TenantDirectoryProjection tenantDirectory,
            OwnerObservation providerDomain,
            LoginVerification loginVerification,
            RecoveryCoverage recoveryVerification,
            List<OwnerObservation> policyOwners,
            List<EffectiveSetting> effectiveSettings) {

        public TenantGovernanceSnapshot {
            policyOwners = List.copyOf(policyOwners);
            effectiveSettings = List.copyOf(effectiveSettings);
        }
    }

    public record TenantDirectoryProjection(
            String state,
            Long tenantId,
            String tenantCode,
            String tenantName,
            String defaultLocale,
            Instant sourceUpdatedAt) {
    }

    public record OwnerObservation(
            String ownerKey,
            String state,
            Instant observedAt,
            List<String> exclusions) {

        public OwnerObservation {
            exclusions = List.copyOf(exclusions);
        }
    }

    public record LoginVerification(
            String internalPrerequisiteState,
            String configuredProviderKey,
            String externalProbeState,
            Instant lastExternalProbeAt,
            SsoTestLoginReceipt latestReceipt,
            List<String> blockingReasons) {

        public LoginVerification {
            blockingReasons = List.copyOf(blockingReasons);
        }
    }

    public record SsoTestLoginCommand(
            @NotNull UUID idempotencyKey,
            @NotBlank @Size(min = 10, max = 1000) String justification) {
    }

    public record SsoTestLoginReceipt(
            UUID testLoginJobId,
            String providerKey,
            String lifecycleState,
            String internalPrerequisiteState,
            String externalProbeState,
            List<String> blockingReasons,
            String executionBoundary,
            Long requestedBy,
            Instant requestedAt,
            Instant completedAt,
            String receiptSha256) {

        public SsoTestLoginReceipt {
            blockingReasons = List.copyOf(blockingReasons);
        }
    }

    public record SsoTestLoginReceiptPage(
            List<SsoTestLoginReceipt> items,
            int limit,
            boolean hasMore) {

        public SsoTestLoginReceiptPage {
            items = List.copyOf(items);
        }
    }

    public record RecoveryCoverage(
            String state,
            long total,
            long verified,
            long overdue,
            long notVerified,
            Instant freshestVerificationAt,
            List<String> exclusions) {

        public RecoveryCoverage {
            exclusions = List.copyOf(exclusions);
        }
    }

    public record EffectiveSetting(
            String settingKey,
            JsonNode effectiveValue,
            String resolutionStrategy,
            String effectiveSource,
            boolean locked,
            boolean overrideAllowed,
            String overrideState,
            List<SettingSource> sources,
            Instant evaluatedAt,
            String evidenceState) {

        public EffectiveSetting {
            effectiveValue = effectiveValue.deepCopy();
            sources = List.copyOf(sources);
        }
    }

    public record SettingSource(
            String level,
            String ownerKey,
            JsonNode value,
            String evaluation,
            String reason) {

        public SettingSource {
            value = value == null ? null : value.deepCopy();
        }
    }

    public record RestorePreferenceCommand(@NotNull @PositiveOrZero Long version) {
    }

    public record UserPreferenceState(
            Long userId,
            String preferredLocale,
            String tenantDefaultLocale,
            long version,
            Instant updatedAt) {
    }

    public record RecoveryVerificationCommand(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Pattern(regexp = "OPERATOR_ATTESTED|RECOVERY_DRILL_COMPLETED")
                    String method,
            @NotBlank @Size(min = 10, max = 500) String evidenceReference,
            @NotNull @Future Instant nextVerificationDueAt) {
    }

    public record RecoveryAccount(
            UUID emergencyAccessPrincipalId,
            Long userId,
            String displayName,
            String justification,
            Instant reviewDueAt,
            String lifecycleState,
            String verificationStatus,
            String verificationMethod,
            String verificationReference,
            Instant lastVerifiedAt,
            Long lastVerifiedBy,
            Instant verificationDueAt,
            long version) {
    }
}
