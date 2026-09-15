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

class ApprovalRelease13PepContractTest {
    private static final String PREVIEW =
            "route.approvals.work.request-draft-migration-preview.data";
    private static final String MIGRATE =
            "route.approvals.work.request-draft-migrate.action";
    private static final String POLICY_CREATE =
            "route.approvals.admin.policy-create.action";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void release13IsTheExactApr07AndApr15Append() throws Exception {
        JsonNode previous = resource("approval-pilot-pep-v12.generated.json");
        JsonNode current = resource("approval-pilot-pep-v13.generated.json");
        Map<String, JsonNode> routes = routes(current);
        Set<String> added = new HashSet<>(routes.keySet());
        added.removeAll(routes(previous).keySet());

        assertEquals(Set.of(PREVIEW, MIGRATE, POLICY_CREATE), added);
        assertRoute(routes.get(PREVIEW), "DATA", "GET",
                "/v1/requests/{requestId}/draft/migration-preview",
                "approvals.work.request.read", Set.of("predicate.approval.own-request.v1"));
        assertRoute(routes.get(MIGRATE), "ACTION", "POST",
                "/v1/requests/{requestId}/draft/migrate",
                "approvals.work.request.update", Set.of(
                        "predicate.approval.own-request.v1",
                        "predicate.approval.object-version.v1"));
        assertRoute(routes.get(POLICY_CREATE), "ACTION", "POST",
                "/v1/admin/policies", "approvals.policy.update", Set.of());

        JsonNode projection = routes.get(PREVIEW).path("accessProfiles").get(0)
                .path("responseProjectionBindings").get(0);
        assertEquals(Set.of("apiBindingKey", "projectionPolicyKey", "responseSchemaKey"),
                fieldNames(projection));
        assertFalse(projection.toString().toLowerCase().contains("secret"));
        assertFalse(projection.toString().toLowerCase().contains("rawpayload"));
    }

    @Test
    void release13EnvelopeAndRuntimeRegistryAreSealed() throws Exception {
        JsonNode current = resource("approval-pilot-pep-v13.generated.json");
        assertEquals(13, current.path("registryRef").path("version").asInt());
        assertEquals("3bd67d7b145c5b7c845788c70f8884c8afadedd9920de419ecd1e1d0e8a4c8b0",
                current.path("registryRef").path("sha256").asText());
        assertEquals(144, current.path("projectedRouteContractCount").asInt());
        assertEquals(152, current.path("bindingPairCount").asInt());

        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(
                json, Clock.systemUTC(), 13);
        assertEquals(152, registry.bindingContracts().size());
        assertTrue(registry.bindingContracts().stream().map(
                ApprovalPilotPepRegistry.BindingContract::routeContractKey)
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(Set.of(PREVIEW, MIGRATE, POLICY_CREATE)));
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
