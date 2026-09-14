package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.*;
import com.dwp.services.approval.document.ApprovalDocumentEndpointPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApprovalDocumentV7ActivationFenceTest {
    @Test
    void everyNewDocumentOperationRemainsClosedAgainstActualImmutableV7InAllFourStates() throws Exception {
        for (String state : new String[] {"000", "100", "110", "111"}) {
            for (var endpoint : ApprovalDocumentEndpointPolicy.ENDPOINTS) {
                var request = new MockHttpServletRequest(endpoint.method(), endpoint.path()
                        .replace("{id}", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
                        .replace("{policyId}", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
                request.addHeader("X-DWP-Service-Token", "trusted"); request.addHeader("X-DWP-User-ID", "20");
                request.addHeader("X-DWP-Tenant-ID", "10"); request.addHeader("X-DWP-Roles", "WORKSPACE_MEMBER");
                request.addHeader("X-DWP-Permissions", "APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:VIEW,ACTION.APPROVAL_REQUEST:UPDATE,ACTION.APPROVAL_REQUEST:EXPORT,ACTION.APPROVAL_TASK:VIEW,ACTION.APPROVAL_TASK:UPDATE,ACTION.APPROVAL_TASK:EXPORT,ADMIN.APPROVAL_POLICY:VIEW,ADMIN.APPROVAL_POLICY:UPDATE,ADMIN.APPROVAL_POLICY:PUBLISH,ADMIN.APPROVAL_POLICY:MANAGE");
                request.addHeader("X-DWP-Identity-Plane", "TENANT"); request.addHeader("X-DWP-Rollout-State", state);
                request.addHeader("X-DWP-Rollout-Cohort", "baseline"); request.addHeader("X-DWP-Rollout-Revision", "rollout-" + "a".repeat(64));
                request.addHeader("X-DWP-Route-Contract-Key", endpoint.routeKey()); request.addHeader("X-DWP-Context-Key", "current-context");
                request.addHeader("X-DWP-Context-Scope-Key", "current-scope"); request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
                request.addHeader("X-DWP-Current-Decision-Revision", "psr-" + "b".repeat(64));
                request.addHeader("X-DWP-Current-Revalidate-At", java.time.OffsetDateTime.now().plusSeconds(30).toString());
                var response = new MockHttpServletResponse(); var calls = new AtomicInteger();
                var mapper = new ObjectMapper().findAndRegisterModules();
                new ApprovalSecurityFilter("trusted", "", true, mapper,
                        new ApprovalPilotPepRegistry(mapper, java.time.Clock.systemUTC(), 7),
                        new ApprovalManagementScopeResolver(), null)
                        .doFilter(request, response, (ignored, ignoredResponse) -> calls.incrementAndGet());
                assertThat(response.getStatus()).as(state + ' ' + endpoint.routeKey()).isEqualTo(403);
                assertThat(calls.get()).isZero();
            }
        }
    }
}
