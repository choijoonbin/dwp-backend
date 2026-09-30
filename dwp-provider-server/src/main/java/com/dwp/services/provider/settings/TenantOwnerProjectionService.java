package com.dwp.services.provider.settings;

import com.dwp.services.provider.settings.TenantOwnerProjectionDtos.DataGovernanceProjection;
import com.dwp.services.provider.settings.TenantOwnerProjectionDtos.DomainObservation;
import com.dwp.services.provider.settings.TenantOwnerProjectionDtos.DomainProjection;
import com.dwp.services.provider.settings.TenantOwnerProjectionDtos.PolicyObservation;
import com.dwp.services.provider.settings.TenantOwnerProjectionDtos.PlanEligibilityProjection;
import com.dwp.services.provider.settings.TenantOwnerProjectionDtos.PlanObservation;
import com.dwp.services.provider.settings.TenantOwnerProjectionDtos.ProductEligibilityObservation;
import com.dwp.services.provider.settings.TenantOwnerProjectionDtos.TenantLifecycleHoldObservation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Service
public class TenantOwnerProjectionService {

    private static final String OWNER = "provider-control-plane";
    private static final List<String> DOMAIN_EXCLUSIONS = List.of(
            "DNS_CHALLENGE_SECRET",
            "DNS_VERIFICATION_RECORD_VALUE",
            "REVOKED_DOMAINS");
    private static final List<String> GOVERNANCE_EXCLUSIONS = List.of(
            "TENANT_SCOPED_HOLD_OWNER_NOT_CONNECTED",
            "EXTERNAL_SHARING_OWNER_NOT_CONNECTED",
            "PHYSICAL_RETENTION_OR_DELETION_EXECUTION_NOT_OBSERVED");
    private static final List<String> PLAN_EXCLUSIONS = List.of(
            "PRODUCTS_WITHOUT_PROVIDER_ENTITLEMENT_BINDINGS",
            "EXTERNAL_SAAS_CAPABILITY_APPLICATION_NOT_OBSERVED");

    private final TenantOwnerProjectionRepository repository;
    private final Clock clock;

    @Autowired
    public TenantOwnerProjectionService(TenantOwnerProjectionRepository repository) {
        this(repository, Clock.systemUTC());
    }

