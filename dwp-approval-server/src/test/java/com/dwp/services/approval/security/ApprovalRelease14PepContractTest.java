package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalRelease14PepContractTest {
    private static final String CANDIDATES =
            "route.approvals.admin.form-publish-review-candidates.data";
    private static final String QUEUE =
            "route.approvals.admin.form-publish-review-queue.data";
    private static final String CURRENT =
            "route.approvals.admin.form-publish-review-request.data";
    private static final String REQUEST =
            "route.approvals.admin.form-publish-review-request.action";
    private static final String REJECT =
            "route.approvals.admin.form-publish-review-reject.action";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void release14IsTheExactFormPublishReviewAppend() throws Exception {
        JsonNode previous = resource("approval-pilot-pep-v13.generated.json");
        JsonNode current = resource("approval-pilot-pep-v14.generated.json");
        Map<String, JsonNode> routes = routes(current);
        Set<String> added = new HashSet<>(routes.keySet());
        added.removeAll(routes(previous).keySet());

        assertEquals(Set.of(CANDIDATES, QUEUE, CURRENT, REQUEST, REJECT), added);
        assertRoute(routes.get(CANDIDATES), "DATA", "GET",
                "/v1/admin/forms/publish-review-candidates", "approvals.design.read", Set.of());
        assertRoute(routes.get(QUEUE), "DATA", "GET",
                "/v1/admin/forms/publish-review-requests", "approvals.design.read", Set.of());
        assertRoute(routes.get(CURRENT), "DATA", "GET",
                "/v1/admin/forms/{formId}/publish-review-request", "approvals.design.read", Set.of());
        assertRoute(routes.get(REQUEST), "ACTION", "POST",
                "/v1/admin/forms/{formId}/publish-review-request", "approvals.design.update",
                Set.of("predicate.approval.object-version.v1"));
        assertRoute(routes.get(REJECT), "ACTION", "POST",
                "/v1/admin/forms/{formId}/publish-review-requests/{requestId}/reject",
                "approvals.design.publish", Set.of("predicate.approval.object-version.v1"));
        assertFalse(routes.get(REJECT).has("stepUpCommandBindings"));

        for (String routeKey : Set.of(CANDIDATES, QUEUE, CURRENT)) {
            JsonNode projection = routes.get(routeKey).path("accessProfiles").get(0)
                    .path("responseProjectionBindings").get(0);
            assertEquals(Set.of("apiBindingKey", "projectionPolicyKey", "responseSchemaKey"),
                    fieldNames(projection));
            String serialized = projection.toString().toLowerCase();
            assertFalse(serialized.contains("secret"));
            assertFalse(serialized.contains("rawpayload"));
        }
    }

    @Test
    void release14EnvelopeAndRuntimeRegistryAreSealed() throws Exception {
        JsonNode current = resource("approval-pilot-pep-v14.generated.json");
        assertEquals(14, current.path("registryRef").path("version").asInt());
        assertEquals("7ee0bac12ddfbc72dda55a5014c67b0798caa68a5ffc73b4be479d06a4590336",
                current.path("registryRef").path("sha256").asText());
        assertEquals("5797318cfe765d77f89d99837d4da70e0452e97a517b99e0d7aa9a0f806a00d7",
                current.path("projectionChecksum").asText());
        assertEquals(149, current.path("projectedRouteContractCount").asInt());
        assertEquals(157, current.path("bindingPairCount").asInt());

        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(json, Clock.systemUTC(), 14);
        assertEquals(157, registry.bindingContracts().size());
        assertTrue(registry.bindingContracts().stream().map(
                ApprovalPilotPepRegistry.BindingContract::routeContractKey)
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(Set.of(CANDIDATES, QUEUE, CURRENT, REQUEST, REJECT)));
    }

    private void assertRoute(JsonNode route, String kind, String method, String path,
            String capability, Set<String> predicates) {
        assertNotNull(route);
        JsonNode service = route.path("servicePepBindings").get(0);
        JsonNode gateway = route.path("gatewayApiBindings").get(0);
        JsonNode profile = route.path("accessProfiles").get(0);
        assertEquals(kind, route.path("routeKind").asText());
        assertEquals(method, service.path("method").asText());
        assertEquals(path, service.path("path").asText());
        assertEquals("/api/approvals" + path, gateway.path("path").asText());
        assertEquals(capability, profile.path("requiredAccess")
                .path("capabilityContractKey").asText());
        assertEquals(predicates, values(profile.path("predicatePolicyKeys")));
        assertEquals("DATA".equals(kind), profile.path("readOnly").asBoolean());
    }

    private JsonNode resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/product-authorization/" + name)) {
            assertNotNull(stream, name);
            return json.readTree(stream);
        }
    }

    private Map<String, JsonNode> routes(JsonNode document) {
        Map<String, JsonNode> result = new HashMap<>();
        document.path("routes").forEach(value -> assertTrue(result.put(
                value.path("routeContractKey").asText(), value) == null));
        return result;
    }

    private Set<String> values(JsonNode values) {
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private Set<String> fieldNames(JsonNode object) {
        Set<String> result = new HashSet<>();
        object.fieldNames().forEachRemaining(result::add);
        return result;
    }
}
