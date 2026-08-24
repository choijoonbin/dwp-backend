package com.dwp.services.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ProductSurfaceInternalSecurityConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsOnlyThePurposeSpecificTokenAndGatewayIdentity() throws Exception {
        var filter = new ProductSurfaceInternalSecurityConfig.ProductSurfaceTokenFilter(
                "trusted-product-surface-token", objectMapper);
        MockHttpServletRequest accepted = new MockHttpServletRequest(
                "POST", "/internal/auth/v1/product-surface-authority/evaluate");
        accepted.addHeader(ProductSurfaceInternalSecurityConfig.TOKEN_HEADER,
                "trusted-product-surface-token");
        accepted.addHeader(ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                "dwp-gateway");
        MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();

        filter.doFilter(accepted, acceptedResponse, new MockFilterChain());

        assertThat(acceptedResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest rejected = new MockHttpServletRequest(
                "POST", "/internal/auth/v1/product-surface-authority/evaluate");
        rejected.addHeader("X-DWP-Service-Token", "trusted-product-surface-token");
        rejected.addHeader(ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                "dwp-gateway");
        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();

        filter.doFilter(rejected, rejectedResponse, new MockFilterChain());

        assertThat(rejectedResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsMissingOrWrongGatewayIdentityEvenWithTheConfiguredToken() throws Exception {
        var filter = new ProductSurfaceInternalSecurityConfig.ProductSurfaceTokenFilter(
                "trusted-product-surface-token", objectMapper);

        for (String path : new String[] {
                "/internal/auth/v1/product-surface-authority/evaluate",
                "/internal/auth/v1/governed-route-authority/evaluate"}) {
            for (String identity : new String[] {null, "another-service"}) {
                MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
                request.addHeader(ProductSurfaceInternalSecurityConfig.TOKEN_HEADER,
                        "trusted-product-surface-token");
                if (identity != null) {
                    request.addHeader(
                            ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                            identity);
                }
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter.doFilter(request, response, new MockFilterChain());

                assertThat(response.getStatus()).isEqualTo(401);
            }
        }
    }
}
