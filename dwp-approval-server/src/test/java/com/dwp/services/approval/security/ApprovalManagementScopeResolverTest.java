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
