package com.dwp.core.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RolePlaneBoundaryTest {

    @Test
    void rejectsProviderAndTenantRolesOnOneIdentity() {
        assertThat(RolePlaneBoundary.hasConflict(
                List.of("PROVIDER_ADMIN", "WORKSPACE_MEMBER"))).isTrue();
        assertThat(RolePlaneBoundary.hasConflict(
                List.of("provider_support", "TENANT_ADMIN"))).isTrue();
    }

    @Test
    void acceptsAProviderOnlyOrTenantOnlyIdentity() {
        assertThat(RolePlaneBoundary.hasConflict(
                List.of("PROVIDER_ADMIN", "PROVIDER_AUDITOR"))).isFalse();
        assertThat(RolePlaneBoundary.hasConflict(
                List.of("WORKSPACE_MEMBER", "TENANT_ADMIN"))).isFalse();
    }
}
