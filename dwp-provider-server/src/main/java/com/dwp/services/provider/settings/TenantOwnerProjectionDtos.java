package com.dwp.services.provider.settings;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TenantOwnerProjectionDtos {

    private TenantOwnerProjectionDtos() {
    }

    public record DomainProjection(
            String ownerService,
            String observationState,
            Instant observedAt,
            Instant sourceLastChangedAt,
            String coverageState,
            List<String> exclusions,
            List<DomainObservation> domains) {

        public DomainProjection {
            exclusions = List.copyOf(exclusions);
            domains = List.copyOf(domains);
        }
    }

    public record DomainObservation(
            UUID domainId,
            String domainName,
            String domainType,
            String verificationMethod,
            String verificationState,
            boolean primaryDomain,
            Instant verifiedAt,
            Instant lastCheckedAt,
            Instant sourceChangedAt,
            String evidenceFreshnessState,
            long version) {
    }

    public record DataGovernanceProjection(
            String ownerService,
            String observationState,
            Instant observedAt,
            Instant sourceLastChangedAt,
            String coverageState,
            List<String> exclusions,
            List<PolicyObservation> policies,
            List<TenantLifecycleHoldObservation> tenantLifecycleHoldObservations) {

        public DataGovernanceProjection {
            exclusions = List.copyOf(exclusions);
            policies = List.copyOf(policies);
            tenantLifecycleHoldObservations = List.copyOf(tenantLifecycleHoldObservations);
        }
    }

    public record PolicyObservation(
            String policyType,
            String ownerService,
            String coverage,
            int revisionNumber,
            String effectiveState,
            Integer retentionDays,
            Boolean legalHoldActive,
            Instant effectiveFrom,
            Instant effectiveTo,
            Instant publishedAt,
            Instant sourceChangedAt,
            String freshnessState,
            String evidenceState,
            String impactFingerprint,
            long sourceVersion) {
    }

    public record TenantLifecycleHoldObservation(
            UUID lifecycleRequestId,
            String requestedAction,
            String lifecycleState,
            String holdEvaluationState,
            String executionState,
            int evidenceReferenceCount,
            String evidenceState,
            String freshnessState,
            Instant sourceChangedAt,
            long sourceVersion) {
    }

    public record PlanEligibilityProjection(
            String ownerService,
            String observationState,
            Instant observedAt,
            Instant sourceLastChangedAt,
            String coverageState,
            List<String> exclusions,
            PlanObservation plan,
            List<ProductEligibilityObservation> products) {

        public PlanEligibilityProjection {
            exclusions = List.copyOf(exclusions);
            products = List.copyOf(products);
        }
    }

    public record PlanObservation(
            String subscriptionState,
            String planKey,
            int planVersion,
            String displayName,
            Instant startsAt,
            Instant endsAt,
            long sourceVersion) {
    }

    public record ProductEligibilityObservation(
            String productKey,
            String appResourceKey,
            String entitlementKey,
            String entitlementType,
            String eligibilityState,
            Instant sourceChangedAt) {
    }
}
