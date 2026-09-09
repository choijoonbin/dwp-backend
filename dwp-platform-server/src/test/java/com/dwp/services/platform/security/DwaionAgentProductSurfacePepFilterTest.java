package com.dwp.services.platform.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DwaionAgentProductSurfacePepFilterTest {

    private static final long TENANT_ID = 7L;
    private static final long USER_ID = 101L;
    private static final String CURRENT_REVISION = "psr-" + "a".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void exactManagementReadAndMutationReachThePlatformOwner() throws Exception {
        Result read = execute(true, request(
                "GET", "/v1/admin/dwaion/agents",
                "route.dwaion.management.agents.page",
                "ADMIN.DWAION_AGENTS:VIEW", CURRENT_REVISION));
        Result mutation = execute(true, request(
                "POST", "/v1/admin/dwaion/agents/DWP_ASSISTANT/revisions/2/activate",
                "route.dwaion.management.agent-revision-activate.action",
                "ADMIN.DWAION_AGENTS:APPROVE", CURRENT_REVISION));

        assertThat(read.chain().getRequest()).isNotNull();
        assertThat(mutation.chain().getRequest()).isNotNull();
        assertThat(read.response().getHeader("X-DWP-Decision-Revision"))
                .isEqualTo(CURRENT_REVISION);
        assertThat(mutation.response().getHeader("X-DWP-Decision-Revision"))
                .isEqualTo(CURRENT_REVISION);
    }

    @Test
    void staleRevisionCannotReachAgentActivation() throws Exception {
        Result result = execute(true, request(
                "POST", "/v1/admin/dwaion/agents/DWP_ASSISTANT/revisions/2/activate",
                "route.dwaion.management.agent-revision-activate.action",
                "ADMIN.DWAION_AGENTS:APPROVE", "psr-" + "b".repeat(64)));

        assertThat(result.response().getStatus()).isEqualTo(409);
        assertThat(result.chain().getRequest()).isNull();
    }

    @Test
    void wrongScopePermissionAndRouteAreDeniedBeforeTheController() throws Exception {
        MockHttpServletRequest wrongScope = request(
                "POST", "/v1/admin/dwaion/agents/DWP_ASSISTANT/revisions",
                "route.dwaion.management.agent-revision-create.action",
                "ADMIN.DWAION_AGENTS:UPDATE", CURRENT_REVISION);
        wrongScope.removeHeader("X-DWP-Context-Scope-Key");
        wrongScope.addHeader("X-DWP-Context-Scope-Key", ProductSurfaceScopeKey.key(
                TENANT_ID, USER_ID, "dwaion", "dwaion.work", "SELF", "SELF"));
        Result scopeResult = execute(true, wrongScope);
        Result permissionResult = execute(true, request(
                "POST", "/v1/admin/dwaion/agents/DWP_ASSISTANT/revisions",
                "route.dwaion.management.agent-revision-create.action",
                "ADMIN.DWAION_AGENTS:CREATE", CURRENT_REVISION));
        Result routeResult = execute(true, request(
                "POST", "/v1/admin/dwaion/agents/DWP_ASSISTANT/revisions",
                "route.dwaion.management.agent-create.action",
                "ADMIN.DWAION_AGENTS:UPDATE", CURRENT_REVISION));

        assertThat(scopeResult.response().getStatus()).isEqualTo(403);
        assertThat(permissionResult.response().getStatus()).isEqualTo(403);
        assertThat(routeResult.response().getStatus()).isEqualTo(403);
        assertThat(scopeResult.chain().getRequest()).isNull();
        assertThat(permissionResult.chain().getRequest()).isNull();
        assertThat(routeResult.chain().getRequest()).isNull();
    }

    @Test
    void malformedDynamicRouteCannotBypassTheExactBinding() throws Exception {
        Result result = execute(true, request(
                "PATCH", "/v1/admin/dwaion/agents/DWP_ASSISTANT/revisions/0",
                "route.dwaion.management.agent-revision-update.action",
                "ADMIN.DWAION_AGENTS:UPDATE", CURRENT_REVISION));

        assertThat(result.response().getStatus()).isEqualTo(403);
        assertThat(result.chain().getRequest()).isNull();
    }

    @Test
    void draftActivationFlagPreservesLegacyButFailsClosedForEnforcedTraffic()
            throws Exception {
        MockHttpServletRequest legacy = request(
                "GET", "/v1/admin/dwaion/agents",
                "route.dwaion.management.agents.page",
                "ADMIN.DWAION_AGENTS:VIEW", CURRENT_REVISION);
        legacy.removeHeader("X-DWP-Rollout-State");
        legacy.removeHeader("X-DWP-Rollout-Revision");
        legacy.removeHeader("X-DWP-Rollout-Cohort");
        Result legacyResult = execute(false, legacy);
        Result enforcedResult = execute(false, request(
                "GET", "/v1/admin/dwaion/agents",
                "route.dwaion.management.agents.page",
                "ADMIN.DWAION_AGENTS:VIEW", CURRENT_REVISION));

        assertThat(legacyResult.chain().getRequest()).isNotNull();
        assertThat(enforcedResult.response().getStatus()).isEqualTo(503);
        assertThat(enforcedResult.chain().getRequest()).isNull();
    }

    @Test
    void browserOpenApiDocumentsTheConditionalRevisionForEveryOwnedMutation()
            throws Exception {
        JsonNode paths = objectMapper.readTree(Path.of(
                "../contracts/openapi/gateway-public.json").toFile()).path("paths");
        for (String operation : List.of(
                "POST /api/platform/v1/admin/dwaion/agents",
                "POST /api/platform/v1/admin/dwaion/agents/{entryKey}/revisions",
                "PATCH /api/platform/v1/admin/dwaion/agents/{entryKey}/revisions/{revisionNumber}",
                "POST /api/platform/v1/admin/dwaion/agents/{entryKey}/revisions/{revisionNumber}/activate",
                "POST /api/platform/v1/admin/dwaion/agents/{entryKey}/revisions/{revisionNumber}/retire")) {
            String[] parts = operation.split(" ", 2);
            JsonNode parameters = paths.path(parts[1]).path(parts[0].toLowerCase())
                    .path("parameters");
            assertThat(parameters).as(operation).isNotEmpty();
            assertThat(parameters.findValuesAsText("name"))
                    .as(operation)
                    .contains("contextScopeKey", "X-DWP-Expected-Decision-Revision");
        }
    }

    private Result execute(boolean enabled, MockHttpServletRequest request) throws Exception {
        DwaionAgentProductSurfacePepFilter filter =
                new DwaionAgentProductSurfacePepFilter(enabled, objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response, chain);
    }

    private MockHttpServletRequest request(
            String method,
            String path,
            String route,
            String permissions,
            String expectedRevision) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("X-DWP-Tenant-ID", Long.toString(TENANT_ID));
        request.addHeader("X-DWP-User-ID", Long.toString(USER_ID));
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        request.addHeader("X-DWP-Roles", "TENANT_ADMIN");
        request.addHeader("X-DWP-Permissions", permissions);
        request.addHeader("X-DWP-Rollout-State", "110");
        request.addHeader("X-DWP-Rollout-Revision", "rollout-" + "c".repeat(64));
        request.addHeader("X-DWP-Rollout-Cohort", "full");
        request.addHeader("X-DWP-Route-Contract-Key", route);
        request.addHeader("X-DWP-Context-Key", "psc-" + "d".repeat(64));
        request.addHeader("X-DWP-Context-Scope-Key", ProductSurfaceScopeKey.key(
                TENANT_ID,
                USER_ID,
                "dwaion",
                "dwaion.management",
                "APP_RESOURCE_SET:RS_DWAION",
                "RESOURCE_SET"));
        request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        request.addHeader("X-DWP-Current-Decision-Revision", CURRENT_REVISION);
        request.addHeader("X-DWP-Current-Revalidate-At", "2099-01-01T00:00:00Z");
        request.addHeader("X-DWP-Expected-Decision-Revision", expectedRevision);
        return request;
    }

    private record Result(MockHttpServletResponse response, MockFilterChain chain) {
    }
}
