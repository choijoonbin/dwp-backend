package com.dwp.services.approval.security;

import com.dwp.core.security.ScopedAuthorityToken;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

class ApprovalSecurityFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private static final String ROLLOUT_REVISION = "rollout-"
            + "0123456789abcdef".repeat(4);

    @AfterEach
    void clearThreadLocalContexts() {
        ApprovalManagementScopeContext.clear();
        ApprovalDecisionRevisionContext.clear();
        ApprovalPilotAuthorizationContext.clear();
        ApprovalRequestContext.clear();
    }

    @ParameterizedTest(name = "state={0}, ready={1}")
    @MethodSource("rolloutTruthTable")
    void appliesProductAgnosticRolloutTruthTableToARealWorkflowMutation(
            String state,
            boolean ready,
            int expectedStatus,
            int expectedMutations) throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter(
                "trusted", "", ready, objectMapper);
        MockHttpServletRequest request = request(
                "POST", "/v1/admin/workflows", "APPROVAL_DESIGNER",
                "ADMIN.APPROVAL_DESIGN:CREATE");
        request.addHeader(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                scopedRole("approvals.design.create", "ADMIN.APPROVAL_DESIGN:CREATE"));
        rollout(request, state);
        if (state.charAt(1) == '1') {
            trustedAuthority(request, "route.approvals.admin.workflow-create.action");
            request.addHeader(ApprovalSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                    "psr-" + "0123456789abcdef".repeat(4));
        }
        AtomicInteger mutations = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                mutations.incrementAndGet());

        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(mutations.get()).isEqualTo(expectedMutations);
    }

    private static Stream<Arguments> rolloutTruthTable() {
        return Stream.of(
                Arguments.of("000", false, 200, 1),
                Arguments.of("000", true, 200, 1),
                Arguments.of("100", false, 200, 1),
                Arguments.of("100", true, 200, 1),
                Arguments.of("110", false, 503, 0),
                Arguments.of("110", true, 200, 1),
                Arguments.of("111", false, 503, 0),
                Arguments.of("111", true, 200, 1));
    }

    @Test
    void duplicateTrustedAuthorityHeadersFailClosedBeforeMutation() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter(
                "trusted", "", true, objectMapper);
        MockHttpServletRequest request = request(
                "POST", "/v1/admin/workflows", "APPROVAL_DESIGNER",
                "ADMIN.APPROVAL_DESIGN:CREATE");
        request.addHeader(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                scopedRole("approvals.design.create", "ADMIN.APPROVAL_DESIGN:CREATE"));
        rollout(request, "110");
        trustedAuthority(request, "route.approvals.admin.workflow-create.action");
        request.addHeader(ApprovalSecurityFilter.ROUTE_CONTRACT_HEADER,
                "route.approvals.admin.workflow-create.action");
        request.addHeader(ApprovalSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                "psr-" + "0123456789abcdef".repeat(4));
        AtomicInteger mutations = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                mutations.incrementAndGet());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(mutations.get()).isZero();
    }

    @Test
    void requiresAnExplicitApplicationPermissionEvenForReads() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest request = request("GET", "/v1/home", "APPROVAL_OPERATOR", "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void permitsAWorkspaceMemberWithVerifiedApplicationAccess() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest request = request(
                "GET", "/v1/home", "WORKSPACE_MEMBER", "APP.APPROVALS:VIEW");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void requiresDomainPermissionsForUserDataCollections() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletResponse tasksDenied = new MockHttpServletResponse();
        filter.doFilter(request(
                "GET", "/v1/tasks", "APPROVAL_DESIGNER", "APP.APPROVALS:VIEW"),
                tasksDenied, new MockFilterChain());

        MockHttpServletResponse requestsPermitted = new MockHttpServletResponse();
        filter.doFilter(request(
                "GET", "/v1/requests", "WORKSPACE_MEMBER",
                "APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:VIEW"),
                requestsPermitted, new MockFilterChain());

        assertThat(tasksDenied.getStatus()).isEqualTo(403);
        assertThat(requestsPermitted.getStatus()).isEqualTo(200);
    }

    @Test
    void limitsTheAgentRuntimeIdentityToExactReadOnlyApprovalSources() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter(
                "trusted", "runtime", objectMapper);
        MockHttpServletRequest allowed = request(
                "GET", "/v1/tasks", "WORKSPACE_MEMBER",
                "APP.APPROVALS:VIEW,ACTION.APPROVAL_TASK:VIEW");
        allowed.removeHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER);
        allowed.addHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

        filter.doFilter(allowed, allowedResponse, new MockFilterChain());

        assertThat(allowedResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest deniedMutation = request(
                "POST", "/v1/tasks/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/claim",
                "WORKSPACE_MEMBER", "APP.APPROVALS:VIEW,ACTION.APPROVAL_TASK:UPDATE");
        deniedMutation.removeHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER);
        deniedMutation.addHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        MockHttpServletResponse deniedMutationResponse = new MockHttpServletResponse();

        filter.doFilter(deniedMutation, deniedMutationResponse, new MockFilterChain());

        assertThat(deniedMutationResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest deniedBroaderRead = request(
                "GET", "/v1/home", "WORKSPACE_MEMBER", "APP.APPROVALS:VIEW");
        deniedBroaderRead.removeHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER);
        deniedBroaderRead.addHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        MockHttpServletResponse deniedBroaderReadResponse = new MockHttpServletResponse();

        filter.doFilter(deniedBroaderRead, deniedBroaderReadResponse, new MockFilterChain());

        assertThat(deniedBroaderReadResponse.getStatus()).isEqualTo(401);

        ApprovalSecurityFilter codeReady = new ApprovalSecurityFilter(
                "trusted", "runtime", true, objectMapper);
        MockHttpServletRequest runtimeWithoutTenantRollout = request(
                "GET", "/v1/tasks", "WORKSPACE_MEMBER",
                "APP.APPROVALS:VIEW,ACTION.APPROVAL_TASK:VIEW");
        runtimeWithoutTenantRollout.removeHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER);
        runtimeWithoutTenantRollout.addHeader(
                ApprovalSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        runtimeWithoutTenantRollout.removeHeader(ApprovalSecurityFilter.ROLLOUT_STATE_HEADER);
        runtimeWithoutTenantRollout.removeHeader(ApprovalSecurityFilter.ROLLOUT_REVISION_HEADER);
        runtimeWithoutTenantRollout.removeHeader(ApprovalSecurityFilter.ROLLOUT_COHORT_HEADER);
        MockHttpServletResponse runtimeResponse = new MockHttpServletResponse();

        codeReady.doFilter(
                runtimeWithoutTenantRollout, runtimeResponse, new MockFilterChain());

        assertThat(runtimeResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void permitsRequesterToResumeAnInformationRequestWithUpdatePermission() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST",
                "/v1/requests/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/information-response",
                "WORKSPACE_MEMBER",
                "APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:UPDATE"),
                response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void separatesRequestCreationFromLifecycleUpdates() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);

        MockHttpServletResponse updateCannotCreate = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST", "/v1/requests", "WORKSPACE_MEMBER",
                "APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:UPDATE"),
                updateCannotCreate, new MockFilterChain());

        MockHttpServletResponse createCannotWithdraw = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST",
                "/v1/requests/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/withdraw",
                "WORKSPACE_MEMBER",
                "APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:CREATE"),
                createCannotWithdraw, new MockFilterChain());

        MockHttpServletResponse createPermitted = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST", "/v1/requests", "WORKSPACE_MEMBER",
                "APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:CREATE"),
                createPermitted, new MockFilterChain());

        MockHttpServletResponse updatePermitted = new MockHttpServletResponse();
        filter.doFilter(request(
                "PUT", "/v1/requests/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/draft",
                "WORKSPACE_MEMBER",
                "APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:UPDATE"),
                updatePermitted, new MockFilterChain());

        assertThat(updateCannotCreate.getStatus()).isEqualTo(403);
        assertThat(createCannotWithdraw.getStatus()).isEqualTo(403);
        assertThat(createPermitted.getStatus()).isEqualTo(200);
        assertThat(updatePermitted.getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotLetARoleOrReadPermissionAuthorizeADecision() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest request = request(
                "POST",
                "/v1/tasks/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/decisions",
                "APPROVAL_OPERATOR",
                "APP.APPROVALS:VIEW");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void separatesWorkflowDesignFromWorkflowPublishing() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest request = request(
                "POST",
                "/v1/admin/workflows/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/publish",
                "APPROVAL_DESIGNER",
                "ADMIN.APPROVAL_DESIGN:VIEW,ADMIN.APPROVAL_DESIGN:UPDATE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void distinguishesWorkflowCreationAndDraftUpdates() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletResponse createDenied = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST", "/v1/admin/workflows", "APPROVAL_DESIGNER",
                "ADMIN.APPROVAL_DESIGN:VIEW,ADMIN.APPROVAL_DESIGN:UPDATE"),
                createDenied, new MockFilterChain());

        MockHttpServletResponse createPermitted = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST", "/v1/admin/workflows", "APPROVAL_DESIGNER",
                "ADMIN.APPROVAL_DESIGN:VIEW,ADMIN.APPROVAL_DESIGN:CREATE"),
                createPermitted, new MockFilterChain());

        MockHttpServletResponse updatePermitted = new MockHttpServletResponse();
        filter.doFilter(request(
                "PUT", "/v1/admin/workflows/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/draft",
                "APPROVAL_DESIGNER", "ADMIN.APPROVAL_DESIGN:UPDATE"),
                updatePermitted, new MockFilterChain());

        assertThat(createDenied.getStatus()).isEqualTo(403);
        assertThat(createPermitted.getStatus()).isEqualTo(200);
        assertThat(updatePermitted.getStatus()).isEqualTo(200);
    }

    @Test
    void separatesFormCatalogDesignPublicationAndRequesterDiscovery() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);

        MockHttpServletResponse createPermitted = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST", "/v1/admin/forms", "APPROVAL_DESIGNER",
                "ADMIN.APPROVAL_DESIGN:VIEW,ADMIN.APPROVAL_DESIGN:CREATE"),
                createPermitted, new MockFilterChain());

        MockHttpServletResponse designerPublishDenied = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST",
                "/v1/admin/forms/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/publish",
                "APPROVAL_DESIGNER",
                "ADMIN.APPROVAL_DESIGN:VIEW,ADMIN.APPROVAL_DESIGN:UPDATE"),
                designerPublishDenied, new MockFilterChain());

        MockHttpServletResponse publisherPermitted = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST",
                "/v1/admin/forms/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/publish",
                "APPROVAL_PUBLISHER",
                "ADMIN.APPROVAL_DESIGN:VIEW,ADMIN.APPROVAL_DESIGN:APPROVE"),
                publisherPermitted, new MockFilterChain());

        MockHttpServletResponse memberCatalogPermitted = new MockHttpServletResponse();
        filter.doFilter(request(
                "GET", "/v1/catalog/forms", "WORKSPACE_MEMBER",
                "APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:VIEW"),
                memberCatalogPermitted, new MockFilterChain());

        assertThat(createPermitted.getStatus()).isEqualTo(200);
        assertThat(designerPublishDenied.getStatus()).isEqualTo(403);
        assertThat(publisherPermitted.getStatus()).isEqualTo(200);
        assertThat(memberCatalogPermitted.getStatus()).isEqualTo(200);
    }

    @Test
    void reservesTheAdministrationOverviewForApprovalOperations() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest designer = request(
                "GET",
                "/v1/admin/overview",
                "APPROVAL_DESIGNER",
                "ADMIN.APPROVAL_DESIGN:VIEW");
        MockHttpServletResponse denied = new MockHttpServletResponse();

        filter.doFilter(designer, denied, new MockFilterChain());

        MockHttpServletRequest operator = request(
                "GET",
                "/v1/admin/overview",
                "APPROVAL_OPERATOR",
                "ADMIN.APPROVAL_OPERATIONS:VIEW");
        MockHttpServletResponse permitted = new MockHttpServletResponse();

        filter.doFilter(operator, permitted, new MockFilterChain());

        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(permitted.getStatus()).isEqualTo(200);
    }

    @Test
    void separatesPolicySubmissionFromIndependentPublication() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        String policyPath =
                "/v1/admin/policies/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb";

        MockHttpServletResponse designerUpdate = new MockHttpServletResponse();
        filter.doFilter(request(
                "PUT", policyPath, "APPROVAL_DESIGNER",
                "ADMIN.APPROVAL_POLICY:VIEW,ADMIN.APPROVAL_POLICY:UPDATE"),
                designerUpdate, new MockFilterChain());

        MockHttpServletResponse designerPublish = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST", policyPath + "/publish", "APPROVAL_DESIGNER",
                "ADMIN.APPROVAL_POLICY:VIEW,ADMIN.APPROVAL_POLICY:UPDATE"),
                designerPublish, new MockFilterChain());

        MockHttpServletResponse publisherUpdate = new MockHttpServletResponse();
        filter.doFilter(request(
                "PUT", policyPath, "APPROVAL_PUBLISHER",
                "ADMIN.APPROVAL_POLICY:VIEW,ADMIN.APPROVAL_POLICY:APPROVE"),
                publisherUpdate, new MockFilterChain());

        MockHttpServletResponse publisherPublish = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST", policyPath + "/publish", "APPROVAL_PUBLISHER",
                "ADMIN.APPROVAL_POLICY:VIEW,ADMIN.APPROVAL_POLICY:APPROVE"),
                publisherPublish, new MockFilterChain());

        assertThat(designerUpdate.getStatus()).isEqualTo(200);
        assertThat(designerPublish.getStatus()).isEqualTo(403);
        assertThat(publisherUpdate.getStatus()).isEqualTo(403);
        assertThat(publisherPublish.getStatus()).isEqualTo(200);
    }

    @Test
    void keepsIntegrationReplaySeparateFromReadOnlyAssurance() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        String retryPath =
                "/v1/admin/operations/events/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/retry";

        MockHttpServletResponse auditorDenied = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST", retryPath, "AUDITOR", "ADMIN.APPROVAL_OPERATIONS:VIEW"),
                auditorDenied, new MockFilterChain());

        MockHttpServletResponse operatorPermitted = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST", retryPath, "APPROVAL_OPERATOR",
                "ADMIN.APPROVAL_OPERATIONS:VIEW,ADMIN.APPROVAL_OPERATIONS:MANAGE"),
                operatorPermitted, new MockFilterChain());

        assertThat(auditorDenied.getStatus()).isEqualTo(403);
        assertThat(operatorPermitted.getStatus()).isEqualTo(200);
    }

    @Test
    void appliesTenantRolloutEnforcementInsteadOfTheGlobalReadinessSwitch() throws Exception {
        ApprovalSecurityFilter ready = new ApprovalSecurityFilter(
                "trusted", "", true, objectMapper);

        MockHttpServletRequest baseline = request(
                "POST",
                "/v1/admin/operations/events/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/retry",
                "APPROVAL_OPERATOR",
                "ADMIN.APPROVAL_OPERATIONS:MANAGE");
        rollout(baseline, "000");
        MockHttpServletResponse baselineResponse = new MockHttpServletResponse();
        ready.doFilter(baseline, baselineResponse, new MockFilterChain());

        MockHttpServletRequest shadowOnly = request(
                "POST",
                "/v1/admin/operations/events/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/retry",
                "APPROVAL_OPERATOR",
                "ADMIN.APPROVAL_OPERATIONS:MANAGE");
        rollout(shadowOnly, "100");
        MockHttpServletResponse shadowOnlyResponse = new MockHttpServletResponse();
        ready.doFilter(shadowOnly, shadowOnlyResponse, new MockFilterChain());

        MockHttpServletRequest enforced = request(
                "GET", "/v1/admin/workflows", "WORKSPACE_MEMBER",
                "ADMIN.APPROVAL_DESIGN:VIEW");
        enforced.addHeader(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                scopedRole("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW"));
        rollout(enforced, "110");
        trustedAuthority(enforced, "route.approvals.admin.workflows.page");
        MockHttpServletResponse enforcedResponse = new MockHttpServletResponse();
        ready.doFilter(enforced, enforcedResponse, new MockFilterChain());

        MockHttpServletRequest enforcedUi = request(
                "GET", "/v1/admin/workflows", "WORKSPACE_MEMBER",
                "ADMIN.APPROVAL_DESIGN:VIEW");
        enforcedUi.addHeader(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                scopedRole("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW"));
        rollout(enforcedUi, "111");
        trustedAuthority(enforcedUi, "route.approvals.admin.workflows.page");
        MockHttpServletResponse enforcedUiResponse = new MockHttpServletResponse();
        ready.doFilter(enforcedUi, enforcedUiResponse, new MockFilterChain());

        assertThat(baselineResponse.getStatus()).isEqualTo(200);
        assertThat(shadowOnlyResponse.getStatus()).isEqualTo(200);
        assertThat(enforcedResponse.getStatus()).isEqualTo(200);
        assertThat(enforcedUiResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void enforcementFailsClosedWhenV2IsNotReadyOrRolloutEvidenceIsInvalid() throws Exception {
        ApprovalSecurityFilter notReady = new ApprovalSecurityFilter(
                "trusted", "", false, objectMapper);
        MockHttpServletRequest enforced = request(
                "GET", "/v1/admin/workflows", "WORKSPACE_MEMBER",
                "ADMIN.APPROVAL_DESIGN:VIEW");
        enforced.addHeader(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                scopedRole("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW"));
        rollout(enforced, "110");
        trustedAuthority(enforced, "route.approvals.admin.workflows.page");
        MockHttpServletResponse notReadyResponse = new MockHttpServletResponse();
        notReady.doFilter(enforced, notReadyResponse, new MockFilterChain());

        ApprovalSecurityFilter ready = new ApprovalSecurityFilter(
                "trusted", "", true, objectMapper);
        MockHttpServletRequest missing = request(
                "GET", "/v1/admin/workflows", "WORKSPACE_MEMBER",
                "ADMIN.APPROVAL_DESIGN:VIEW");
        missing.removeHeader(ApprovalSecurityFilter.ROLLOUT_STATE_HEADER);
        MockHttpServletResponse missingResponse = new MockHttpServletResponse();
        ready.doFilter(missing, missingResponse, new MockFilterChain());

        MockHttpServletRequest malformed = request(
                "GET", "/v1/admin/workflows", "WORKSPACE_MEMBER",
                "ADMIN.APPROVAL_DESIGN:VIEW");
        rollout(malformed, "010");
        MockHttpServletResponse malformedResponse = new MockHttpServletResponse();
        ready.doFilter(malformed, malformedResponse, new MockFilterChain());

        assertThat(notReadyResponse.getStatus()).isEqualTo(503);
        assertThat(missingResponse.getStatus()).isEqualTo(503);
        assertThat(malformedResponse.getStatus()).isEqualTo(503);
    }

    @Test
    void exactEnforcementAcceptsDynamicPairedSetsButRejectsMalformedOrUnpairedSets()
            throws Exception {
        ApprovalSecurityFilter ready = new ApprovalSecurityFilter(
                "trusted", "", true, objectMapper);
        MockHttpServletRequest exact = request(
                "GET", "/v1/admin/workflows", "WORKSPACE_MEMBER",
                "ADMIN.APPROVAL_DESIGN:VIEW");
        exact.addHeader(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                scopedRole("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW"));
        rollout(exact, "110");
        trustedAuthority(exact, "route.approvals.admin.workflows.page");
        MockHttpServletResponse exactResponse = new MockHttpServletResponse();
        ready.doFilter(exact, exactResponse, new MockFilterChain());

        MockHttpServletRequest wrongResourceKey = request(
                "GET", "/v1/admin/workflows", "WORKSPACE_MEMBER",
                "ADMIN.APPROVAL_DESIGN:VIEW");
        wrongResourceKey.addHeader(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                "APP_CONFIG_ADMIN@APP.APPROVALS,"
                        + ScopedAuthorityToken.responsibilityCode(
                                "approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW")
                        + "@APP.APPROVALS");
        rollout(wrongResourceKey, "110");
        trustedAuthority(wrongResourceKey, "route.approvals.admin.workflows.page");
        MockHttpServletResponse wrongResourceKeyResponse = new MockHttpServletResponse();
        ready.doFilter(wrongResourceKey, wrongResourceKeyResponse, new MockFilterChain());

        MockHttpServletRequest dynamicSet = request(
                "GET", "/v1/admin/workflows", "WORKSPACE_MEMBER",
                "ADMIN.APPROVAL_DESIGN:VIEW");
        dynamicSet.addHeader(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                scopedRole("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW",
                        "RS_HCM_CONFIG"));
        rollout(dynamicSet, "110");
        trustedAuthority(dynamicSet, "route.approvals.admin.workflows.page");
        MockHttpServletResponse dynamicSetResponse = new MockHttpServletResponse();
        ready.doFilter(dynamicSet, dynamicSetResponse, new MockFilterChain());

        MockHttpServletRequest unpairedSet = request(
                "GET", "/v1/admin/workflows", "WORKSPACE_MEMBER",
                "ADMIN.APPROVAL_DESIGN:VIEW");
        unpairedSet.addHeader(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                "APP_CONFIG_ADMIN@RS_APPROVALS," + ScopedAuthorityToken.wireToken(
                        "approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW",
                        "RS_HCM_CONFIG"));
        rollout(unpairedSet, "110");
        trustedAuthority(unpairedSet, "route.approvals.admin.workflows.page");
        MockHttpServletResponse unpairedSetResponse = new MockHttpServletResponse();
        ready.doFilter(unpairedSet, unpairedSetResponse, new MockFilterChain());

        assertThat(exactResponse.getStatus()).isEqualTo(200);
        assertThat(wrongResourceKeyResponse.getStatus()).isEqualTo(403);
        assertThat(dynamicSetResponse.getStatus()).isEqualTo(200);
        assertThat(unpairedSetResponse.getStatus()).isEqualTo(403);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"000", "100"})
    void defaultOffAndShadowAllowScopedOnlyDutyWithoutGrantingCrossDuty(String state)
            throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter(
                "trusted", "", true, objectMapper);
        String path = "/v1/admin/workflows/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/publish";
        MockHttpServletRequest allowed = request(
                "POST", path, "WORKSPACE_MEMBER", "ADMIN.APPROVAL_DESIGN:PUBLISH");
        allowed.addHeader(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                scopedRole("approvals.design.publish", "ADMIN.APPROVAL_DESIGN:PUBLISH"));
        rollout(allowed, state);
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        filter.doFilter(allowed, allowedResponse, new MockFilterChain());

        MockHttpServletRequest wrongDuty = request(
                "POST", path, "WORKSPACE_MEMBER", "ADMIN.APPROVAL_DESIGN:PUBLISH");
        wrongDuty.addHeader(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                scopedRole("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW"));
        rollout(wrongDuty, state);
        MockHttpServletResponse wrongDutyResponse = new MockHttpServletResponse();
        filter.doFilter(wrongDuty, wrongDutyResponse, new MockFilterChain());

        assertThat(allowedResponse.getStatus()).isEqualTo(200);
        assertThat(wrongDutyResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void setupExceptionClearsEveryContextBeforeTheSameThreadHandlesAnotherRequest()
            throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter(
                "trusted", "", true, objectMapper);
        String authorityAttribute = ApprovalPilotPepRegistry.class.getName() + ".authorities";
        MockHttpServletRequest request = populate(new MockHttpServletRequest(
                "GET", "/v1/admin/workflows") {
            @Override
            public void setAttribute(String name, Object value) {
                if (authorityAttribute.equals(name)) {
                    throw new IllegalStateException("forced setup failure");
                }
                super.setAttribute(name, value);
            }
        }, "WORKSPACE_MEMBER", "ADMIN.APPROVAL_DESIGN:VIEW");
        request.addHeader(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                scopedRole("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW"));
        rollout(request, "110");
        trustedAuthority(request, "route.approvals.admin.workflows.page");

        assertThatThrownBy(() -> filter.doFilter(
                request, new MockHttpServletResponse(), new MockFilterChain()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced setup failure");
        assertContextsCleared();
        runRejectedFollowUpOnSameThread(filter);
        assertContextsCleared();
    }

    @Test
    void chainExceptionClearsEveryContextBeforeTheSameThreadHandlesAnotherRequest()
            throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter(
                "trusted", "", true, objectMapper);
        MockHttpServletRequest request = request(
                "GET", "/v1/admin/workflows", "WORKSPACE_MEMBER",
                "ADMIN.APPROVAL_DESIGN:VIEW");
        request.addHeader(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                scopedRole("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW"));
        rollout(request, "110");
        trustedAuthority(request, "route.approvals.admin.workflows.page");

        assertThatThrownBy(() -> filter.doFilter(
                request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> {
                    throw new ServletException("forced chain failure");
                }))
                .isInstanceOf(ServletException.class)
                .hasMessageContaining("forced chain failure");
        assertContextsCleared();
        runRejectedFollowUpOnSameThread(filter);
        assertContextsCleared();
    }

    private MockHttpServletRequest request(
            String method,
            String path,
            String roles,
            String permissions) {
        return populate(new MockHttpServletRequest(method, path), roles, permissions);
    }

    private MockHttpServletRequest populate(
            MockHttpServletRequest request,
            String roles,
            String permissions) {
        request.addHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(ApprovalSecurityFilter.USER_HEADER, "17");
        request.addHeader(ApprovalSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(ApprovalSecurityFilter.ROLES_HEADER, roles);
        request.addHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER, permissions);
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_STATE_HEADER, "000");
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_REVISION_HEADER, ROLLOUT_REVISION);
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_COHORT_HEADER, "baseline");
        return request;
    }

    private void runRejectedFollowUpOnSameThread(ApprovalSecurityFilter filter)
            throws Exception {
        MockHttpServletRequest followUp = request(
                "GET", "/v1/home", "WORKSPACE_MEMBER", "APP.APPROVALS:VIEW");
        followUp.removeHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER);
        followUp.addHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER, "untrusted");
        filter.doFilter(followUp, new MockHttpServletResponse(), new MockFilterChain());
    }

    private void assertContextsCleared() {
        assertThat(ApprovalManagementScopeContext.current()).isEmpty();
        assertThat(ApprovalDecisionRevisionContext.current()).isEmpty();
        assertThat(ApprovalPilotAuthorizationContext.current()).isEmpty();
        assertThatThrownBy(ApprovalRequestContext::require)
                .isInstanceOf(IllegalStateException.class);
    }

    private void rollout(MockHttpServletRequest request, String state) {
        request.removeHeader(ApprovalSecurityFilter.ROLLOUT_STATE_HEADER);
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_STATE_HEADER, state);
    }

    private void trustedAuthority(MockHttpServletRequest request, String routeKey) {
        request.addHeader(ApprovalSecurityFilter.ROUTE_CONTRACT_HEADER, routeKey);
        request.addHeader(ApprovalSecurityFilter.CURRENT_DECISION_REVISION_HEADER,
                "psr-" + "0123456789abcdef".repeat(4));
        request.addHeader(ApprovalSecurityFilter.CURRENT_DECISION_REVALIDATE_AT_HEADER,
                "2030-01-01T00:00:00Z");
        request.addHeader(ApprovalSecurityFilter.CURRENT_CONTEXT_HEADER, "approval.management");
        String resourceSet = java.util.Collections.list(
                        request.getHeaders(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER)).stream()
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> value.startsWith("APP_CONFIG_ADMIN@"))
                .map(value -> value.substring("APP_CONFIG_ADMIN@".length()))
                .findFirst()
                .orElse("RS_APPROVALS");
        request.addHeader(ApprovalSecurityFilter.CURRENT_SCOPE_HEADER,
                ProductSurfaceScopeKey.resourceSet(
                        42L, 17L, "approvals", "approvals.admin", resourceSet));
    }

    private String scopedRole(String contractKey, String resolvedCapabilityCode) {
        return scopedRole(contractKey, resolvedCapabilityCode, "RS_APPROVALS");
    }

    private String scopedRole(
            String contractKey,
            String resolvedCapabilityCode,
            String resourceSetKey) {
        return "APP_CONFIG_ADMIN@" + resourceSetKey + ','
                + ScopedAuthorityToken.wireToken(
                        contractKey, resolvedCapabilityCode, resourceSetKey);
    }
}
