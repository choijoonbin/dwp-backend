package com.dwp.services.approval.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.core.security.ScopedAuthorityToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ApprovalProductSurfacePepEvidenceTest {

    private static final long TENANT_ID = 42L;
    private static final long USER_ID = 17L;
    private static final String RESOURCE_SET = "RS_APPROVALS";
    private static final String CURRENT_REVISION = "psr-" + "0123456789abcdef".repeat(4);
    private static final String ROLLOUT_REVISION =
            "rollout-" + "0123456789abcdef".repeat(4);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearRequestContexts() {
        ApprovalManagementScopeContext.clear();
        ApprovalDecisionRevisionContext.clear();
        ApprovalPilotAuthorizationContext.clear();
        ApprovalRequestContext.clear();
    }

    @Test
    void rejectsCrossTenantOpaqueScopeAtOwnerServicePep() throws Exception {
        MockHttpServletRequest request = managementPageRequest();
        replaceScope(request, TENANT_ID + 1, RESOURCE_SET);

        Result result = execute(request);

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.ownerCalls()).isZero();
    }

    @Test
    void rejectsCanonicalOpaqueScopeEscapeAtOwnerServicePep() throws Exception {
        MockHttpServletRequest request = managementPageRequest();
        replaceScope(request, TENANT_ID, "RS_FOREIGN_APPROVALS");

        Result result = execute(request);

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.ownerCalls()).isZero();
    }

    @Test
    void rejectsStaleAuthorityRevisionAtOwnerServicePep() throws Exception {
        MockHttpServletRequest request = exactRequest(
                "POST",
                "/v1/admin/workflows",
                "route.approvals.admin.workflow-create.action",
                "ADMIN.APPROVAL_DESIGN:CREATE",
                "approvals.design.create",
                ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL);
        request.addHeader(
                ApprovalSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                "psr-" + "f".repeat(64));

        Result result = execute(request);

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.ownerCalls()).isZero();
    }

    @Test
    void rejectsNormalCapabilityInProviderSupportModeAtOwnerServicePep() throws Exception {
        MockHttpServletRequest request = exactRequest(
                "POST",
                "/v1/admin/workflows",
                "route.approvals.admin.workflow-create.action",
                "ADMIN.APPROVAL_DESIGN:CREATE",
                "approvals.design.create",
                ApprovalPilotPepRegistry.ActiveAccessMode.PROVIDER_SUPPORT);
        request.addHeader(
                ApprovalSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                CURRENT_REVISION);

        Result result = execute(request);

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.ownerCalls()).isZero();
    }

    @Test
    void rejectsInternalAuthorityHeaderSpoofAtOwnerServicePep() throws Exception {
        MockHttpServletRequest request = managementPageRequest();
        request.removeHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER);
        request.addHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER, "spoofed");

        Result result = execute(request);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.ownerCalls()).isZero();
    }

    @Test
    void executesPageDataAndActionContractsThroughApprovalOwnerRegistry() {
        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(objectMapper);
        String taskId = "14d7b229-4752-4a50-8ac1-ecc129620649";

        assertAllowedKind(
                registry,
                "GET",
                "/v1/home",
                Set.of("APP.APPROVALS:VIEW"),
                "route.approvals.work.home.page",
                "PAGE");
        assertAllowedKind(
                registry,
                "GET",
                "/v1/tasks/" + taskId,
                Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_TASK:VIEW"),
                "route.approvals.work.task-detail.data",
                "DATA");
        assertAllowedKind(
                registry,
                "POST",
                "/v1/tasks/" + taskId + "/decisions",
                Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_TASK:APPROVE"),
                "route.approvals.work.task-decision.action",
                "ACTION");

        assertThat(registry.bindingContracts())
                .extracting(
                        ApprovalPilotPepRegistry.BindingContract::routeContractKey,
                        ApprovalPilotPepRegistry.BindingContract::routeKind,
                        ApprovalPilotPepRegistry.BindingContract::method,
                        ApprovalPilotPepRegistry.BindingContract::publicPath,
                        ApprovalPilotPepRegistry.BindingContract::servicePath)
                .contains(
                        tuple(
                                "route.approvals.work.home.page",
                                "PAGE",
                                "GET",
                                "/api/approvals/v1/home",
                                "/v1/home"),
                        tuple(
                                "route.approvals.work.task-detail.data",
                                "DATA",
                                "GET",
                                "/api/approvals/v1/tasks/{taskId}",
                                "/v1/tasks/{taskId}"),
                        tuple(
                                "route.approvals.work.task-decision.action",
                                "ACTION",
                                "POST",
                                "/api/approvals/v1/tasks/{taskId}/decisions",
                                "/v1/tasks/{taskId}/decisions"));
    }

    private MockHttpServletRequest managementPageRequest() {
        return exactRequest(
                "GET",
                "/v1/admin/workflows",
                "route.approvals.admin.workflows.page",
                "ADMIN.APPROVAL_DESIGN:VIEW",
                "approvals.design.read",
                ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL);
    }

    private MockHttpServletRequest exactRequest(
            String method,
            String path,
            String routeKey,
            String capabilityCode,
            String capabilityContract,
            ApprovalPilotPepRegistry.ActiveAccessMode accessMode) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(ApprovalSecurityFilter.USER_HEADER, Long.toString(USER_ID));
        request.addHeader(ApprovalSecurityFilter.TENANT_HEADER, Long.toString(TENANT_ID));
        request.addHeader(ApprovalSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        request.addHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER, capabilityCode);
        request.addHeader(
                ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                "APP_CONFIG_ADMIN@" + RESOURCE_SET + ','
                        + ScopedAuthorityToken.wireToken(
                                capabilityContract, capabilityCode, RESOURCE_SET));
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_STATE_HEADER, "110");
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_REVISION_HEADER, ROLLOUT_REVISION);
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_COHORT_HEADER, "baseline");
        request.addHeader(ApprovalSecurityFilter.ROUTE_CONTRACT_HEADER, routeKey);
        request.addHeader(ApprovalSecurityFilter.ACTIVE_ACCESS_MODE_HEADER, accessMode.name());
        request.addHeader(ApprovalSecurityFilter.CURRENT_DECISION_REVISION_HEADER, CURRENT_REVISION);
        request.addHeader(
                ApprovalSecurityFilter.CURRENT_DECISION_REVALIDATE_AT_HEADER,
                "2030-01-01T00:00:00Z");
        request.addHeader(ApprovalSecurityFilter.CURRENT_CONTEXT_HEADER, "approval.management");
        replaceScope(request, TENANT_ID, RESOURCE_SET);
        return request;
    }

    private void replaceScope(
            MockHttpServletRequest request, long tenantId, String resourceSet) {
        request.removeHeader(ApprovalSecurityFilter.CURRENT_SCOPE_HEADER);
        request.addHeader(
                ApprovalSecurityFilter.CURRENT_SCOPE_HEADER,
                ProductSurfaceScopeKey.resourceSet(
                        tenantId,
                        USER_ID,
                        "approvals",
                        "approvals.admin",
                        resourceSet));
    }

    private Result execute(MockHttpServletRequest request) throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter(
                "trusted", "", true, objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger ownerCalls = new AtomicInteger();

        filter.doFilter(
                request,
                response,
                (ignoredRequest, ignoredResponse) -> ownerCalls.incrementAndGet());

        return new Result(response.getStatus(), ownerCalls.get());
    }

    private void assertAllowedKind(
            ApprovalPilotPepRegistry registry,
            String method,
            String path,
            Set<String> permissions,
            String routeKey,
            String expectedKind) {
        ApprovalPilotPepRegistry.Decision decision = registry.authorize(
                new ApprovalPilotPepRegistry.RequestEvidence(
                        method,
                        path,
                        permissions,
                        "",
                        Set.of("WORKSPACE_MEMBER"),
                        routeKey,
                        ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.authorities())
                .singleElement()
                .satisfies(authority -> {
                    assertThat(authority.routeContractKey()).isEqualTo(routeKey);
                    assertThat(authority.routeKind()).isEqualTo(expectedKind);
                });
    }

    private record Result(int status, int ownerCalls) {
    }
}
