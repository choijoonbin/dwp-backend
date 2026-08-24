package com.dwp.services.platform.security;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformApprovalsPepRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clear() {
        PlatformApprovalsAuthorizationContext.clear();
    }

    @Test
    void loadsOnlyTheTwoFixedApprovalHomeBindings() {
        PlatformApprovalsPepRegistry registry = new PlatformApprovalsPepRegistry(objectMapper);

        assertThat(registry.bindingContracts()).hasSize(2);
        assertThat(registry.bindingContracts())
                .extracting(PlatformApprovalsPepRegistry.BindingContract::method)
                .containsExactlyInAnyOrder("GET", "PUT");
        assertThat(registry.bindingContracts())
                .allSatisfy(binding -> assertThat(binding.fixedPathValues())
                        .containsExactlyEntriesOf(java.util.Map.of(
                                "surfaceKey", "approval-home")));
    }

    @Test
    void requiresApprovalsEntitlementAndExactFixedSurface() {
        PlatformApprovalsPepRegistry registry = new PlatformApprovalsPepRegistry(objectMapper);

        assertThat(registry.authorize(evidence(
                "GET", "/v1/home-preferences/surfaces/approval-home",
                Set.of("APP.APPROVALS:VIEW"),
                "route.approvals.work.home-preference.data")).allowed()).isTrue();
        assertThat(registry.authorize(evidence(
                "GET", "/v1/home-preferences/surfaces/hcm-home",
                Set.of("APP.APPROVALS:VIEW"),
                "route.approvals.work.home-preference.data")).allowed()).isFalse();
        assertThat(registry.authorize(evidence(
                "GET", "/v1/home-preferences/surfaces/approval-home",
                Set.of("APP.HCM:VIEW"),
                "route.approvals.work.home-preference.data")).allowed()).isFalse();
    }

    @Test
    void rejectsMethodMismatchUnknownResetAndClientRouteSpoofing() {
        PlatformApprovalsPepRegistry registry = new PlatformApprovalsPepRegistry(objectMapper);

        assertThat(registry.authorize(evidence(
                "POST", "/v1/home-preferences/surfaces/approval-home",
                Set.of("APP.APPROVALS:VIEW"),
                "route.approvals.work.home-preference.data")).allowed()).isFalse();
        assertThat(registry.authorize(evidence(
                "POST", "/v1/home-preferences/surfaces/approval-home/reset",
                Set.of("APP.APPROVALS:VIEW"),
                "route.approvals.work.home-preference.data")).allowed()).isFalse();
        assertThat(registry.authorize(evidence(
                "GET", "/v1/home-preferences/surfaces/approval-home",
                Set.of("APP.APPROVALS:VIEW"),
                "route.approvals.work.home-preference-update.action")).denialCode())
                .isEqualTo("EXACT_ROUTE_AUTHORITY_REQUIRED");
    }

    @Test
    void ownerContextRechecksActorAndTenantForTheGovernedSurfaceOnly() {
        PlatformApprovalsAuthorizationContext.set(
                7L, 11L, java.util.List.of("route.approvals.work.home-preference.data"));

        PlatformApprovalsAuthorizationContext.requireSelf(7L, 11L, "approval-home");
        assertThatThrownBy(() -> PlatformApprovalsAuthorizationContext.requireSelf(
                7L, 12L, "approval-home"))
                .isInstanceOf(BaseException.class);
        PlatformApprovalsAuthorizationContext.requireSelf(7L, 11L, "hcm-home");
    }

    private PlatformApprovalsPepRegistry.RequestEvidence evidence(
            String method, String path, Set<String> permissions, String routeKey) {
        return new PlatformApprovalsPepRegistry.RequestEvidence(
                method, path, permissions, routeKey);
    }
}
