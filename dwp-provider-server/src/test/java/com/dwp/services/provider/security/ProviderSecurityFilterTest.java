package com.dwp.services.provider.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderSecurityFilterTest {

    private final ProviderOperatorService operatorService = mock(ProviderOperatorService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void rejectsSpoofedRequestsWithoutTheGatewayServiceIdentity() throws Exception {
        ProviderSecurityFilter filter = new ProviderSecurityFilter(
                "trusted-provider", operatorService, objectMapper);
        MockHttpServletRequest request = request("wrong-provider", "PROVIDER_ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
        });

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsTenantAdministratorsWithoutAProviderPersona() throws Exception {
        ProviderRequestContext.Actor actor = actor();
        when(operatorService.activeOperator(3L, 17L)).thenReturn(java.util.Optional.of(actor));
        ProviderSecurityFilter filter = new ProviderSecurityFilter(
                "trusted-provider", operatorService, objectMapper);
        MockHttpServletRequest request = request("trusted-provider", "TENANT_ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
        });

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void requiresBothTheAuthPersonaAndTheActiveProviderOperatorAssignment() throws Exception {
        when(operatorService.activeOperator(3L, 17L)).thenReturn(java.util.Optional.empty());
        ProviderSecurityFilter filter = new ProviderSecurityFilter(
                "trusted-provider", operatorService, objectMapper);
        MockHttpServletRequest request = request("trusted-provider", "PROVIDER_SUPPORT");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
        });

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void forwardsOnlyAnActiveProviderOperatorAndClearsTheRequestContext() throws Exception {
        ProviderRequestContext.Actor actor = actor();
        when(operatorService.activeOperator(3L, 17L)).thenReturn(java.util.Optional.of(actor));
        ProviderSecurityFilter filter = new ProviderSecurityFilter(
                "trusted-provider", operatorService, objectMapper);
        MockHttpServletRequest request = request("trusted-provider", "PROVIDER_SUPPORT");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ProviderRequestContext.Actor> forwarded = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                forwarded.set(ProviderRequestContext.require()));

        assertThat(forwarded.get()).isEqualTo(actor);
        assertThat(ProviderRequestContext.currentUserId()).isEmpty();
    }

    private MockHttpServletRequest request(String serviceToken, String roles) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/overview");
        request.addHeader("X-DWP-Service-Token", serviceToken);
        request.addHeader("X-DWP-User-ID", "17");
        request.addHeader("X-DWP-Tenant-ID", "3");
        request.addHeader("X-DWP-Roles", roles);
        return request;
    }

    private ProviderRequestContext.Actor actor() {
        return new ProviderRequestContext.Actor(
                9L,
                17L,
                3L,
                "Provider support",
                Set.of("PROVIDER_SUPPORT"),
                Set.of("ESTATE_READ", "SUPPORT_SESSION_WRITE"));
    }
}
