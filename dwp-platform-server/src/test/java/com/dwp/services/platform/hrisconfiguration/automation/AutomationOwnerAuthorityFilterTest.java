package com.dwp.services.platform.hrisconfiguration.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationOwnerAuthorityFilterTest {

    private static final String SERVICE_TOKEN =
            "gateway-service-token-for-sys006-tests";
    private static final String ID =
            "00000000-0000-4000-8000-000000000606";
    private static final List<String> PATHS = List.of(
            "/v1/admin/hris/automations",
            "/v1/admin/hris/automations",
            "/v1/admin/hris/automations/" + ID + "/runs",
            "/v1/admin/hris/automation-runs/" + ID,
            "/v1/admin/hris/automation-runs/" + ID + "/retry",
            "/v1/admin/hris/automations/" + ID + "/versions",
            "/v1/admin/hris/automations/" + ID + "/versions/7/validate",
            "/v1/admin/hris/automations/" + ID + "/versions/7/submit",
            "/v1/admin/hris/automations/" + ID + "/versions/7/approve",
            "/v1/admin/hris/automations/" + ID + "/versions/7/activate",
            "/v1/admin/hris/automations/" + ID + "/schedules",
            "/v1/admin/hris/automation-schedules/" + ID + "/submit",
            "/v1/admin/hris/automation-schedules/" + ID + "/approve",
            "/v1/admin/hris/automation-schedules/" + ID + "/activate",
            "/v1/admin/hris/automation-schedules/" + ID + "/suspend",
            "/v1/admin/hris/automation-runs/" + ID + "/cancel");

    private final AutomationOwnerAuthorityFilter filter =
            new AutomationOwnerAuthorityFilter(
                    SERVICE_TOKEN, new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void allSixteenExactGatewayBindingsAttachClosedOwnerAuthority() throws Exception {
        List<AutomationOwnerAuthorityFilter.Binding> bindings =
                AutomationOwnerAuthorityFilter.bindingContracts();
        assertThat(bindings).hasSize(16);

        for (int index = 0; index < bindings.size(); index++) {
            var binding = bindings.get(index);
            MockHttpServletRequest request = trustedRequest(
                    binding, PATHS.get(index), binding.stepUp() ? "ELEVATED" : "NORMAL");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).as(binding.routeKey()).isEqualTo(200);
            assertThat(chain.getRequest()).as(binding.routeKey()).isSameAs(request);
            assertThat(request.getAttribute(
                    AutomationOwnerAuthorityFilter.AUTHORITY_ATTRIBUTE))
                    .as(binding.routeKey())
                    .isInstanceOf(AutomationOwnerAuthorityFilter.Authority.class);
        }
    }

    @Test
    void highRiskBindingRejectsNormalAndAcceptsOnlyTrustedElevatedMode()
            throws Exception {
        var binding = AutomationOwnerAuthorityFilter.bindingContracts().get(2);

        MockHttpServletRequest normal = trustedRequest(binding, PATHS.get(2), "NORMAL");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        MockFilterChain deniedChain = new MockFilterChain();
        filter.doFilter(normal, denied, deniedChain);
        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(deniedChain.getRequest()).isNull();

        MockHttpServletRequest elevated = trustedRequest(
                binding, PATHS.get(2), "ELEVATED");
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        MockFilterChain allowedChain = new MockFilterChain();
        filter.doFilter(elevated, allowed, allowedChain);
        assertThat(allowed.getStatus()).isEqualTo(200);
        assertThat(allowedChain.getRequest()).isSameAs(elevated);
    }

    @Test
    void forgedRawChallengeAndAnyExactRouteMismatchFailBeforeTheService()
            throws Exception {
        var binding = AutomationOwnerAuthorityFilter.bindingContracts().get(15);
        MockHttpServletRequest forged = trustedRequest(
                binding, PATHS.get(15), "ELEVATED");
        forged.addHeader("X-DWP-Step-Up-Challenge", "browser-controlled-proof");
        MockHttpServletResponse forgedResponse = new MockHttpServletResponse();
        MockFilterChain forgedChain = new MockFilterChain();
        filter.doFilter(forged, forgedResponse, forgedChain);
        assertThat(forgedResponse.getStatus()).isEqualTo(400);
        assertThat(forgedChain.getRequest()).isNull();

        MockHttpServletRequest mismatch = trustedRequest(
                binding, PATHS.get(15), "ELEVATED");
        mismatch.removeHeader("X-DWP-Route-Contract-Key");
        mismatch.addHeader("X-DWP-Route-Contract-Key", "route.substituted");
        MockHttpServletResponse mismatchResponse = new MockHttpServletResponse();
        MockFilterChain mismatchChain = new MockFilterChain();
        filter.doFilter(mismatch, mismatchResponse, mismatchChain);
        assertThat(mismatchResponse.getStatus()).isEqualTo(403);
        assertThat(mismatchChain.getRequest()).isNull();
    }

    @Test
    void populationIsDerivedFromForwardedFactsAndBigintIdentityIsNotJsonBounded()
            throws Exception {
        var binding = AutomationOwnerAuthorityFilter.bindingContracts().getFirst();
        MockHttpServletRequest first = trustedRequest(binding, PATHS.getFirst(), "NORMAL");
        first.removeHeader("X-DWP-Tenant-ID");
        first.addHeader("X-DWP-Tenant-ID", Long.toString(Long.MAX_VALUE));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(first, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        var authority = (AutomationOwnerAuthorityFilter.Authority) first.getAttribute(
                AutomationOwnerAuthorityFilter.AUTHORITY_ATTRIBUTE);
        assertThat(authority.tenantId()).isEqualTo(Long.MAX_VALUE);
        assertThat(authority.populationScopeDigest()).matches("^[a-f0-9]{64}$");
        assertThat(first.getHeader("X-DWP-Population-Scope-Digest")).isNull();
    }

    private static MockHttpServletRequest trustedRequest(
            AutomationOwnerAuthorityFilter.Binding binding,
            String path,
            String accessMode) {
        MockHttpServletRequest request = new MockHttpServletRequest(binding.method(), path);
        var profile = binding.profiles().getFirst();
        String revision = "psr-" + "a".repeat(64);
        request.addHeader("X-DWP-Service-Token", SERVICE_TOKEN);
        request.addHeader("X-DWP-Route-Contract-Key", binding.routeKey());
        request.addHeader("X-DWP-Current-Decision-Revision", revision);
        request.addHeader("X-DWP-Current-Revalidate-At",
                OffsetDateTime.now().plusMinutes(5).toString());
        request.addHeader("X-DWP-Permissions",
                "APP.HCM:VIEW," + profile.permission());
        request.addHeader("X-DWP-Roles", profile.role());
        request.addHeader("X-DWP-Resource-Roles", profile.resourceRole());
        request.addHeader("X-DWP-Tenant-ID", "42");
        request.addHeader("X-DWP-User-ID", "84");
        request.addHeader("X-DWP-Subject-Principal-Public-ID",
                "00000000-0000-4000-8000-000000000607");
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        request.addHeader("X-DWP-Population-Kind", "CONFIG_SCOPE");
        request.addHeader("X-DWP-Context-Key", "psc-" + "b".repeat(64));
        request.addHeader("X-DWP-Context-Scope-Key", "tenant:42");
        request.addHeader("X-DWP-Field-Policy-Revision", "7");
        request.addHeader("X-DWP-Authorization-Revision", "11");
        request.addHeader("X-Correlation-ID", UUID.randomUUID().toString());
        request.addHeader("X-Purpose-Code", "HRIS_SYSTEM_ADMIN");
        request.addHeader("X-DWP-Active-Access-Mode", accessMode);
        if (binding.action()) {
            request.addHeader("X-DWP-Expected-Decision-Revision", revision);
            request.addHeader("Idempotency-Key", "sys006-idempotency-key-0001");
        }
        if (binding.cas()) {
            request.addHeader("X-DWP-Expected-Object-Version", "7");
        }
        return request;
    }
}
