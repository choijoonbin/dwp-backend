package com.dwp.services.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRecoveryInternalSecurityConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsOnlyThePurposeSpecificTokenAndExactApprovalServiceIdentity()
            throws Exception {
        var filter = new ApprovalRecoveryInternalSecurityConfig.ApprovalRecoveryTokenFilter(
                "trusted-approval-recovery-token", objectMapper);
        MockHttpServletRequest request = request(
                "trusted-approval-recovery-token", "dwp-approval-server");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMissingWrongOrGenericTokensInConstantTimeComparisonPath()
            throws Exception {
        var filter = new ApprovalRecoveryInternalSecurityConfig.ApprovalRecoveryTokenFilter(
                "trusted-approval-recovery-token", objectMapper);

        for (String token : new String[] {null, "wrong-token", " trusted-approval-recovery-token "}) {
            MockHttpServletRequest request = request(token, "dwp-approval-server");
            if (token == null) request.addHeader("X-DWP-Service-Token", "generic-token");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString())
                    .contains("E2000")
                    .doesNotContain("trusted-approval-recovery-token");
        }
    }

    @Test
    void rejectsMissingOrWrongServiceIdentityEvenWithTheRightToken() throws Exception {
        var filter = new ApprovalRecoveryInternalSecurityConfig.ApprovalRecoveryTokenFilter(
                "trusted-approval-recovery-token", objectMapper);

        for (String identity : new String[] {null, "dwp-gateway", "DWP-APPROVAL-SERVER"}) {
            MockHttpServletRequest request = request(
                    "trusted-approval-recovery-token", identity);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(401);
        }
    }

    @Test
    void failsClosedWhenTheDedicatedTokenIsNotConfigured() throws Exception {
        var filter = new ApprovalRecoveryInternalSecurityConfig.ApprovalRecoveryTokenFilter(
                "", objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request("anything", "dwp-approval-server"),
                response,
                new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsDuplicateSecurityHeaders() throws Exception {
        var filter = new ApprovalRecoveryInternalSecurityConfig.ApprovalRecoveryTokenFilter(
                "trusted-approval-recovery-token", objectMapper);
        MockHttpServletRequest duplicateToken = request(
                "trusted-approval-recovery-token", "dwp-approval-server");
        duplicateToken.addHeader(
                ApprovalRecoveryInternalSecurityConfig.TOKEN_HEADER,
                "trusted-approval-recovery-token");
        MockHttpServletResponse tokenResponse = new MockHttpServletResponse();
        filter.doFilter(duplicateToken, tokenResponse, new MockFilterChain());

        MockHttpServletRequest duplicateIdentity = request(
                "trusted-approval-recovery-token", "dwp-approval-server");
        duplicateIdentity.addHeader(
                ApprovalRecoveryInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                "dwp-approval-server");
        MockHttpServletResponse identityResponse = new MockHttpServletResponse();
        filter.doFilter(duplicateIdentity, identityResponse, new MockFilterChain());

        assertThat(tokenResponse.getStatus()).isEqualTo(401);
        assertThat(identityResponse.getStatus()).isEqualTo(401);
    }

    private MockHttpServletRequest request(String token, String identity) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", ApprovalRecoveryInternalSecurityConfig.INTERNAL_PATH);
        if (token != null) {
            request.addHeader(ApprovalRecoveryInternalSecurityConfig.TOKEN_HEADER, token);
        }
        if (identity != null) {
            request.addHeader(
                    ApprovalRecoveryInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                    identity);
        }
        return request;
    }
}
