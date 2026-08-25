package com.dwp.services.auth.config;

import com.dwp.observability.api.ApiHistoryAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ProductAuthorizationOperationsSecurityConfigTest {

    private static final String APPROVAL_TOKEN = "provider-approval-secret";
    private static final String ACTIVATION_TOKEN = "platform-activation-secret";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void providerIdentityAndPurposeTokenCanReachOnlyTheApprovalLane() throws Exception {
        var filter = filter(APPROVAL_TOKEN, ACTIVATION_TOKEN);

        assertAccepted(filter, "/bundles/product-surfaces/versions/3/approval",
                ProductAuthorizationOperationsSecurityConfig.PROVIDER_SERVICE_IDENTITY,
                ProductAuthorizationOperationsSecurityConfig.APPROVAL_TOKEN_HEADER,
                APPROVAL_TOKEN);
        assertRejected(filter, "/bundles/product-surfaces/versions/3/activation",
                ProductAuthorizationOperationsSecurityConfig.PROVIDER_SERVICE_IDENTITY,
                ProductAuthorizationOperationsSecurityConfig.APPROVAL_TOKEN_HEADER,
                APPROVAL_TOKEN);
    }

    @Test
    void platformIdentityAndPurposeTokenCanReachActivationAndRollbackOnly() throws Exception {
        var filter = filter(APPROVAL_TOKEN, ACTIVATION_TOKEN);

        assertAccepted(filter, "/bundles/product-surfaces/versions/3/activation",
                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY,
                ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                ACTIVATION_TOKEN);
        assertAccepted(filter, "/bundles/product-surfaces/versions/2/rollback",
                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY,
                ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                ACTIVATION_TOKEN);
        assertAccepted(filter, "GET", "/bundles/product-surfaces/active",
                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY,
                ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                ACTIVATION_TOKEN);
        assertAccepted(filter, "GET", "/bundles/product-surfaces/versions/3",
                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY,
                ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                ACTIVATION_TOKEN);
        assertRejected(filter, "/bundles/product-surfaces/versions/3/approval",
                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY,
                ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                ACTIVATION_TOKEN);
    }

    @Test
    void failsClosedForMissingConfigurationWrongIdentityDuplicateHeadersAndUnknownPaths()
            throws Exception {
        assertRejected(filter("", ""),
                "/bundles/product-surfaces/versions/3/approval",
                ProductAuthorizationOperationsSecurityConfig.PROVIDER_SERVICE_IDENTITY,
                ProductAuthorizationOperationsSecurityConfig.APPROVAL_TOKEN_HEADER,
                "anything");
        assertRejected(filter(APPROVAL_TOKEN, ACTIVATION_TOKEN),
                "/bundles/product-surfaces/versions/3/activation",
                "dwp-gateway",
                ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                ACTIVATION_TOKEN);
        assertRejected(filter(APPROVAL_TOKEN, ACTIVATION_TOKEN),
                "/bundles/product-surfaces/versions/3/unknown",
                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY,
                ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                ACTIVATION_TOKEN);

        var filter = filter(APPROVAL_TOKEN, ACTIVATION_TOKEN);
        MockHttpServletRequest duplicate = request(
                "/bundles/product-surfaces/versions/3/approval",
                ProductAuthorizationOperationsSecurityConfig.PROVIDER_SERVICE_IDENTITY,
                ProductAuthorizationOperationsSecurityConfig.APPROVAL_TOKEN_HEADER,
                APPROVAL_TOKEN);
        duplicate.addHeader(
                ProductAuthorizationOperationsSecurityConfig.APPROVAL_TOKEN_HEADER,
                APPROVAL_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(duplicate, response, new MockFilterChain());

        MockHttpServletRequest mixedLane = request(
                "/bundles/product-surfaces/versions/3/approval",
                ProductAuthorizationOperationsSecurityConfig.PROVIDER_SERVICE_IDENTITY,
                ProductAuthorizationOperationsSecurityConfig.APPROVAL_TOKEN_HEADER,
                APPROVAL_TOKEN);
        mixedLane.addHeader(
                ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                ACTIVATION_TOKEN);
        MockHttpServletResponse mixedResponse = new MockHttpServletResponse();
        filter.doFilter(mixedLane, mixedResponse, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(mixedResponse.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain(APPROVAL_TOKEN);
    }

    @Test
    void refusesASecretThatCollapsesTheIndependentLanes() {
        assertThatIllegalStateException()
                .isThrownBy(() -> filter("same-secret", " same-secret "))
                .withMessageContaining("must differ");
    }

    @Test
    void publishesOnlyVerifiedWorkloadProvenanceToApiHistory() throws Exception {
        var filter = filter(APPROVAL_TOKEN, ACTIVATION_TOKEN);
        MockHttpServletRequest accepted = request(
                "/bundles/product-surfaces/versions/3/approval",
                ProductAuthorizationOperationsSecurityConfig.PROVIDER_SERVICE_IDENTITY,
                ProductAuthorizationOperationsSecurityConfig.APPROVAL_TOKEN_HEADER,
                APPROVAL_TOKEN);
        MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();
        filter.doFilter(accepted, acceptedResponse, new MockFilterChain());

        assertThat(accepted.getAttribute(ApiHistoryAttributes.ACTOR_TYPE)).isEqualTo("SERVICE");
        assertThat(accepted.getAttribute(ApiHistoryAttributes.ACTOR_ID)).isEqualTo(
                ProductAuthorizationOperationsSecurityConfig.PROVIDER_SERVICE_IDENTITY);
        assertThat(accepted.getAttribute(ApiHistoryAttributes.AUTH_TYPE)).isEqualTo("SERVICE");

        MockHttpServletRequest rejected = request(
                "/bundles/product-surfaces/versions/3/activation",
                ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY,
                ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                "wrong-token");
        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
        filter.doFilter(rejected, rejectedResponse, new MockFilterChain());

        assertThat(rejectedResponse.getStatus()).isEqualTo(401);
        assertThat(rejected.getAttribute(ApiHistoryAttributes.ACTOR_TYPE)).isNull();
        assertThat(rejected.getAttribute(ApiHistoryAttributes.ACTOR_ID)).isNull();
        assertThat(rejected.getAttribute(ApiHistoryAttributes.AUTH_TYPE)).isNull();
        assertThat(rejectedResponse.getContentAsString()).doesNotContain("wrong-token");
    }

    private ProductAuthorizationOperationsSecurityConfig.ProductAuthorizationOperationsTokenFilter
            filter(String approvalToken, String activationToken) {
        return new ProductAuthorizationOperationsSecurityConfig
                .ProductAuthorizationOperationsTokenFilter(
                        approvalToken, activationToken, objectMapper);
    }

    private void assertAccepted(
            ProductAuthorizationOperationsSecurityConfig.ProductAuthorizationOperationsTokenFilter filter,
            String suffix,
            String identity,
            String tokenHeader,
            String token) throws Exception {
        assertAccepted(filter, "POST", suffix, identity, tokenHeader, token);
    }

    private void assertAccepted(
            ProductAuthorizationOperationsSecurityConfig.ProductAuthorizationOperationsTokenFilter filter,
            String method,
            String suffix,
            String identity,
            String tokenHeader,
            String token) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                request(method, suffix, identity, tokenHeader, token),
                response,
                new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private void assertRejected(
            ProductAuthorizationOperationsSecurityConfig.ProductAuthorizationOperationsTokenFilter filter,
            String suffix,
            String identity,
            String tokenHeader,
            String token) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                request(suffix, identity, tokenHeader, token),
                response,
                new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    private MockHttpServletRequest request(
            String suffix,
            String identity,
            String tokenHeader,
            String token) {
        return request("POST", suffix, identity, tokenHeader, token);
    }

    private MockHttpServletRequest request(
            String method,
            String suffix,
            String identity,
            String tokenHeader,
            String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                method,
                ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_PREFIX + suffix);
        request.addHeader(
                ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                identity);
        request.addHeader(tokenHeader, token);
        return request;
    }
}
