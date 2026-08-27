package com.dwp.services.platform.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderProvisioningSecurityFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void protectsTheInternalProductCatalogWithProviderServiceIdentity() throws Exception {
        ProviderProvisioningSecurityFilter filter =
                new ProviderProvisioningSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/internal/provider/v1/code-catalog/code-sets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void acceptsTheTrustedProviderServiceForTheInternalProductCatalog() throws Exception {
        ProviderProvisioningSecurityFilter filter =
                new ProviderProvisioningSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/internal/provider/v1/code-catalog/code-sets");
        request.addHeader("X-DWP-Provisioning-Token", "trusted");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void excludesTheExactWidgetRegistryPlaneFromGenericProvisioningAuthentication() throws Exception {
        ProviderProvisioningSecurityFilter filter =
                new ProviderProvisioningSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/internal/provider/v1/widget-registry/definitions");
        request.addHeader("X-DWP-Provisioning-Token", "trusted");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(filter.shouldNotFilter(request)).isTrue();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void keepsWidgetRegistryPrefixLookalikesBehindGenericProvisioningAuthentication() throws Exception {
        ProviderProvisioningSecurityFilter filter =
                new ProviderProvisioningSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/internal/provider/v1/widget-registry-lookalike/definitions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(filter.shouldNotFilter(request)).isFalse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsAmbiguousInternalProviderPathsEvenWithTheStaticToken() throws Exception {
        ProviderProvisioningSecurityFilter filter =
                new ProviderProvisioningSecurityFilter("trusted", objectMapper);
        List<String> paths = List.of(
                "/internal/provider/v1/widget-registry;matrix/definitions",
                "/internal/provider/v1/widget%2Dregistry/definitions",
                "/internal/provider/v1/widget-registry%2Fdefinitions",
                "/internal/provider/v1/widget-registry\\definitions",
                "/internal/provider//v1/widget-registry/definitions",
                "/internal/provider/./v1/widget-registry/definitions",
                "/safe/../internal/provider/v1/widget-registry/definitions",
                "/%69nternal/provider/v1/widget-registry/definitions");

        for (String path : paths) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.addHeader("X-DWP-Provisioning-Token", "trusted");
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertThat(filter.shouldNotFilter(request)).as(path).isFalse();
            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).as(path).isEqualTo(400);
            assertThat(response.getContentAsString()).as(path)
                    .contains("A canonical internal Provider path is required.");
        }
    }
}
