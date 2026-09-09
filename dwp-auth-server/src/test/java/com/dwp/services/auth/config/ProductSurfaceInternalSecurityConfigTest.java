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

    @Test
    void acceptsOnlyTheDedicatedMeetingTokenOnTheExactFollowupEndpoint() throws Exception {
        var filter = new ProductSurfaceInternalSecurityConfig.ProductSurfaceTokenFilter(
                "trusted-product-surface-token",
                "trusted-meeting-followup-token",
                objectMapper);
        MockHttpServletRequest accepted = new MockHttpServletRequest(
                "POST", ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_PATH);
        accepted.addHeader(ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_TOKEN_HEADER,
                "trusted-meeting-followup-token");
        accepted.addHeader(ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY);
        MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();

        filter.doFilter(accepted, acceptedResponse, new MockFilterChain());

        assertThat(acceptedResponse.getStatus()).isEqualTo(200);

        for (String path : new String[] {
                "/internal/auth/v1/product-surface-authority/evaluate",
                "/internal/auth/v1/governed-route-authority/evaluate"}) {
            MockHttpServletRequest wrongPath = new MockHttpServletRequest("POST", path);
            wrongPath.addHeader(
                    ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_TOKEN_HEADER,
                    "trusted-meeting-followup-token");
            wrongPath.addHeader(ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                    ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(wrongPath, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(401);
        }
    }

    @Test
    void doesNotAcceptGatewayAndMeetingTokensAcrossServiceIdentities() throws Exception {
        var filter = new ProductSurfaceInternalSecurityConfig.ProductSurfaceTokenFilter(
                "trusted-product-surface-token",
                "trusted-meeting-followup-token",
                objectMapper);
        MockHttpServletRequest meetingWithGatewayToken = new MockHttpServletRequest(
                "POST", ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_PATH);
        meetingWithGatewayToken.addHeader(ProductSurfaceInternalSecurityConfig.TOKEN_HEADER,
                "trusted-product-surface-token");
        meetingWithGatewayToken.addHeader(
                ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY);
        MockHttpServletResponse meetingResponse = new MockHttpServletResponse();

        filter.doFilter(meetingWithGatewayToken, meetingResponse, new MockFilterChain());

        assertThat(meetingResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest gatewayWithMeetingToken = new MockHttpServletRequest(
                "POST", ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_PATH);
        gatewayWithMeetingToken.addHeader(
                ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_TOKEN_HEADER,
                "trusted-meeting-followup-token");
        gatewayWithMeetingToken.addHeader(
                ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                ProductSurfaceInternalSecurityConfig.GATEWAY_SERVICE_IDENTITY);
        MockHttpServletResponse gatewayResponse = new MockHttpServletResponse();

        filter.doFilter(gatewayWithMeetingToken, gatewayResponse, new MockFilterChain());

        assertThat(gatewayResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest gatewayWithGatewayToken = new MockHttpServletRequest(
                "POST", ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_PATH);
        gatewayWithGatewayToken.addHeader(ProductSurfaceInternalSecurityConfig.TOKEN_HEADER,
                "trusted-product-surface-token");
        gatewayWithGatewayToken.addHeader(
                ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                ProductSurfaceInternalSecurityConfig.GATEWAY_SERVICE_IDENTITY);
        MockHttpServletResponse dedicatedPathResponse = new MockHttpServletResponse();

        filter.doFilter(
                gatewayWithGatewayToken, dedicatedPathResponse, new MockFilterChain());

        assertThat(dedicatedPathResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsAmbiguousDedicatedCredentialsAndNonPostRequests() throws Exception {
        var filter = new ProductSurfaceInternalSecurityConfig.ProductSurfaceTokenFilter(
                "trusted-product-surface-token",
                "trusted-meeting-followup-token",
                objectMapper);
        MockHttpServletRequest duplicate = new MockHttpServletRequest(
                "POST", ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_PATH);
        duplicate.addHeader(
                ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_TOKEN_HEADER,
                "trusted-meeting-followup-token");
        duplicate.addHeader(
                ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_TOKEN_HEADER,
                "trusted-meeting-followup-token");
        duplicate.addHeader(ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY);
        MockHttpServletResponse duplicateResponse = new MockHttpServletResponse();

        filter.doFilter(duplicate, duplicateResponse, new MockFilterChain());

        assertThat(duplicateResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest mixed = new MockHttpServletRequest(
                "POST", ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_PATH);
        mixed.addHeader(
                ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_TOKEN_HEADER,
                "trusted-meeting-followup-token");
        mixed.addHeader(
                ProductSurfaceInternalSecurityConfig.TOKEN_HEADER,
                "trusted-product-surface-token");
        mixed.addHeader(ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY);
        MockHttpServletResponse mixedResponse = new MockHttpServletResponse();

        filter.doFilter(mixed, mixedResponse, new MockFilterChain());

        assertThat(mixedResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest gatewayMixed = new MockHttpServletRequest(
                "POST", "/internal/auth/v1/product-surface-authority/evaluate");
        gatewayMixed.addHeader(
                ProductSurfaceInternalSecurityConfig.TOKEN_HEADER,
                "trusted-product-surface-token");
        gatewayMixed.addHeader(
                ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_TOKEN_HEADER,
                "trusted-meeting-followup-token");
        gatewayMixed.addHeader(ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                ProductSurfaceInternalSecurityConfig.GATEWAY_SERVICE_IDENTITY);
        MockHttpServletResponse gatewayMixedResponse = new MockHttpServletResponse();

        filter.doFilter(gatewayMixed, gatewayMixedResponse, new MockFilterChain());

        assertThat(gatewayMixedResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest get = new MockHttpServletRequest(
                "GET", ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_PATH);
        get.addHeader(ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_TOKEN_HEADER,
                "trusted-meeting-followup-token");
        get.addHeader(ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY);
        MockHttpServletResponse getResponse = new MockHttpServletResponse();

        filter.doFilter(get, getResponse, new MockFilterChain());

        assertThat(getResponse.getStatus()).isEqualTo(401);
    }
}
