package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dwp.core.security.ScopedAuthorityToken;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact constructor/projection gates, independent of source activation or grants. */
class ApprovalRelease10ProjectionSchemaContractTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private ObjectNode document() throws Exception {
        try (var stream = getClass().getResourceAsStream("/product-authorization/approval-release10-projections.generated.json")) {
            return (ObjectNode) json.readTree(stream);
        }
    }
    @Test void actualClosedConstructorRetainsAllOldBindingsAndHasExactRelease10Counts() {
        var current = new ApprovalPilotPepRegistry(json, Clock.systemUTC(), 10);
        assertEquals(115, current.bindingContracts().size());
        for (int version : List.of(2,7,8,9)) {
            var old = new ApprovalPilotPepRegistry(json, Clock.systemUTC(), version);
            assertTrue(current.bindingContracts().containsAll(old.bindingContracts()));
        }
        assertThrows(IllegalStateException.class, () -> new ApprovalPilotPepRegistry(json, Clock.systemUTC(), 11));
    }
    @Test void actualResponseOnlyGraphAndAllEightProjectionsMatch() throws Exception {
        var document = document();
        assertDoesNotThrow(() -> ApprovalRelease10ProjectionSchemaContract.validateDocument(json, document));
        assertEquals(17, document.path("schemas").size());
        assertEquals(document.path("schemaClosureSha256").asText(),
                ApprovalPepProjectionLineage.sha256(json, document.path("schemas")));
        for (var binding : document.path("bindings")) {
            var projection = json.createObjectNode();
            for (String key : List.of("apiBindingKey","projectionPolicyKey","responseSchemaKey",
                    "schemaVersion","additionalProperties","openApiSchemaSha256")) projection.set(key, binding.path(key));
            String route = binding.path("routeContractKey").asText();
            String profile = binding.path("profileKey").asText();
            assertTrue(ApprovalRelease10ProjectionSchemaContract.matches(route, profile, projection));
            for (String key : List.of("responseSchemaKey","schemaVersion","additionalProperties","openApiSchemaSha256","unknown")) {
                var changed = projection.deepCopy(); changed.put(key, "fabricated");
                assertFalse(ApprovalRelease10ProjectionSchemaContract.matches(route, profile, changed), key);
            }
        }
    }
    @Test void corruptedOrRecomputedResponseGraphCannotReplaceSealedArtifact() throws Exception {
        for (String mutation : List.of("schema","count","missing","alias","extra","type")) {
            var document = document();
            switch (mutation) {
                case "schema" -> ((ObjectNode) document.path("schemas").path("ApprovalSignatureCeremony")).put("additionalProperties", true);
                case "count" -> document.put("bindingCount", 9);
                case "missing" -> document.withArray("bindings").remove(0);
                case "alias" -> ((ObjectNode) document.path("bindings").get(0)).put("routeContractKey","route.approvals.admin.retention-claim.read");
                case "extra" -> document.withArray("bindings").add(document.path("bindings").get(0).deepCopy());
                case "type" -> document.put("schemaVersion", "1");
                default -> throw new AssertionError();
            }
            document.remove("checksum"); document.put("checksum", ApprovalPepProjectionLineage.sha256(json, document));
            assertThrows(IllegalStateException.class, () -> ApprovalRelease10ProjectionSchemaContract.validateDocument(json, document), mutation);
        }
    }
    @Test void planningContributesExactlyTwoCurrentSameScopeAuthoritiesAndRejectsEitherMissingHalf() {
        var current = new ApprovalPilotPepRegistry(json, Clock.systemUTC(), 10);
        String workflow = ScopedAuthorityToken.wireToken("approvals.admin.workflow-planning-simulation.read",
                "ADMIN.APPROVAL_WORKFLOW:UPDATE", "RS_APPROVALS");
        String form = ScopedAuthorityToken.wireToken("approvals.admin.workflow-planning-form.read",
                "ACTION.APPROVAL_FORM:VIEW", "RS_APPROVALS");
        var allowed = current.authorize(planningEvidence("APP_CONFIG_ADMIN@RS_APPROVALS," + workflow + ',' + form));
        assertTrue(allowed.allowed()); assertEquals(2, allowed.authorities().size());
        assertEquals(Set.of("approvals.admin.workflow-planning-simulation.read", "approvals.admin.workflow-planning-form.read"),
                allowed.authorities().stream().map(ApprovalPilotPepRegistry.RouteAuthority::capabilityContractKey).collect(java.util.stream.Collectors.toSet()));
        for (var authority : allowed.authorities()) {
            assertTrue(authority.readOnly()); assertFalse(authority.highRisk());
            assertEquals("full-management", authority.profileKey());
            assertEquals(Set.of("predicate.approval.workflow-planning-simulation.v1"), authority.predicatePolicyKeys());
            assertEquals("ApprovalWorkflowPlanningResult", authority.responseSchemaKey());
            assertFalse(authority.projectionAdditionalProperties());
        }
        String other = ScopedAuthorityToken.wireToken("approvals.admin.workflow-planning-form.read", "ACTION.APPROVAL_FORM:VIEW", "RS_OTHER");
        for (String roles : List.of("APP_CONFIG_ADMIN@RS_APPROVALS," + workflow, "APP_CONFIG_ADMIN@RS_APPROVALS," + form,
                "APP_CONFIG_ADMIN@RS_APPROVALS,APP_CONFIG_ADMIN@RS_OTHER," + workflow + ',' + other,
                workflow + ',' + form)) {
            var denied = current.authorize(planningEvidence(roles)); assertFalse(denied.allowed()); assertTrue(denied.authorities().isEmpty());
        }
    }
    @Test void planningDoesNotAcceptAnyDuplicateAliasOrSingleCapabilityFallback() throws Exception {
        var access = (ObjectNode) json.readTree("{\"type\":\"CAPABILITY_EXPRESSION\",\"mode\":\"ALL\",\"capabilityContractKeys\":[\"approvals.admin.workflow-planning-simulation.read\",\"approvals.admin.workflow-planning-form.read\"]}");
        assertEquals(2, ApprovalPep10AuthorityKeys.planning("DATA", "full-management", true, access).size());
        for (String mutation : List.of("ANY", "duplicate", "alias", "extra", "type")) {
            var changed = access.deepCopy();
            switch (mutation) {
                case "ANY" -> changed.put("mode", "ANY");
                case "duplicate" -> changed.withArray("capabilityContractKeys").set(1, changed.path("capabilityContractKeys").get(0));
                case "alias" -> changed.withArray("capabilityContractKeys").set(1, json.getNodeFactory().textNode("approvals.admin.workflow-planning-form.view"));
                case "extra" -> changed.put("capabilityContractKey", "approvals.design.read");
                case "type" -> changed.withArray("capabilityContractKeys").set(1, json.getNodeFactory().numberNode(1));
                default -> throw new AssertionError();
            }
            assertThrows(IllegalStateException.class, () -> ApprovalPep10AuthorityKeys.planning("DATA", "full-management", true, changed), mutation);
        }
        assertThrows(IllegalStateException.class, () -> ApprovalPep10AuthorityKeys.planning("ACTION", "full-management", true, access));
        assertThrows(IllegalStateException.class, () -> ApprovalPep10AuthorityKeys.planning("DATA", "full-management", false, access));
    }
    private ApprovalPilotPepRegistry.RequestEvidence planningEvidence(String roles) {
        return new ApprovalPilotPepRegistry.RequestEvidence("POST", "/v1/admin/workflows/14d7b229-4752-4a50-8ac1-ecc129620649/versions/39c64f11-deef-4f2d-91ed-f5d5ed6463e9/simulation",
                Set.of("ADMIN.APPROVAL_WORKFLOW:UPDATE", "ACTION.APPROVAL_FORM:VIEW"), roles, Set.of(),
                ApprovalPep10AuthorityKeys.ROUTE, ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL);
    }
}
