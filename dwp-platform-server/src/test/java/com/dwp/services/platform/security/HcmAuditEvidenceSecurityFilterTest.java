package com.dwp.services.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HcmAuditEvidenceSecurityFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PlatformCanaryPepRegistry canary =
            mock(PlatformCanaryPepRegistry.class);
    private final PlatformApprovalsPepRegistry approvals =
            mock(PlatformApprovalsPepRegistry.class);
    private final HcmPlatformPepRegistry hcm = mock(HcmPlatformPepRegistry.class);
    private final PlatformSecurityFilter filter = new PlatformSecurityFilter(
            "trusted", "runtime", false, objectMapper, canary, approvals, hcm);

    @BeforeEach
    void stubClosedReadRoute() {
        when(hcm.governsPathFamily(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0)
                        .startsWith("/v1/admin/audit-control/events"));
        when(hcm.authorize(any())).thenAnswer(invocation -> {
            HcmPlatformPepRegistry.RequestEvidence evidence = invocation.getArgument(0);
            return new HcmPlatformPepRegistry.Decision(
                    true,
                    null,
                    new HcmPlatformPepRegistry.RouteAuthority(
                            evidence.trustedRouteContractKey(),
                            "PAGE",
                            "GET",
                            "/v1/admin/audit-control/events",
                            "hcm-audit-evidence-reader",
                            true,
                            "hcm.audit.evidence.view",
                            "STEPUP-MGMT-HIGH-V1",
                            null));
        });
    }

    @Test
    void eligibleNormalRequestReceivesChallengeOnlyAfterTheWholeIntersection() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = invoke(request);

        assertError(response, 403, "STEP_UP_REQUIRED");
        assertNoStore(response);
    }

    @Test
    void missingAuthorityAndEverySodConflictWinOverTheNormalChallenge() throws Exception {
        MockHttpServletRequest unauthorized = request();
        unauthorized.removeHeader(PlatformSecurityHeaders.PERMISSIONS);
        unauthorized.addHeader(PlatformSecurityHeaders.PERMISSIONS,
                "ADMIN.AUDIT_VIEW:VIEW");
        assertError(invoke(unauthorized), 403, "E2001");

        for (String conflict : HcmAuditEvidenceSecurityPolicy.CONFLICTING_PACKAGES) {
            MockHttpServletRequest sod = request();
            sod.removeHeader(PlatformSecurityHeaders.ROLES);
            sod.addHeader(PlatformSecurityHeaders.ROLES,
                    "HRIS_AUDITOR," + conflict);
            assertError(invoke(sod), 403, "SOD_CONFLICT");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "INVALID_QUERY,400,E1001",
            "UNAUTHORIZED,401,E2000",
            "FORBIDDEN,403,E2001",
            "UNAVAILABLE,503,AUTHORITY_RESOLUTION_UNAVAILABLE"
    })
    void everyHcmFilterRejectionIsPrivateNoStore(
            String mutation, int status, String errorCode) throws Exception {
        MockHttpServletRequest request = request();
        switch (mutation) {
            case "INVALID_QUERY" -> {
                request.setParameter("actor", "hidden");
                request.setQueryString("product=HCM&actor=hidden");
            }
            case "UNAUTHORIZED" ->
                    request.removeHeader(PlatformSecurityHeaders.SERVICE_TOKEN);
            case "FORBIDDEN" -> {
                request.removeHeader(PlatformSecurityHeaders.PERMISSIONS);
                request.addHeader(PlatformSecurityHeaders.PERMISSIONS,
                        "ADMIN.AUDIT_VIEW:VIEW");
            }
            case "UNAVAILABLE" ->
                    request.removeHeader(PlatformSecurityHeaders.ROLLOUT_REVISION);
            default -> throw new IllegalArgumentException(mutation);
        }

        MockHttpServletResponse response = invoke(request);

        assertError(response, status, errorCode);
        assertNoStore(response);
    }

    private MockHttpServletResponse invoke(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/admin/audit-control/events");
        request.setParameter("product", "HCM");
        request.setQueryString("product=HCM");
        request.addHeader(PlatformSecurityHeaders.SERVICE_TOKEN, "trusted");
        request.addHeader(PlatformSecurityHeaders.USER, "101");
        request.addHeader(PlatformSecurityHeaders.TENANT, "7");
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        request.addHeader(PlatformSecurityHeaders.ROLES, "HRIS_AUDITOR");
        request.addHeader(PlatformSecurityHeaders.PERMISSIONS,
                "APP.HCM:VIEW,ADMIN.AUDIT_VIEW:VIEW");
        request.addHeader(PlatformSecurityHeaders.RESOURCE_ROLES,
                "HRIS_DUTY_AUDITOR@RS_HCM_CONFIG");
        request.addHeader(PlatformSecurityHeaders.PURPOSE,
                HcmAuditEvidenceSecurityPolicy.PURPOSE);
        request.addHeader(PlatformSecurityHeaders.ACTIVE_ACCESS_MODE, "NORMAL");
        request.addHeader(PlatformSecurityHeaders.SUBJECT_PRINCIPAL_PUBLIC_ID,
                "018f47a2-7b3c-7def-8abc-0123456789ab");
        request.addHeader(PlatformSecurityHeaders.FIELD_POLICY_REVISION, "17");
        request.addHeader(PlatformSecurityHeaders.AUTHORIZATION_REVISION, "29");
        request.addHeader(PlatformSecurityHeaders.ROLLOUT_STATE, "100");
        request.addHeader(PlatformSecurityHeaders.ROLLOUT_REVISION,
                "rollout-" + "0123456789abcdef".repeat(4));
        request.addHeader(PlatformSecurityHeaders.ROLLOUT_COHORT, "baseline");
        request.addHeader(PlatformSecurityHeaders.ROUTE_CONTRACT,
                HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE);
        request.addHeader(PlatformSecurityHeaders.CURRENT_DECISION_REVISION,
                "psr-" + "0123456789abcdef".repeat(4));
        request.addHeader(PlatformSecurityHeaders.CURRENT_REVALIDATE_AT,
                "2035-01-01T00:00:00Z");
        request.addHeader(PlatformSecurityHeaders.CONTEXT,
                HcmAuditEvidenceSecurityPolicy.CONTEXT);
        request.addHeader(PlatformSecurityHeaders.SCOPE,
                "hcm-scope-" + "b".repeat(40));
        return request;
    }

    private void assertError(
            MockHttpServletResponse response, int status, String errorCode) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"errorCode\":\"" + errorCode + "\"");
    }

    private void assertNoStore(MockHttpServletResponse response) {
        assertThat(response.getHeader("Cache-Control"))
                .isEqualTo("private, no-store, max-age=0");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
    }
}
