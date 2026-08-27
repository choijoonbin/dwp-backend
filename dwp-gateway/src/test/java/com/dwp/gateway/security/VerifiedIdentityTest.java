package com.dwp.gateway.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifiedIdentityTest {

    @Test
    void providerPlaneRejectsTenantResourceRoleEvidence() {
        assertThatThrownBy(() -> identity(
                "PROVIDER",
                List.of("PROVIDER_ADMIN"),
                List.of("APP_OWNER@RS_MAIL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("provider identities cannot carry tenant resource-role evidence");
    }

    @Test
    void tenantPlanePreservesTenantResourceRoleEvidence() {
        VerifiedIdentity identity = identity(
                "TENANT",
                List.of("WORKSPACE_MEMBER"),
                List.of("APP_OWNER@RS_MAIL"));

        assertThat(identity.resourceRoles()).containsExactly("APP_OWNER@RS_MAIL");
    }

    @Test
    void rolelessProviderPlaneAllowsEmptyResourceRoleEvidence() {
        VerifiedIdentity identity = identity("PROVIDER", List.of(), List.of());

        assertThat(identity.identityPlane()).isEqualTo("PROVIDER");
        assertThat(identity.roles()).isEmpty();
        assertThat(identity.resourceRoles()).isEmpty();
    }

    private VerifiedIdentity identity(
            String identityPlane,
            List<String> roles,
            List<String> resourceRoles) {
        return new VerifiedIdentity(
                "7",
                "1",
                roles,
                List.of(),
                List.of(),
                resourceRoles,
                null,
                null,
                false,
                null,
                identityPlane);
    }
}
