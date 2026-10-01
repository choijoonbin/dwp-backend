package com.dwp.services.auth.tenantcapabilityoverride;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.service.AppGovernanceAuthorization;
import com.dwp.services.auth.service.IdentityAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantCapabilityOverrideServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T05:00:00Z");
    private static final UUID CHANGE_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111");
    private static final UUID BUNDLE_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222");

    private final TenantCapabilityOverrideRepository repository =
            mock(TenantCapabilityOverrideRepository.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final AppGovernanceAuthorization authorization = mock(AppGovernanceAuthorization.class);
    private TenantCapabilityOverrideService service;

    @BeforeEach
    void setUp() {
        service = new TenantCapabilityOverrideService(
                repository, audit, authorization, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void projectsActualPolicyEffectiveSourceAndServerDerivedActions() {
        when(authorization.requireVisibility(7L, 101L)).thenReturn(
                new AppGovernanceAuthorization.Visibility(
                        false, Set.of(), Set.of(), Set.of("APP.EMPLOYEE_SERVICES")));
        when(authorization.appResourceKeys(7L, 101L, "APP_OWNER"))
                .thenReturn(Set.of("APP.EMPLOYEE_SERVICES"));
        when(authorization.appResourceKeys(7L, 101L, "APP_ACCESS_APPROVER"))
                .thenReturn(Set.of());
        when(authorization.appResourceKeys(7L, 101L, "APP_ACCESS_MANAGER"))
                .thenReturn(Set.of());
        when(repository.policies()).thenReturn(List.of(policy("ALLOW_DISABLE")));
        when(repository.changes(7L)).thenReturn(List.of());
        when(repository.isDisabled(7L, "services.catalog.read", NOW)).thenReturn(false);

        TenantCapabilityOverrideDtos.Projection result = service.projection(7L, 101L);

        assertThat(result.coverageState()).isEqualTo("COMPLETE_INTERNAL_OWNERS");
        assertThat(result.exclusions())
                .containsExactly("EXTERNAL_SAAS_CAPABILITY_APPLICATION_UNAVAILABLE");
        assertThat(result.capabilities()).singleElement().satisfies(capability -> {
            assertThat(capability.effectiveState()).isEqualTo("ENABLED");
            assertThat(capability.effectiveSource())
                    .isEqualTo("GLOBAL_AUTHORIZATION_BUNDLE");
            assertThat(capability.policy().allowedActions())
                    .containsExactly("REQUEST_DISABLE");
            assertThat(capability.lineage()).extracting(
                    TenantCapabilityOverrideDtos.Lineage::ownerKey)
                    .containsExactly("AUTH_PRODUCT_AUTHORIZATION_CATALOG");
        });
    }

    @Test
    void refusesAChangeForAnOwnerLockedCapability() {
        TenantCapabilityOverrideDtos.Policy locked = policy("OWNER_LOCKED");
        when(repository.policy(locked.contractKey())).thenReturn(java.util.Optional.of(locked));

        assertThatThrownBy(() -> service.create(
                7L, 101L, "correlation", new TenantCapabilityOverrideDtos.CreateRequest(
                        locked.contractKey(), "DISABLED", NOW.plusSeconds(3600),
                        "Suppress this capability after reviewing tenant impact.")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(authorization).requireAppResponsibility(
                7L, 101L, "APP_OWNER", "APP.EMPLOYEE_SERVICES", "correlation",
                "TENANT_CAPABILITY_OVERRIDE", locked.contractKey());
    }

    @Test
    void activatesOnlyWithTheThirdIndependentActorAndCurrentBundleRevision() {
        TenantCapabilityOverrideDtos.Change approved = change(
                "APPROVED", "DISABLED", 101L, 102L, null, 2L);
        TenantCapabilityOverrideDtos.Change active = change(
                "ACTIVE", "DISABLED", 101L, 102L, 103L, 3L);
        when(repository.lockChange(7L, CHANGE_ID)).thenReturn(approved);
        when(repository.policy(approved.contractKey()))
                .thenReturn(java.util.Optional.of(policy("ALLOW_DISABLE")));
        when(repository.activate(
                eq(7L), eq(CHANGE_ID), eq(2L), eq(103L),
                eq("Activate the independently approved tenant suppression."),
                any(UUID.class), eq(NOW))).thenReturn(active);
        when(authorization.appResourceKeys(7L, 103L, "APP_OWNER")).thenReturn(Set.of());
        when(authorization.appResourceKeys(7L, 103L, "APP_ACCESS_APPROVER"))
                .thenReturn(Set.of());
        when(authorization.appResourceKeys(7L, 103L, "APP_ACCESS_MANAGER"))
                .thenReturn(Set.of("APP.EMPLOYEE_SERVICES"));

        TenantCapabilityOverrideDtos.Change result = service.activate(
                7L, 103L, "correlation", CHANGE_ID,
                new TenantCapabilityOverrideDtos.ReasonedCommand(
                        2L, "Activate the independently approved tenant suppression."));

        assertThat(result.lifecycleState()).isEqualTo("ACTIVE");
        verify(authorization).requireAppResponsibility(
                7L, 103L, "APP_ACCESS_MANAGER", "APP.EMPLOYEE_SERVICES", "correlation",
                "TENANT_CAPABILITY_OVERRIDE", CHANGE_ID.toString());
        verify(repository).appendEvent(
                eq(7L), eq(CHANGE_ID), eq("OVERRIDE_ACTIVATED"), eq(103L),
                eq("correlation"), eq(3L), any(UUID.class), any());
    }

    private TenantCapabilityOverrideDtos.Policy policy(String mode) {
        return new TenantCapabilityOverrideDtos.Policy(
                "services.catalog.read", "services", "APP.EMPLOYEE_SERVICES",
                "services.management", "ADMIN.SERVICE_CATALOG:VIEW", "VIEW",
                "HIGH", "AUTH_PRODUCT_AUTHORIZATION_CATALOG", BUNDLE_ID, 8L,
                "OWNER_LOCKED".equals(mode)
                        ? "high-risk-owner-lock" : "tenant-restrictive-disable",
                1L, mode, "OWNER_LOCKED".equals(mode) ? null : 90,
                "AUTH_TENANT_CAPABILITY_OVERRIDE", "TEST_POLICY", "UNAVAILABLE", List.of());
    }

    private TenantCapabilityOverrideDtos.Change change(
            String state,
            String desired,
            Long requestedBy,
            Long approvedBy,
            Long activatedBy,
            long version) {
        return new TenantCapabilityOverrideDtos.Change(
                CHANGE_ID, "services.catalog.read", "services",
                "APP.EMPLOYEE_SERVICES", "tenant-restrictive-disable", 1L,
                BUNDLE_ID, 8L, desired, state, NOW.plusSeconds(3600),
                "Suppress this capability after reviewing tenant impact.",
                requestedBy, NOW.minusSeconds(120), approvedBy,
                approvedBy == null ? null : NOW.minusSeconds(60),
                approvedBy == null ? null : "Approved after independent review.",
                activatedBy, activatedBy == null ? null : NOW,
                activatedBy == null ? null : UUID.randomUUID(), null, null, null,
                version, NOW.minusSeconds(180), NOW, List.of());
    }
}