    TenantOwnerProjectionService(TenantOwnerProjectionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DomainProjection domains() {
        TenantSettingsRequestContext.requirePermission(
                TenantSettingsSecurityFilter.DOMAIN_READ_PERMISSION);
        var actor = TenantSettingsRequestContext.require();
        List<DomainObservation> domains = repository.domains(actor.providerTenantId()).stream()
                .map(this::domain)
                .toList();
        return new DomainProjection(
                OWNER,
                "LIVE_OWNER_READ",
                clock.instant(),
                latest(domains.stream().map(DomainObservation::sourceChangedAt)),
                "CURRENT_TENANT_NON_REVOKED_DOMAINS",
                DOMAIN_EXCLUSIONS,
                domains);
    }

    @Transactional(readOnly = true)
    public DataGovernanceProjection dataGovernance() {
        TenantSettingsRequestContext.requirePermission(
                TenantSettingsSecurityFilter.GOVERNANCE_READ_PERMISSION);
        var actor = TenantSettingsRequestContext.require();
        Instant now = clock.instant();
        List<PolicyObservation> policies = repository.globalRetentionAndLegalHoldPolicies().stream()
                .map(row -> policy(row, now))
                .toList();
        List<TenantLifecycleHoldObservation> holds =
                repository.latestTenantLifecycleHoldObservations(actor.providerTenantId()).stream()
                        .map(this::hold)
                        .toList();
        return new DataGovernanceProjection(
                OWNER,
                "LIVE_OWNER_READ",
                now,
                latest(Stream.concat(
                        policies.stream().map(PolicyObservation::sourceChangedAt),
                        holds.stream().map(TenantLifecycleHoldObservation::sourceChangedAt))),
                "GLOBAL_POLICIES_AND_CURRENT_TENANT_LIFECYCLE_EVALUATIONS",
                GOVERNANCE_EXCLUSIONS,
                policies,
                holds);
    }

    @Transactional(readOnly = true)
    public PlanEligibilityProjection planEligibility() {
        TenantSettingsRequestContext.requirePermission(
                TenantSettingsSecurityFilter.PLAN_READ_PERMISSION);
        var actor = TenantSettingsRequestContext.require();
        var planRow = repository.currentPlan(actor.providerTenantId()).orElse(null);
        PlanObservation plan = planRow == null ? null : new PlanObservation(
                planRow.subscriptionState(), planRow.planKey(), planRow.planVersion(),
                planRow.displayName(), planRow.startsAt(), planRow.endsAt(), planRow.version());
        List<ProductEligibilityObservation> products = repository
                .productEligibility(actor.providerTenantId()).stream()
                .map(row -> new ProductEligibilityObservation(
                        row.productKey(), row.appResourceKey(), row.entitlementKey(),
                        row.entitlementType(), row.eligibilityState(), row.updatedAt()))
                .toList();
        Stream<Instant> planUpdates = planRow == null
                ? Stream.empty() : Stream.of(planRow.updatedAt());
        return new PlanEligibilityProjection(
                OWNER,
                "LIVE_OWNER_READ",
                clock.instant(),
                latest(Stream.concat(
                        planUpdates,
                        products.stream().map(ProductEligibilityObservation::sourceChangedAt))),
                "CURRENT_SUBSCRIPTION_AND_TENANT_ENTITLEMENTS",
                PLAN_EXCLUSIONS,
                plan,
                products);
    }

    private DomainObservation domain(TenantOwnerProjectionRepository.DomainRow row) {
        String evidenceFreshness = "INTERNAL".equals(row.verificationMethod())
                && "VERIFIED".equals(row.verificationState())
                ? "OWNER_ATTESTED"
                : row.lastCheckedAt() == null ? "NOT_OBSERVED" : "RECORDED_AT";
        return new DomainObservation(
                row.domainId(), row.domainName(), row.domainType(), row.verificationMethod(),
                row.verificationState(), row.primaryDomain(), row.verifiedAt(),
                row.lastCheckedAt(), row.updatedAt(), evidenceFreshness, row.version());
    }

    private PolicyObservation policy(
            TenantOwnerProjectionRepository.PolicyRow row,
            Instant now) {
        String effectiveState;
        if (row.effectiveFrom() != null && row.effectiveFrom().isAfter(now)) {
            effectiveState = "SCHEDULED";
        } else if (row.effectiveTo() != null && !row.effectiveTo().isAfter(now)) {
            effectiveState = "EXPIRED";
        } else if ("LEGAL_HOLD".equals(row.policyType())
                && Boolean.FALSE.equals(row.legalHoldActive())) {
            effectiveState = "INACTIVE";
        } else {
            effectiveState = "ACTIVE";
        }
        return new PolicyObservation(
                row.policyType(), row.ownerService(), "GLOBAL", row.revisionNumber(),
                effectiveState, row.retentionDays(), row.legalHoldActive(), row.effectiveFrom(),
                row.effectiveTo(), row.publishedAt(), row.updatedAt(),
                "CURRENT_OWNER_REVISION",
                row.impactHash() == null ? "NOT_RECORDED" : "IMPACT_FINGERPRINT_RECORDED",
                redactedFingerprint(row.impactHash()), row.version());
    }

    private TenantLifecycleHoldObservation hold(
            TenantOwnerProjectionRepository.TenantLifecycleHoldRow row) {
        return new TenantLifecycleHoldObservation(
                row.lifecycleRequestId(), row.requestedAction(), row.lifecycleState(),
                row.holdEvaluationState(), row.executionState(), row.evidenceReferenceCount(),
                row.evidenceReferenceCount() == 0 ? "NOT_RECORDED" : "REFERENCES_REDACTED",
                "RECORDED_AT", row.updatedAt(), row.version());
    }

    private Instant latest(Stream<Instant> values) {
        return values.filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    private String redactedFingerprint(String hash) {
        if (hash == null) return null;
        return hash.substring(0, Math.min(12, hash.length()));
    }
}
