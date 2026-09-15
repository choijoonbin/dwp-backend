package com.dwp.services.approval.operations;

import com.dwp.services.approval.signatureproviders.SignatureProviderOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalRelease12PepContractTest {
    private static final Map<String, String> OPERATION_PATHS = Map.of(
            "route.approvals.admin.operations.dead-letter.action",
            "/v1/admin/operations/events/{outboxId}/dead-letter",
            "route.approvals.admin.operations.replay.action",
            "/v1/admin/operations/events/{outboxId}/replay",
            "route.approvals.admin.operations.batch-retry.action",
            "/v1/admin/operations/deliveries/retry",
            "route.approvals.admin.operations.batch-dead-letter.action",
            "/v1/admin/operations/deliveries/dead-letter",
            "route.approvals.admin.operations.batch-replay.action",
            "/v1/admin/operations/deliveries/replay",
            "route.approvals.admin.operations.reconcile.action",
            "/v1/admin/operations/deliveries/reconcile",
            "route.approvals.admin.operations.task-reassign.action",
            "/v1/admin/operations/tasks/{taskId}/reassign",
            "route.approvals.admin.operations.task-batch-reassign.action",
            "/v1/admin/operations/tasks/reassign");

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void release12IsTheExactSource12Source13AndWorkCommandAppend() throws Exception {
        JsonNode previous = resource("approval-pilot-pep-v11.generated.json");
        JsonNode current = resource("approval-pilot-pep-v12.generated.json");
        Map<String, JsonNode> routes = routes(current);
        Set<String> prior = routes(previous).keySet();
        Set<String> expected = new HashSet<>(OPERATION_PATHS.keySet());
        for (SignatureProviderOperation operation : SignatureProviderOperation.values()) {
            expected.add(operation.routeContractKey());
            JsonNode route = routes.get(operation.routeContractKey());
            assertNotNull(route, operation.name());
            assertRoute(route, operation.method(), operation.pathTemplate(), capability(operation));
            assertEquals(operation.routeKind(), route.path("routeKind").asText());
            assertEquals(operation.readOnly(), route.path("sideEffectFree").asBoolean(false));
            assertEquals(operation.highRisk(), route.has("stepUpCommandBindings"), operation.name());
            if (operation.readOnly()) {
                JsonNode projection = route.path("accessProfiles").get(0)
                        .path("responseProjectionBindings").get(0);
                assertEquals(3, projection.size());
                String serialized = projection.toString().toLowerCase();
                assertFalse(serialized.contains("secret"));
                assertFalse(serialized.contains("rawpayload"));
            }
        }
        for (ApprovalOperationsProtocol.Route operation : ApprovalOperationsProtocol.Route.values()) {
            expected.add(operation.contractKey());
            JsonNode route = routes.get(operation.contractKey());
            assertNotNull(route, operation.name());
            assertRoute(route, "POST", OPERATION_PATHS.get(operation.contractKey()),
                    ApprovalOperationsProtocol.CAPABILITY);
            JsonNode stepUp = route.path("stepUpCommandBindings").get(0);
            assertNotNull(stepUp, operation.name());
            assertEquals("COMMAND_HEADER", stepUp.path("expectedObjectVersionSource").asText());
            assertEquals("X-DWP-Expected-Object-Version",
                    stepUp.path("expectedObjectVersionName").asText());
        }
        expected.add("route.approvals.work.request-resubmit-draft.action");
        expected.add("route.approvals.work.delegation-update.action");
        Set<String> added = new HashSet<>(routes.keySet());
        added.removeAll(prior);
        assertEquals(expected, added);
        assertEquals(29, added.size());
    }

    @Test
    void release12EnvelopeAndNewCapabilitiesAreSealed() throws Exception {
        JsonNode current = resource("approval-pilot-pep-v12.generated.json");
        assertEquals(12, current.path("registryRef").path("version").asInt());
        assertEquals("65155dcc88f454a0ad2530518f8ec9b0c070afd31d583a19f980dd3d10f78a74",
                current.path("registryRef").path("sha256").asText());
        assertEquals(141, current.path("projectedRouteContractCount").asInt());
        assertEquals(149, current.path("bindingPairCount").asInt());
        Map<String, JsonNode> capabilities = index(current.path("capabilities"), "contractKey");
        assertCapability(capabilities.get("approvals.signature.manage"),
                "ADMIN.APPROVAL_SIGNATURE:MANAGE", "LOW", null);
        assertCapability(capabilities.get("approvals.signature.publish"),
                "ADMIN.APPROVAL_SIGNATURE:PUBLISH", "HIGH", "STEPUP-MGMT-HIGH-V1");
    }

    private void assertRoute(JsonNode route, String method, String path, String capability) {
        JsonNode service = route.path("servicePepBindings").get(0);
        JsonNode gateway = route.path("gatewayApiBindings").get(0);
        assertEquals(method, service.path("method").asText());
        assertEquals(path, service.path("path").asText());
        assertEquals("/api/approvals" + path, gateway.path("path").asText());
        assertEquals(capability, route.path("accessProfiles").get(0)
                .path("requiredAccess").path("capabilityContractKey").asText());
    }

    private String capability(SignatureProviderOperation operation) {
        return switch (operation.permission()) {
            case "ADMIN.APPROVAL_SIGNATURE:VIEW" -> "approvals.signature.read";
            case "ADMIN.APPROVAL_SIGNATURE:MANAGE" -> "approvals.signature.manage";
            case "ADMIN.APPROVAL_SIGNATURE:PUBLISH" -> "approvals.signature.publish";
            case "ACTION.APPROVAL_REQUEST:VIEW" -> "approvals.work.signature.read";
            case "ACTION.APPROVAL_SIGNATURE:UPDATE" -> "approvals.work.signature.update";
            case "ACTION.APPROVAL_SIGNATURE:SIGN" -> "approvals.work.signature.sign";
            default -> throw new AssertionError(operation.permission());
        };
    }

    private void assertCapability(JsonNode capability, String permission,
            String risk, String activation) {
        assertNotNull(capability);
        assertEquals(permission, capability.path("resolvedCapabilityCode").asText());
        assertEquals(risk, capability.path("riskTier").asText());
        assertEquals("APP_CONFIG_ADMIN", capability.path("requiredResponsibilityCode").asText());
        if (activation == null) assertTrue(capability.path("activationPolicy").isNull());
        else assertEquals(activation, capability.path("activationPolicy").asText());
    }

    private JsonNode resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/product-authorization/" + name)) {
            assertNotNull(stream, name);
            return json.readTree(stream);
        }
    }

    private Map<String, JsonNode> routes(JsonNode document) {
        return index(document.path("routes"), "routeContractKey");
    }

    private Map<String, JsonNode> index(JsonNode values, String field) {
        Map<String, JsonNode> result = new HashMap<>();
        values.forEach(value -> assertTrue(result.put(value.path(field).asText(), value) == null));
        return result;
    }
}
