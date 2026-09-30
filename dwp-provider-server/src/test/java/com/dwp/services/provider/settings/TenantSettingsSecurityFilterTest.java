package com.dwp.services.provider.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantSettingsSecurityFilterTest {

    private static final UUID SESSION_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID PROVIDER_TENANT_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearContext() {
        TenantSettingsRequestContext.clear();
    }

    @Test
    void forwardsMappedTenantOnlyWithTheExactDomainReadPermission() throws Exception {
        mappedTenant(3L);
        TenantSettingsSecurityFilter filter = filter();
        MockHttpServletRequest request = request(
                "/v1/tenant/settings/provider-domains",
                TenantSettingsSecurityFilter.DOMAIN_READ_PERMISSION);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<TenantSettingsRequestContext.Actor> forwarded = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                forwarded.set(TenantSettingsRequestContext.require()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(forwarded.get().authTenantId()).isEqualTo(3L);
        assertThat(forwarded.get().providerTenantId()).isEqualTo(PROVIDER_TENANT_ID);
        assertThat(forwarded.get().permissions())
                .containsExactly(TenantSettingsSecurityFilter.DOMAIN_READ_PERMISSION);
        assertThatThrownByMissingContext();
    }

    @Test
    void roleNameDoesNotAuthorizeAProjectionWithoutItsResourcePermission() throws Exception {
        TenantSettingsSecurityFilter filter = filter();
        MockHttpServletRequest request = request(
                "/v1/tenant/settings/provider-domains", null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("TENANT_ADMIN alone must not authorize the owner projection");
        });

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void forwardsGovernanceReadOnlyWithItsExactPermission() throws Exception {
        mappedTenant(3L);
        TenantSettingsSecurityFilter filter = filter();
        MockHttpServletRequest request = request(
                "/v1/tenant/settings/data-governance-observation",
                TenantSettingsSecurityFilter.GOVERNANCE_READ_PERMISSION);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<TenantSettingsRequestContext.Actor> forwarded = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                forwarded.set(TenantSettingsRequestContext.require()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(forwarded.get().permissions())
                .containsExactly(TenantSettingsSecurityFilter.GOVERNANCE_READ_PERMISSION);
        assertThatThrownByMissingContext();
    }

    @Test
    void forwardsPlanEligibilityOnlyWithExactAppGovernanceViewPermission() throws Exception {
        mappedTenant(3L);
        TenantSettingsSecurityFilter filter = filter();
        MockHttpServletRequest request = request(
                "/v1/tenant/settings/plan-eligibility",
                TenantSettingsSecurityFilter.PLAN_READ_PERMISSION);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<TenantSettingsRequestContext.Actor> forwarded = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                forwarded.set(TenantSettingsRequestContext.require()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(forwarded.get().permissions())
                .containsExactly(TenantSettingsSecurityFilter.PLAN_READ_PERMISSION);
        assertThatThrownByMissingContext();
    }

    @Test
    void anotherAppGovernanceActionCannotReadPlanEligibility() throws Exception {
        TenantSettingsSecurityFilter filter = filter();
        MockHttpServletRequest request = request(
                "/v1/tenant/settings/plan-eligibility", "ADMIN.APP_GOVERNANCE:MANAGE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("a different action must not authorize the plan read");
        });

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void requiresTheTrustedServiceTokenBeforeReadingForwardedIdentity() throws Exception {
        TenantSettingsSecurityFilter filter = filter();
        MockHttpServletRequest request = request(
                "/v1/tenant/settings/provider-domains",
                TenantSettingsSecurityFilter.DOMAIN_READ_PERMISSION);
        request.removeHeader("X-DWP-Service-Token");
        request.addHeader("X-DWP-Service-Token", "untrusted");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("untrusted callers must not reach tenant mapping");
        });

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void requiresTheVerifiedSessionBinding() throws Exception {
        TenantSettingsSecurityFilter filter = filter();
        MockHttpServletRequest request = request(
                "/v1/tenant/settings/provider-domains",
                TenantSettingsSecurityFilter.DOMAIN_READ_PERMISSION);
        request.removeHeader("X-DWP-Auth-Session-ID");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("a request without session binding must be denied");
        });

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void doesNotTreatAnotherResourcePermissionAsEquivalent() throws Exception {
        TenantSettingsSecurityFilter filter = filter();
        MockHttpServletRequest request = request(
                "/v1/tenant/settings/data-governance-observation",
                "ADMIN.AUDIT_CONFIGURE:MANAGE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("a different action or resource must not authorize this read");
        });

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void blocksProviderIdentityBeforeTenantMapping() throws Exception {
        TenantSettingsSecurityFilter filter = filter();
        MockHttpServletRequest request = request(
                "/v1/tenant/settings/provider-domains",
                TenantSettingsSecurityFilter.DOMAIN_READ_PERMISSION);
        request.removeHeader("X-DWP-Identity-Plane");
        request.addHeader("X-DWP-Identity-Plane", "PROVIDER");
        request.removeHeader("X-DWP-Roles");
        request.addHeader("X-DWP-Roles", "PROVIDER_ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("provider identities must not enter tenant projections");
        });

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void blocksResolvedSupportContextEvenWhenTenantHeadersArePresent() throws Exception {
        TenantSettingsSecurityFilter filter = filter();
        MockHttpServletRequest request = request(
                "/v1/tenant/settings/provider-domains",
                TenantSettingsSecurityFilter.DOMAIN_READ_PERMISSION);
        request.addHeader("X-DWP-Support-Session-ID", UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("support context must not borrow tenant-plane projection access");
        });

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void failsClosedWhenTheAuthTenantHasNoActiveProviderMapping() throws Exception {
        mappedTenant(9L);
        TenantSettingsSecurityFilter filter = filter();
        MockHttpServletRequest request = request(
                "/v1/tenant/settings/data-governance-observation",
                TenantSettingsSecurityFilter.GOVERNANCE_READ_PERMISSION);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("an unmapped tenant must not reach the projection");
        });

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void mappedTenant(long authTenantId) {
        when(jdbc.query(
                anyString(),
                any(ResultSetExtractor.class),
                eq(authTenantId))).thenReturn(PROVIDER_TENANT_ID);
    }

    private TenantSettingsSecurityFilter filter() {
        return new TenantSettingsSecurityFilter("trusted-provider", jdbc, objectMapper);
    }

    private MockHttpServletRequest request(String path, String permissions) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-DWP-Service-Token", "trusted-provider");
        request.addHeader("X-DWP-User-ID", "17");
        request.addHeader("X-DWP-Tenant-ID", "3");
        request.addHeader("X-DWP-Roles", "TENANT_ADMIN");
        if (permissions != null) request.addHeader("X-DWP-Permissions", permissions);
        request.addHeader("X-DWP-Auth-Session-ID", SESSION_ID.toString());
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        return request;
    }

    private void assertThatThrownByMissingContext() {
        org.assertj.core.api.Assertions.assertThatThrownBy(TenantSettingsRequestContext::require)
                .isInstanceOf(IllegalStateException.class);
    }
}
