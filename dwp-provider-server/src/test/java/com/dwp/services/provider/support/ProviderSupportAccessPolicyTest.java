package com.dwp.services.provider.support;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderSupportAccessPolicyTest {

    @Test
    void separatesReadAndWriteTenantConfigurationScopes() {
        assertThat(ProviderSupportAccessPolicy.requiredScope(
                "GET", "/api/platform/v1/admin/tenant-branding"))
                .isEqualTo("TENANT_CONFIGURATION_READ");
        assertThat(ProviderSupportAccessPolicy.requiredScope(
                "PUT", "/api/platform/v1/admin/tenant-branding"))
                .isEqualTo("TENANT_CONFIGURATION_WRITE");
    }

    @Test
    void permitsOnlyReadOperationsForWorkforceSupport() {
        assertThat(ProviderSupportAccessPolicy.requiredScope(
                "GET", "/api/people/v1/workforce/organization/intelligence"))
                .isEqualTo("WORKFORCE_READ");
        assertThatThrownBy(() -> ProviderSupportAccessPolicy.requiredScope(
                "POST", "/api/people/v1/workforce/organization/scenarios"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void rejectsUnrelatedTenantAdministrationSurfaces() {
        assertThatThrownBy(() -> ProviderSupportAccessPolicy.requiredScope(
                "GET", "/api/auth/admin/v1/access/roles"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> ProviderSupportAccessPolicy.requiredScope(
                "GET", "/api/platform/v1/admin/code-catalog/code-sets"))
                .isInstanceOf(BaseException.class);
    }
}
