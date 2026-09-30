package com.dwp.services.provider.settings;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantOwnerProjectionServiceTest {

    private static final UUID TENANT_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-29T02:00:00Z");

    private final TenantOwnerProjectionRepository repository =
            mock(TenantOwnerProjectionRepository.class);
    private final TenantOwnerProjectionService service = new TenantOwnerProjectionService(
            repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clearContext() {
        TenantSettingsRequestContext.clear();
    }

    @Test
    void domainProjectionUsesOnlyMappedTenantAndNeverNeedsChallengeMaterial() {
        setActor(Set.of(TenantSettingsSecurityFilter.DOMAIN_READ_PERMISSION));
        Instant changedAt = NOW.minusSeconds(10);
        when(repository.domains(TENANT_ID)).thenReturn(List.of(
                new TenantOwnerProjectionRepository.DomainRow(
                        UUID.randomUUID(), "corp.example", "LOGIN", "DNS_TXT", "PENDING",
                        false, null, null, changedAt, 4),
                new TenantOwnerProjectionRepository.DomainRow(
                        UUID.randomUUID(), "default.local", "LOGIN", "INTERNAL", "VERIFIED",
                        true, NOW.minusSeconds(100), null, NOW.minusSeconds(20), 1)));

        var result = service.domains();

        verify(repository).domains(TENANT_ID);
        assertThat(result.observationState()).isEqualTo("LIVE_OWNER_READ");
        assertThat(result.sourceLastChangedAt()).isEqualTo(changedAt);
        assertThat(result.domains()).extracting(
                TenantOwnerProjectionDtos.DomainObservation::evidenceFreshnessState)
                .containsExactly("NOT_OBSERVED", "OWNER_ATTESTED");
        assertThat(result.exclusions()).contains(
                "DNS_CHALLENGE_SECRET", "DNS_VERIFICATION_RECORD_VALUE");
    }

    @Test
    void governanceProjectionSeparatesLiveOwnerReadFromRecordedTenantHoldEvidence() {
        setActor(Set.of(TenantSettingsSecurityFilter.GOVERNANCE_READ_PERMISSION));
        when(repository.globalRetentionAndLegalHoldPolicies()).thenReturn(List.of(
                new TenantOwnerProjectionRepository.PolicyRow(
                        "RETENTION", "provider-data-governance", 2, 90, null,
                        NOW.minusSeconds(100), null, NOW.minusSeconds(90),
                        NOW.minusSeconds(80), "a".repeat(64), 5),
                new TenantOwnerProjectionRepository.PolicyRow(
                        "LEGAL_HOLD", "provider-data-governance", 3, null, true,
                        NOW.plusSeconds(600), null, NOW.minusSeconds(50),
                        NOW.minusSeconds(40), null, 6)));
        when(repository.latestTenantLifecycleHoldObservations(TENANT_ID)).thenReturn(List.of(
                new TenantOwnerProjectionRepository.TenantLifecycleHoldRow(
                        UUID.randomUUID(), "PURGE", "BLOCKED_BY_HOLD",
                        "ACTIVE_GLOBAL_LEGAL_HOLD", "OWNER_HANDOFF_REQUIRED", 2,
                        NOW.minusSeconds(30), 7)));

        var result = service.dataGovernance();

        assertThat(result.coverageState())
                .isEqualTo("GLOBAL_POLICIES_AND_CURRENT_TENANT_LIFECYCLE_EVALUATIONS");
        assertThat(result.policies()).extracting(
                TenantOwnerProjectionDtos.PolicyObservation::effectiveState)
                .containsExactly("ACTIVE", "SCHEDULED");
        assertThat(result.policies().getFirst().impactFingerprint())
                .isEqualTo("a".repeat(12));
        assertThat(result.tenantLifecycleHoldObservations().getFirst().evidenceState())
                .isEqualTo("REFERENCES_REDACTED");
        assertThat(result.tenantLifecycleHoldObservations().getFirst().freshnessState())
                .isEqualTo("RECORDED_AT");
        assertThat(result.exclusions()).contains(
                "TENANT_SCOPED_HOLD_OWNER_NOT_CONNECTED",
                "EXTERNAL_SHARING_OWNER_NOT_CONNECTED",
                "PHYSICAL_RETENTION_OR_DELETION_EXECUTION_NOT_OBSERVED");
    }

    @Test
    void exactResourcePermissionIsAlsoEnforcedInsideTheService() {
        setActor(Set.of("ADMIN.IDENTITY_PROVISIONING:MANAGE"));

        assertThatThrownBy(service::domains)
                .isInstanceOf(BaseException.class);
    }

    @Test
    void planProjectionReturnsOnlyTenantSafeEligibilityAndCurrentPlanEvidence() {
        setActor(Set.of(TenantSettingsSecurityFilter.PLAN_READ_PERMISSION));
        when(repository.currentPlan(TENANT_ID)).thenReturn(Optional.of(
                new TenantOwnerProjectionRepository.PlanRow(
                        "ACTIVE", "DWP_ENTERPRISE", 4, "DWP Enterprise",
                        NOW.minusSeconds(3600), null, 7, NOW.minusSeconds(20))));
        when(repository.productEligibility(TENANT_ID)).thenReturn(List.of(
                new TenantOwnerProjectionRepository.ProductEligibilityRow(
                        "approvals", "APP.APPROVALS", "core.approvals", "APP",
                        "ELIGIBLE", NOW.minusSeconds(10))));

        var result = service.planEligibility();

        verify(repository).currentPlan(TENANT_ID);
        verify(repository).productEligibility(TENANT_ID);
        assertThat(result.coverageState())
                .isEqualTo("CURRENT_SUBSCRIPTION_AND_TENANT_ENTITLEMENTS");
        assertThat(result.plan().planKey()).isEqualTo("DWP_ENTERPRISE");
        assertThat(result.products()).singleElement().satisfies(product -> {
            assertThat(product.productKey()).isEqualTo("approvals");
            assertThat(product.eligibilityState()).isEqualTo("ELIGIBLE");
        });
        assertThat(result.exclusions()).contains(
                "PRODUCTS_WITHOUT_PROVIDER_ENTITLEMENT_BINDINGS",
                "EXTERNAL_SAAS_CAPABILITY_APPLICATION_NOT_OBSERVED");
    }

    @Test
    void appGovernanceRoleAloneCannotReadPlanEligibility() {
        setActor(Set.of("ADMIN.APP_GOVERNANCE:MANAGE"));

        assertThatThrownBy(service::planEligibility)
                .isInstanceOf(BaseException.class);
    }

    private void setActor(Set<String> permissions) {
        TenantSettingsRequestContext.set(new TenantSettingsRequestContext.Actor(
                3L, 17L, UUID.randomUUID(), TENANT_ID,
                Set.of("TENANT_ADMIN"), permissions));
    }
}
