package com.dwp.services.platform.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
}
