package com.dwp.services.approval.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.core.security.ScopedAuthorityToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApprovalRelease15PepContractTest {
    private static final String SCOPE = "RS_APPROVALS";
    private static final String REVISION = "psr-" + "1".repeat(64);
    private static final Set<String> ADDED_ROUTES = Set.of(
            "route.approvals.admin.routing.page",
            "route.approvals.admin.integrations.page",
            "route.approvals.admin.audit.page",
            "route.approvals.admin.analytics.page",
            "route.approvals.admin.deployments.page",
            "route.approvals.admin.template-catalog.data",
            "route.approvals.admin.template-detail.data",
            "route.approvals.admin.form-studio-catalog.data",
            "route.approvals.admin.form-studio-detail.data",
            "route.approvals.admin.routing-group.data",
            "route.approvals.admin.routing-resolver-catalog.data",
            "route.approvals.admin.routing-resolver.data",
            "route.approvals.admin.policy-automation-catalog.data",
            "route.approvals.admin.policy-automation-detail.data",
            "route.approvals.admin.connector-governance.data",
            "route.approvals.admin.incident-catalog.data",
            "route.approvals.admin.incident-detail.data",
            "route.approvals.admin.audit-saved-view-catalog.data",
            "route.approvals.admin.audit-record-detail.data",
            "route.approvals.admin.analytics-metric-definitions.data",
            "route.approvals.admin.analytics-cohort.data",
            "route.approvals.admin.deployment-catalog.data",
            "route.approvals.admin.deployment-detail.data",
            "route.approvals.admin.template-draft.action",
            "route.approvals.admin.form-studio-draft.action",
            "route.approvals.admin.routing-directory-update.action",
            "route.approvals.admin.policy-automation-update.action",
            "route.approvals.admin.routing-directory-publish.action",
            "route.approvals.admin.routing-directory-retire.action",
            "route.approvals.admin.policy-automation-publish.action",
            "route.approvals.admin.connector-command.action",
            "route.approvals.admin.incident-command.action",
            "route.approvals.admin.audit-saved-view-create.action",
            "route.approvals.admin.audit-export-create.action",
            "route.approvals.admin.audit-export-attestation.action",
            "route.approvals.admin.deployment-package-create.action",
            "route.approvals.admin.deployment-promotion-create.action",
            "route.approvals.admin.deployment-promotion-review.action",
            "route.approvals.admin.deployment-promotion-schedule.action",
            "route.approvals.admin.deployment-activation.action",
            "route.approvals.admin.deployment-activation-evidence.action",
            "route.approvals.admin.deployment-rollback.action",
            "route.approvals.admin.deployment-rollback-evidence.action");
    private static final Set<String> HIGH_RISK_ROUTES = Set.of(
            "route.approvals.admin.routing-directory-publish.action",
            "route.approvals.admin.routing-directory-retire.action",
            "route.approvals.admin.policy-automation-publish.action",
            "route.approvals.admin.connector-command.action",
            "route.approvals.admin.incident-command.action",
            "route.approvals.admin.audit-saved-view-create.action",
            "route.approvals.admin.audit-export-create.action",
            "route.approvals.admin.audit-export-attestation.action",
            "route.approvals.admin.deployment-package-create.action",
            "route.approvals.admin.deployment-promotion-create.action",
            "route.approvals.admin.deployment-promotion-review.action",
            "route.approvals.admin.deployment-promotion-schedule.action",
            "route.approvals.admin.deployment-activation.action",
            "route.approvals.admin.deployment-activation-evidence.action",
            "route.approvals.admin.deployment-rollback.action",
            "route.approvals.admin.deployment-rollback-evidence.action");

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearContexts() {
        ApprovalManagementScopeContext.clear();
        ApprovalDecisionRevisionContext.clear();
        ApprovalPilotAuthorizationContext.clear();
        ApprovalRequestContext.clear();
    }

    @Test
    void release15IsAnExactMirroredAppendWithCommandBindings() throws Exception {
        Map<String, JsonNode> previous = routes(resource("approval-pilot-pep-v14.generated.json"));
        Map<String, JsonNode> current = routes(resource("approval-pilot-pep-v15.generated.json"));
        Set<String> added = new HashSet<>(current.keySet());
        added.removeAll(previous.keySet());

        assertEquals(ADDED_ROUTES, added);
        assertEquals(89, added.stream().mapToInt(
                key -> current.get(key).path("servicePepBindings").size()).sum());
        assertEquals(28, HIGH_RISK_ROUTES.stream().mapToInt(
                key -> current.get(key).path("stepUpCommandBindings").size()).sum());

        for (String key : added) {
            JsonNode route = current.get(key);
            Map<String, JsonNode> gateway = bindings(route, "gatewayApiBindings");
            Map<String, JsonNode> service = bindings(route, "servicePepBindings");
            assertEquals(service.keySet(), gateway.keySet(), key);
            service.forEach((bindingKey, binding) -> {
                assertEquals(binding.path("method").asText(),
                        gateway.get(bindingKey).path("method").asText(), bindingKey);
                assertEquals("/api/approvals" + binding.path("path").asText(),
                        gateway.get(bindingKey).path("path").asText(), bindingKey);
                assertThat(binding.path("path").asText()).doesNotContain("**");
            });
            if (HIGH_RISK_ROUTES.contains(key)) {
                Map<String, JsonNode> stepUp = bindings(route, "stepUpCommandBindings");
                assertEquals(service.keySet(), stepUp.keySet(), key);
                stepUp.forEach((bindingKey, binding) -> {
                    assertEquals("COMMAND_HEADER",
                            binding.path("expectedObjectVersionSource").asText());
                    assertEquals("X-DWP-Expected-Object-Version",
                            binding.path("expectedObjectVersionName").asText());
                    assertEquals("approval", binding.path("ownerServiceKey").asText());
                    assertEquals("dwp-approval-server", binding.path("audience").asText());
                    assertThat(binding.has("targetIdPathParameter")
                            ^ binding.has("targetIdBodyFields")).isTrue();
                });
            } else {
                assertThat(route.has("stepUpCommandBindings")).isFalse();
            }
        }
    }

    @Test
    void release15EnvelopeAndRuntimeRegistryAreSealed() throws Exception {
        JsonNode current = resource("approval-pilot-pep-v15.generated.json");
        assertEquals(15, current.path("registryRef").path("version").asInt());
        assertEquals("a9eab001b26d6488de0f7f176fa1d70fea2c9792a230769f645eb53ba90cf89f",
                current.path("registryRef").path("sha256").asText());
        assertEquals("d6af7f1b305188daf09635caafb0768e7a64821ad3a6743cb0cec1298edda47d",
                current.path("projectionChecksum").asText());
        assertEquals(192, current.path("projectedRouteContractCount").asInt());
        assertEquals(246, current.path("bindingPairCount").asInt());
        assertEquals(246,
                new ApprovalPilotPepRegistry(json, Clock.systemUTC(), 15)
                        .bindingContracts().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"110", "111"})
    void exactV15PageAndHighRiskActionReachOwnerOnlyWithMatchingAuthority(String state)
            throws Exception {
        assertEquals(200, execute(request(
                "GET", "/v1/admin/operations/analytics/dashboard",
                "route.approvals.admin.analytics.page",
                "ADMIN.APPROVAL_OPERATIONS:VIEW", "approvals.operations.read", state,
                false)).status());
        assertEquals(200, execute(request(
                "POST", "/v1/admin/operations/deployments/promotions/"
                        + "d2ead147-86ce-44de-bdc4-25f1c392b183/activation",
                "route.approvals.admin.deployment-activation.action",
                "ADMIN.APPROVAL_OPERATIONS:EXECUTE", "approvals.operations.execute", state,
                true)).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"110", "111"})
    void enforcedStatesRejectConfusedDeputyMissingAuthorityAndUnknownPath(String state)
            throws Exception {
        MockHttpServletRequest confused = request(
                "GET", "/v1/admin/operations/deployments/dashboard",
                "route.approvals.admin.analytics.page",
                "ADMIN.APPROVAL_OPERATIONS:VIEW", "approvals.operations.read", state,
                false);
        assertEquals(403, execute(confused).status());

        MockHttpServletRequest missing = request(
                "GET", "/v1/admin/operations/analytics/dashboard",
                "route.approvals.admin.analytics.page",
                "ADMIN.APPROVAL_OPERATIONS:VIEW", "approvals.operations.read", state,
                false);
        missing.removeHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER);
        assertEquals(403, execute(missing).status());

        MockHttpServletRequest unknown = request(
                "GET", "/v1/admin/operations/not-registered",
                "route.approvals.admin.analytics.page",
                "ADMIN.APPROVAL_OPERATIONS:VIEW", "approvals.operations.read", state,
                false);
        assertEquals(403, execute(unknown).status());
    }

    @Test
    void staleDecisionRevisionFailsBeforeOwnerExecution() throws Exception {
        MockHttpServletRequest stale = request(
                "POST", "/v1/admin/operations/deployments/promotions/"
                        + "d2ead147-86ce-44de-bdc4-25f1c392b183/activation",
                "route.approvals.admin.deployment-activation.action",
                "ADMIN.APPROVAL_OPERATIONS:EXECUTE", "approvals.operations.execute", "110",
                true);
        stale.removeHeader(ApprovalSecurityFilter.EXPECTED_DECISION_REVISION_HEADER);
        stale.addHeader(ApprovalSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                "psr-" + "f".repeat(64));
        Result result = execute(stale);
        assertEquals(409, result.status());
        assertEquals(0, result.ownerCalls());
    }

    private MockHttpServletRequest request(
            String method, String path, String routeKey, String permission,
            String capability, String state, boolean mutation) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(ApprovalSecurityFilter.USER_HEADER, "17");
        request.addHeader(ApprovalSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(ApprovalSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        request.addHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER, permission);
        request.addHeader(ApprovalSecurityFilter.RESOURCE_ROLES_HEADER,
                "APP_CONFIG_ADMIN@" + SCOPE + ','
                        + ScopedAuthorityToken.wireToken(capability, permission, SCOPE));
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_STATE_HEADER, state);
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_REVISION_HEADER,
                "rollout-" + "2".repeat(64));
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_COHORT_HEADER, "baseline");
        request.addHeader(ApprovalSecurityFilter.ROUTE_CONTRACT_HEADER, routeKey);
        request.addHeader(ApprovalSecurityFilter.ACTIVE_ACCESS_MODE_HEADER, "NORMAL");
        request.addHeader(ApprovalSecurityFilter.CURRENT_DECISION_REVISION_HEADER, REVISION);
        request.addHeader(ApprovalSecurityFilter.CURRENT_DECISION_REVALIDATE_AT_HEADER,
                "2030-01-01T00:00:00Z");
        request.addHeader(ApprovalSecurityFilter.CURRENT_CONTEXT_HEADER, "approval.management");
        request.addHeader(ApprovalSecurityFilter.CURRENT_SCOPE_HEADER,
                ProductSurfaceScopeKey.resourceSet(
                        42L, 17L, "approvals", "approvals.admin", SCOPE));
        if (mutation) {
            request.addHeader(ApprovalSecurityFilter.EXPECTED_DECISION_REVISION_HEADER, REVISION);
        }
        return request;
    }

    private Result execute(MockHttpServletRequest request) throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter(
                "trusted", "", true, json);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger calls = new AtomicInteger();
        filter.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> calls.incrementAndGet());
        return new Result(response.getStatus(), calls.get());
    }

    private JsonNode resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/product-authorization/" + name)) {
            assertNotNull(stream, name);
            return json.readTree(stream);
        }
    }

    private Map<String, JsonNode> routes(JsonNode document) {
        Map<String, JsonNode> result = new HashMap<>();
        document.path("routes").forEach(route -> assertThat(result.put(
                route.path("routeContractKey").asText(), route)).isNull());
        return result;
    }

    private Map<String, JsonNode> bindings(JsonNode route, String field) {
        Map<String, JsonNode> result = new HashMap<>();
        route.path(field).forEach(binding -> assertThat(result.put(
                binding.path("bindingKey").asText(), binding)).isNull());
        return result;
    }

    private record Result(int status, int ownerCalls) { }
}
