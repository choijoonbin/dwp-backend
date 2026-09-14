package com.dwp.services.auth.config;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApprovalFormUserInternalSecurityConfigTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void onlyExactPostServicePurposeIdentityReachesTheController() throws Exception {
        for (String operation : new String[] {"search", "resolve"}) {
            var request = request("POST", ApprovalFormUserInternalSecurityConfig.PREFIX + '/' + operation);
            assertThat(run(request, "dedicated-token")).isEqualTo(200);
        }
    }

    @Test
    void duplicateCredentialsDeputyProviderAndBorrowedAuthorityNeverReachTheController() throws Exception {
        for (String name : new String[] {ApprovalFormUserInternalSecurityConfig.TOKEN_HEADER, "X-DWP-Service-Identity"}) {
            var request = request("POST", ApprovalFormUserInternalSecurityConfig.PREFIX + "/search");
            request.addHeader(name, request.getHeader(name)); assertThat(run(request, "dedicated-token")).isEqualTo(401);
        }
        for (String borrowed : new String[] {"X-DWP-Identity-Sync-Token", "X-DWP-Tenant-ID", "X-DWP-Permissions", "Authorization"}) {
            var request = request("POST", ApprovalFormUserInternalSecurityConfig.PREFIX + "/search");
            request.addHeader(borrowed, "borrowed"); assertThat(run(request, "dedicated-token")).isEqualTo(401);
        }
        var deputy = request("POST", ApprovalFormUserInternalSecurityConfig.PREFIX + "/search");
        deputy.removeHeader("X-DWP-Service-Identity"); deputy.addHeader("X-DWP-Service-Identity", "dwp-gateway");
        assertThat(run(deputy, "dedicated-token")).isEqualTo(401);
    }

    @Test
    void headPercentAliasesTrailingSlashChildrenAndQueryAreClosed() throws Exception {
        for (String path : new String[] {"/search/", "/search/extra", "/%73earch", "//search", "/SEARCH", ""}) {
            assertThat(run(request("POST", ApprovalFormUserInternalSecurityConfig.PREFIX + path), "dedicated-token")).isEqualTo(403);
        }
        for (String method : new String[] {"GET", "HEAD", "PUT", "DELETE", "OPTIONS"}) {
            assertThat(run(request(method, ApprovalFormUserInternalSecurityConfig.PREFIX + "/search"), "dedicated-token")).isEqualTo(403);
        }
        var query = request("POST", ApprovalFormUserInternalSecurityConfig.PREFIX + "/search"); query.setQueryString("purpose=CREATE");
        assertThat(run(query, "dedicated-token")).isEqualTo(403);
    }

    @Test
    void missingConfigurationAndTokenWhitespaceHaveNoFallback() throws Exception {
        var request = request("POST", ApprovalFormUserInternalSecurityConfig.PREFIX + "/search");
        assertThat(run(request, "")).isEqualTo(401);
        request.removeHeader(ApprovalFormUserInternalSecurityConfig.TOKEN_HEADER);
        request.addHeader(ApprovalFormUserInternalSecurityConfig.TOKEN_HEADER, " dedicated-token ");
        assertThat(run(request, "dedicated-token")).isEqualTo(401);
    }

    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader(ApprovalFormUserInternalSecurityConfig.TOKEN_HEADER, "dedicated-token");
        request.addHeader("X-DWP-Service-Identity", "dwp-approval-server"); return request;
    }

    private int run(MockHttpServletRequest request, String configured) throws Exception {
        var response = new MockHttpServletResponse(); var calls = new AtomicInteger();
        new ApprovalFormUserInternalSecurityConfig.ApprovalFormUserTokenFilter(configured, mapper)
                .doFilter(request, response, (ignored, ignoredResponse) -> calls.incrementAndGet());
        assertThat(calls.get()).isEqualTo(response.getStatus() == 200 ? 1 : 0);
        assertThat(response.getContentAsString()).doesNotContain("dedicated-token"); return response.getStatus();
    }
}
