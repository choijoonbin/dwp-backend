package com.dwp.services.approval.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.core.security.ScopedAuthorityToken;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalManagementScopeResolverTest {

    private final ApprovalManagementScopeResolver resolver =
            new ApprovalManagementScopeResolver();

    @Test
    void resolvesOnlyTheSelectedSetBoundToTheExactCapability() {
        String roles = String.join(",",
                "APP_CONFIG_ADMIN@RS_TEAM_A",
                "APP_CONFIG_ADMIN@RS_TEAM_B",
                ScopedAuthorityToken.wireToken(
                        "approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW", "RS_TEAM_A"),
                ScopedAuthorityToken.wireToken(
                        "approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW", "RS_TEAM_B"));

        assertThat(resolver.resolve(
                42L, 17L, scope("RS_TEAM_B"), List.of(authority()), roles))
                .isEqualTo("RS_TEAM_B");
    }

    @Test
    void rejectsCrossCapabilityAndUnpairedSelections() {
        String wrongCapability = "APP_CONFIG_ADMIN@RS_TEAM_B,"
                + ScopedAuthorityToken.wireToken(
                "approvals.design.update", "ADMIN.APPROVAL_DESIGN:UPDATE", "RS_TEAM_B");
        String unpaired = "APP_CONFIG_ADMIN@RS_TEAM_A,"
                + ScopedAuthorityToken.wireToken(
                "approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW", "RS_TEAM_B");

        assertThat(resolver.resolve(
                42L, 17L, scope("RS_TEAM_B"), List.of(authority()), wrongCapability)).isNull();
        assertThat(resolver.resolve(
                42L, 17L, scope("RS_TEAM_B"), List.of(authority()), unpaired)).isNull();
    }

    @Test
    void resolvesPolicyImpactOnlyWhenAllThreeIndependentAuthoritiesBindTheSelectedSet() {
        assertThat(resolver.resolve(42L, 17L, scope("RS_TEAM_B"), impactAuthorities(),
                impactRoles("RS_TEAM_B", "RS_TEAM_B", "RS_TEAM_B"))).isEqualTo("RS_TEAM_B");
    }

    @Test
    void oneMatchingManagementGrantCannotHealTheOtherTwoScopes() {
        assertThat(resolver.resolve(42L, 17L, scope("RS_TEAM_B"), impactAuthorities(),
                impactRoles("RS_TEAM_A", "RS_TEAM_A", "RS_TEAM_B"))).isNull();
    }

    @Test
    void missingOrDuplicateAuthorityContributionsCannotResolvePolicyImpact() {
        var authorities = impactAuthorities();
        String roles = impactRoles("RS_TEAM_B", "RS_TEAM_B", "RS_TEAM_B");
        assertThat(resolver.resolve(42L, 17L, scope("RS_TEAM_B"), authorities.subList(0, 2), roles)).isNull();
        assertThat(resolver.resolve(42L, 17L, scope("RS_TEAM_B"),
                List.of(authorities.getFirst(), authorities.getFirst(), authorities.getLast()), roles)).isNull();
    }

    private List<ApprovalPilotPepRegistry.RouteAuthority> impactAuthorities() {
        return List.of(impactAuthority("design", "DESIGN"), impactAuthority("operations", "OPERATIONS"),
                impactAuthority("policy", "POLICY"));
    }

    private ApprovalPilotPepRegistry.RouteAuthority impactAuthority(String capability, String resource) {
        return new ApprovalPilotPepRegistry.RouteAuthority(
                "route.approvals.admin.policy-impact.data", "DATA", "full-management", true,
                Set.of(), "approvals." + capability + ".read", null, null, false,
                null, null, null, null, null, "ADMIN.APPROVAL_" + resource + ":VIEW", "APP_CONFIG_ADMIN");
    }

    private String impactRoles(String designSet, String operationsSet, String policySet) {
        return String.join(",", "APP_CONFIG_ADMIN@RS_TEAM_A", "APP_CONFIG_ADMIN@RS_TEAM_B",
                ScopedAuthorityToken.wireToken("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW", designSet),
                ScopedAuthorityToken.wireToken("approvals.operations.read", "ADMIN.APPROVAL_OPERATIONS:VIEW", operationsSet),
                ScopedAuthorityToken.wireToken("approvals.policy.read", "ADMIN.APPROVAL_POLICY:VIEW", policySet));
    }

    private String scope(String set) {
        return ProductSurfaceScopeKey.resourceSet(
                42L, 17L, "approvals", "approvals.admin", set);
    }

    private ApprovalPilotPepRegistry.RouteAuthority authority() {
        return new ApprovalPilotPepRegistry.RouteAuthority(
                "route.approvals.admin.workflows.page", "PAGE", "full-management", false,
                Set.of(), "approvals.design.read", null, null, false,
                null, null, null, null, null,
                "ADMIN.APPROVAL_DESIGN:VIEW", "APP_CONFIG_ADMIN");
    }
}
