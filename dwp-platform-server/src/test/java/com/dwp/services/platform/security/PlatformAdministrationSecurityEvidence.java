package com.dwp.services.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Inherited exact admin and catalog authorization contracts. */
abstract class PlatformAdministrationSecurityEvidence extends PlatformSecurityFilterTestSupport {

    @Test
    void protectsEveryExactAuditRuntimeCodeSetWithAuditView() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);
        for (String codeSet : List.of(
                "PLATFORM.AUDIT.WINDOW",
                "PLATFORM.AUDIT.CATEGORY_FILTER",
                "PLATFORM.AUDIT.SEVERITY_FILTER",
                "PLATFORM.AUDIT.OUTCOME_FILTER",
                "PLATFORM.EVENT_ENVELOPE.DOMAIN",
                "PLATFORM.EVENT_ENVELOPE.CLASSIFICATION",
                "PLATFORM.SYS_AUDIT_EXPORT_JOBS.FORMAT")) {
            String path = "/v1/catalog/code-sets/" + codeSet;
            assertThat(apply(filter, tenantAdminRequest("GET", path, null)).getStatus())
                    .as("missing permission " + codeSet)
                    .isEqualTo(403);
            assertThat(apply(filter, tenantAdminRequest(
                    "GET", path, "ADMIN.AUDIT_VIEW:VIEW")).getStatus())
                    .as("view " + codeSet)
                    .isEqualTo(200);
            assertThat(apply(filter, tenantAdminRequest(
                    "GET", path, "ADMIN.AUDIT_VIEW:MANAGE")).getStatus())
                    .as("wrong permission " + codeSet)
                    .isEqualTo(403);
        }
    }

    @Test
    void protectsEveryExactApiMonitoringRuntimeCodeSetWithApiMonitoringView()
            throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);
        for (String codeSet : List.of(
                "PLATFORM.API_HISTORY.WINDOW",
                "PLATFORM.API_HISTORY.OBSERVATION_POINT_FILTER",
                "PLATFORM.API_HISTORY.HTTP_METHOD_FILTER",
                "PLATFORM.API_HISTORY.OUTCOME_FILTER")) {
            String path = "/v1/catalog/code-sets/" + codeSet;
            assertThat(apply(filter, tenantAdminRequest("GET", path, null)).getStatus())
                    .as("missing permission " + codeSet)
                    .isEqualTo(403);
            assertThat(apply(filter, tenantAdminRequest(
                    "GET", path, "ADMIN.API_MONITORING:VIEW")).getStatus())
                    .as("view " + codeSet)
                    .isEqualTo(200);
            assertThat(apply(filter, tenantAdminRequest(
                    "GET", path, "ADMIN.API_MONITORING:MANAGE")).getStatus())
                    .as("wrong permission " + codeSet)
                    .isEqualTo(403);
        }
    }

    @Test
    void rejectsEncodedAndNonCanonicalRuntimeCodeSetPathsBeforeExactAuthorization()
            throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);

        assertThat(apply(filter, tenantAdminRequest(
                "GET",
                "/v1/catalog/code-sets/PLATFORM%2EAPI_HISTORY%2EWINDOW",
                null)).getStatus()).isEqualTo(403);
        assertThat(apply(filter, tenantAdminRequest(
                "GET",
                "/v1/catalog/code-sets/PLATFORM.API_HISTORY.WINDOW;ignored=true",
                null)).getStatus()).isEqualTo(403);
        assertThat(apply(filter, tenantAdminRequest(
                "GET",
                "/v1/catalog/code-sets/platform.api_history.window",
                null)).getStatus()).isEqualTo(403);
    }

    @Test
    void confinesSharedSettingsRuntimeCodeSetsToTheirExactTenantBoundaries() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);
        for (String codeSet : List.of(
                "PLATFORM.PREFERENCE.COLOR_MODE",
                "PLATFORM.PREFERENCE.DENSITY",
                "PLATFORM.PREFERENCE.TIME_ZONE",
                "PLATFORM.PREFERENCE.DATE_FORMAT",
                "PLATFORM.PREFERENCE.TIME_FORMAT",
                "PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK",
                "PLATFORM.PREFERENCE.NUMBER_FORMAT")) {
            String path = "/v1/catalog/code-sets/" + codeSet;
            assertThat(apply(filter, tenantAdminRequest("GET", path, null)).getStatus())
                    .as(codeSet)
                    .isEqualTo(200);

            MockHttpServletRequest provider = tenantAdminRequest("GET", path, null);
            provider.removeHeader("X-DWP-Identity-Plane");
            provider.addHeader("X-DWP-Identity-Plane", "PROVIDER");
            assertThat(apply(filter, provider).getStatus()).as(codeSet).isEqualTo(403);
        }

        MockHttpServletRequest auditProvider = tenantAdminRequest(
                "GET",
                "/v1/catalog/code-sets/PLATFORM.AUDIT.WINDOW",
                "ADMIN.AUDIT_VIEW:VIEW");
        auditProvider.removeHeader("X-DWP-Identity-Plane");
        auditProvider.addHeader("X-DWP-Identity-Plane", "PROVIDER");
        assertThat(apply(filter, auditProvider).getStatus()).isEqualTo(403);

        assertThat(apply(filter, tenantAdminRequest(
                "GET",
                "/v1/catalog/code-sets/PLATFORM.AUDIT.UNKNOWN",
                null)).getStatus()).isEqualTo(200);
    }

    @Test
    void requiresExactApiMonitoringViewAndManageAuthorities() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);

        assertThat(apply(filter, tenantAdminRequest(
                "GET", "/v1/admin/api-history/overview", null)).getStatus()).isEqualTo(403);
        assertThat(apply(filter, tenantAdminRequest(
                "GET", "/v1/admin/api-history/overview",
                "ADMIN.API_MONITORING:VIEW")).getStatus()).isEqualTo(200);
        MockHttpServletRequest delegatedReader = tenantAdminRequest(
                "GET", "/v1/admin/api-history/events", "ADMIN.API_MONITORING:VIEW");
        delegatedReader.removeHeader(PlatformSecurityFilter.ROLES_HEADER);
        delegatedReader.addHeader(PlatformSecurityFilter.ROLES_HEADER, "CUSTOM_API_OBSERVER");
        assertThat(apply(filter, delegatedReader).getStatus()).isEqualTo(200);
        assertThat(apply(filter, tenantAdminRequest(
                "HEAD", "/v1/admin/api-history/events",
                "ADMIN.API_MONITORING:VIEW")).getStatus()).isEqualTo(200);
        assertThat(apply(filter, tenantAdminRequest(
                "GET", "/v1/admin/api-history/events",
                "ADMIN.API_MONITORING:MANAGE")).getStatus()).isEqualTo(403);
        assertThat(apply(filter, tenantAdminRequest(
                "POST", "/v1/admin/api-history/retention",
                "ADMIN.API_MONITORING:VIEW")).getStatus()).isEqualTo(403);
        assertThat(apply(filter, tenantAdminRequest(
                "POST", "/v1/admin/api-history/retention",
                "ADMIN.API_MONITORING:MANAGE")).getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsApiMonitoringAuthorityFromProviderSupportAndAdjacentPaths() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);

        MockHttpServletRequest provider = tenantAdminRequest(
                "GET", "/v1/admin/api-history/overview",
                "ADMIN.API_MONITORING:VIEW");
        provider.removeHeader("X-DWP-Identity-Plane");
        provider.addHeader("X-DWP-Identity-Plane", "PROVIDER");
        assertThat(apply(filter, provider).getStatus()).isEqualTo(403);

        MockHttpServletRequest support = tenantAdminRequest(
                "GET", "/v1/admin/api-history/overview",
                "ADMIN.API_MONITORING:VIEW");
        support.addHeader(PlatformSecurityFilter.SUPPORT_SESSION_HEADER, "support-1");
        support.addHeader(
                PlatformSecurityFilter.SUPPORT_SCOPES_HEADER,
                "TENANT_CONFIGURATION_READ");
        support.addHeader(PlatformSecurityFilter.ACTOR_TENANT_HEADER, "99");
        assertThat(apply(filter, support).getStatus()).isEqualTo(403);

        MockHttpServletRequest adjacent = tenantAdminRequest(
                "GET", "/v1/admin/api-history-archive",
                "ADMIN.API_MONITORING:VIEW");
        adjacent.removeHeader(PlatformSecurityFilter.ROLES_HEADER);
        adjacent.addHeader(PlatformSecurityFilter.ROLES_HEADER, "CUSTOM_API_OBSERVER");
        assertThat(apply(filter, adjacent).getStatus()).isEqualTo(403);
    }

    @Test
    void requiresExactTenantCatalogAndRegistryPermissionsInsteadOfRoleNames() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);

        MockHttpServletRequest roleOnly = tenantAdminRequest("GET", "/v1/admin/catalog", null);
        MockHttpServletResponse roleOnlyResponse = new MockHttpServletResponse();
        filter.doFilter(roleOnly, roleOnlyResponse, new MockFilterChain());
        assertThat(roleOnlyResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest catalogReader = tenantAdminRequest(
                "GET", "/v1/admin/catalog", "ADMIN.PLATFORM_CATALOG:VIEW");
        MockHttpServletResponse catalogReaderResponse = new MockHttpServletResponse();
        filter.doFilter(catalogReader, catalogReaderResponse, new MockFilterChain());
        assertThat(catalogReaderResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest readCannotWrite = tenantAdminRequest(
                "POST", "/v1/admin/catalog/relations", "ADMIN.PLATFORM_CATALOG:VIEW");
        MockHttpServletResponse readCannotWriteResponse = new MockHttpServletResponse();
        filter.doFilter(readCannotWrite, readCannotWriteResponse, new MockFilterChain());
        assertThat(readCannotWriteResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest registryManager = tenantAdminRequest(
                "POST", "/v1/admin/registry-entries", "ADMIN.PLATFORM_REGISTRY:MANAGE");
        MockHttpServletResponse registryManagerResponse = new MockHttpServletResponse();
        filter.doFilter(registryManager, registryManagerResponse, new MockFilterChain());
        assertThat(registryManagerResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest wrongPlane = tenantAdminRequest(
                "GET", "/v1/admin/catalog", "ADMIN.PLATFORM_CATALOG:VIEW");
        wrongPlane.removeHeader("X-DWP-Identity-Plane");
        wrongPlane.addHeader("X-DWP-Identity-Plane", "PROVIDER");
        MockHttpServletResponse wrongPlaneResponse = new MockHttpServletResponse();
        filter.doFilter(wrongPlane, wrongPlaneResponse, new MockFilterChain());
        assertThat(wrongPlaneResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void requiresExactViewAndManagePermissionsForEverySettingsOwnerPath() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);
        String[][] paths = {
                {"/v1/admin/tenant-branding", "ADMIN.TENANT_BRANDING"},
                {"/v1/admin/preference-exceptions", "ADMIN.MANAGED_PREFERENCES"},
                {"/v1/admin/localization", "ADMIN.LOCALIZATION"},
                {"/v1/admin/catalog", "ADMIN.PLATFORM_CATALOG"},
                {"/v1/admin/registry-entries", "ADMIN.PLATFORM_REGISTRY"},
                {"/v1/admin/navigation", "ADMIN.NAVIGATION"},
                {"/v1/admin/navigation/studio", "ADMIN.NAVIGATION"},
                {"/v1/admin/reference-sets", "ADMIN.REFERENCE_DATA"}
        };

        for (String[] entry : paths) {
            MockHttpServletResponse roleOnlyResponse = apply(
                    filter, tenantAdminRequest("GET", entry[0], null));
            assertThat(roleOnlyResponse.getStatus()).as("role-only " + entry[0]).isEqualTo(403);

            MockHttpServletResponse readerResponse = apply(
                    filter, tenantAdminRequest("GET", entry[0], entry[1] + ":VIEW"));
            assertThat(readerResponse.getStatus()).as("read " + entry[0]).isEqualTo(200);

            MockHttpServletResponse readCannotWriteResponse = apply(
                    filter, tenantAdminRequest("POST", entry[0], entry[1] + ":VIEW"));
            assertThat(readCannotWriteResponse.getStatus())
                    .as("read cannot write " + entry[0]).isEqualTo(403);

            MockHttpServletResponse managerResponse = apply(
                    filter, tenantAdminRequest("POST", entry[0], entry[1] + ":MANAGE"));
            assertThat(managerResponse.getStatus()).as("manage " + entry[0]).isEqualTo(200);
        }
    }

    @Test
    void exactSettingsPermissionCannotCrossIdentityPlanesOrTenantBoundaries() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);

        MockHttpServletRequest provider = tenantAdminRequest(
                "GET", "/v1/admin/tenant-branding", "ADMIN.TENANT_BRANDING:VIEW");
        provider.removeHeader("X-DWP-Identity-Plane");
        provider.addHeader("X-DWP-Identity-Plane", "PROVIDER");
        assertThat(apply(filter, provider).getStatus()).isEqualTo(403);

        MockHttpServletRequest crossTenant = tenantAdminRequest(
                "GET", "/v1/admin/tenant-branding", "ADMIN.TENANT_BRANDING:VIEW");
        crossTenant.addHeader(PlatformSecurityFilter.ACTOR_TENANT_HEADER, "99");
        assertThat(apply(filter, crossTenant).getStatus()).isEqualTo(403);

        MockHttpServletRequest support = tenantAdminRequest(
                "GET", "/v1/admin/tenant-branding", "ADMIN.TENANT_BRANDING:VIEW");
        support.addHeader(PlatformSecurityFilter.SUPPORT_SESSION_HEADER, "support-1");
        support.addHeader(PlatformSecurityFilter.SUPPORT_SCOPES_HEADER, "TENANT_CONFIGURATION_READ");
        support.addHeader(PlatformSecurityFilter.ACTOR_TENANT_HEADER, "99");
        assertThat(apply(filter, support).getStatus()).isEqualTo(403);
    }
}
