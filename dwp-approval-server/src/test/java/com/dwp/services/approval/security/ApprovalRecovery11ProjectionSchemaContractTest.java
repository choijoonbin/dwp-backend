package com.dwp.services.approval.security;

import com.dwp.core.security.ScopedAuthorityToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source11 constructor, response graph, and exact original-authority coverage. */
class ApprovalRecovery11ProjectionSchemaContractTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private ObjectNode document() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/product-authorization/approval-recovery11-projections.generated.json")) {
            return (ObjectNode) json.readTree(stream);
        }
    }

    @Test void currentConstructorRetainsAllRelease10BindingsAndAddsOnlyFiveReads() {
        var release10 = new ApprovalPilotPepRegistry(json, Clock.systemUTC(), 10);
        var current = new ApprovalPilotPepRegistry(json, Clock.systemUTC(), 11);
        assertEquals(120, current.bindingContracts().size());
        assertTrue(current.bindingContracts().containsAll(release10.bindingContracts()));
        assertEquals(5, current.bindingContracts().stream()
                .filter(binding -> !release10.bindingContracts().contains(binding)).count());
        assertTrue(current.bindingContracts().stream()
                .filter(binding -> !release10.bindingContracts().contains(binding))
                .allMatch(binding -> "GET".equals(binding.method())
                        && "DATA".equals(binding.routeKind())));
    }

    @Test void actualResponseOnlyGraphAndAllFiveProjectionsMatch() throws Exception {
        ObjectNode document = document();
        assertDoesNotThrow(() -> ApprovalRecovery11ProjectionSchemaContract
                .validateDocument(json, document));
        assertEquals(4, document.path("schemas").size());
        assertEquals(document.path("schemaClosureSha256").asText(),
                ApprovalPepProjectionLineage.sha256(json, document.path("schemas")));
        for (var binding : document.path("bindings")) {
            ObjectNode projection = json.createObjectNode();
            for (String key : List.of("apiBindingKey", "projectionPolicyKey",
                    "responseSchemaKey", "schemaVersion", "additionalProperties",
                    "openApiSchemaSha256")) projection.set(key, binding.path(key));
            String route = binding.path("routeContractKey").asText();
            String profile = binding.path("profileKey").asText();
            assertTrue(ApprovalRecovery11ProjectionSchemaContract
                    .matches(route, profile, projection));
            ObjectNode changed = projection.deepCopy();
            changed.put("openApiSchemaSha256", "0".repeat(64));
            assertFalse(ApprovalRecovery11ProjectionSchemaContract
                    .matches(route, profile, changed));
        }
    }

    @Test void recomputedMutationCannotReplaceSealedRecoveryGraph() throws Exception {
        for (String mutation : List.of("schema", "count", "missing", "alias", "method")) {
            ObjectNode document = document();
            switch (mutation) {
                case "schema" -> ((ObjectNode) document.path("schemas")
                        .path("ApprovalRetentionCommandReceipt"))
                        .put("additionalProperties", true);
                case "count" -> document.put("bindingCount", 6);
                case "missing" -> document.withArray("bindings").remove(0);
                case "alias" -> ((ObjectNode) document.path("bindings").get(0))
                        .put("routeContractKey", "route.approvals.admin.retention-command.data");
                case "method" -> ((ObjectNode) document.path("bindings").get(0))
                        .put("method", "POST");
                default -> throw new AssertionError();
            }
            document.remove("checksum");
            document.put("checksum", ApprovalPepProjectionLineage.sha256(json, document));
            assertThrows(IllegalStateException.class, () ->
                    ApprovalRecovery11ProjectionSchemaContract.validateDocument(json, document),
                    mutation);
        }
    }

    @Test void originalReceiptUsesExactReadProfileAndOriginalCapability() {
        var current = new ApprovalPilotPepRegistry(json, Clock.systemUTC(), 11);
        String role = ScopedAuthorityToken.wireToken(
                "approvals.policy.update", "ADMIN.APPROVAL_POLICY:UPDATE", "RS_APPROVALS");
        var decision = current.authorize(new ApprovalPilotPepRegistry.RequestEvidence(
                "GET", "/v1/admin/retention/policy-initialization-commands/command-1",
                Set.of("ADMIN.APPROVAL_POLICY:UPDATE"),
                "APP_CONFIG_ADMIN@RS_APPROVALS," + role, Set.of(),
                "route.approvals.admin.retention-policy-initialization-command.data",
                ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL));
        assertTrue(decision.allowed());
        var authority = assertDoesNotThrow(() -> decision.authorities().getFirst());
        assertEquals("approval.retention.command-receipt.original-authority.v1",
                authority.profileKey());
        assertEquals("approvals.policy.update", authority.capabilityContractKey());
        assertEquals(Set.of("predicate.approval.retention-command-original-authority.v1"),
                authority.predicatePolicyKeys());
        assertEquals("ApprovalRetentionCommandReceipt", authority.responseSchemaKey());
        assertTrue(authority.readOnly());
    }

    @Test void planningSelectionRequiresBothAuthoritiesInTheSameResourceSet() {
        var current = new ApprovalPilotPepRegistry(json, Clock.systemUTC(), 11);
        String workflow = ScopedAuthorityToken.wireToken(
                "approvals.admin.workflow-planning-simulation.read",
                "ADMIN.APPROVAL_WORKFLOW:UPDATE", "RS_APPROVALS");
        String form = ScopedAuthorityToken.wireToken(
                "approvals.admin.workflow-planning-form.read",
                "ACTION.APPROVAL_FORM:VIEW", "RS_APPROVALS");
        var allowed = current.authorize(selectionEvidence(
                "APP_CONFIG_ADMIN@RS_APPROVALS," + workflow + ',' + form));
        assertTrue(allowed.allowed());
        assertEquals(Set.of("approvals.admin.workflow-planning-simulation.read",
                        "approvals.admin.workflow-planning-form.read"),
                allowed.authorities().stream()
                        .map(ApprovalPilotPepRegistry.RouteAuthority::capabilityContractKey)
                        .collect(Collectors.toSet()));
        assertTrue(allowed.authorities().stream().allMatch(authority ->
                "ApprovalWorkflowPlanningSelection".equals(authority.responseSchemaKey())
                        && authority.predicatePolicyKeys().equals(
                        Set.of("predicate.approval.workflow-planning-selection.v1"))));

        String otherForm = ScopedAuthorityToken.wireToken(
                "approvals.admin.workflow-planning-form.read",
                "ACTION.APPROVAL_FORM:VIEW", "RS_OTHER");
        for (String roles : List.of(
                "APP_CONFIG_ADMIN@RS_APPROVALS," + workflow,
                "APP_CONFIG_ADMIN@RS_APPROVALS," + form,
                "APP_CONFIG_ADMIN@RS_APPROVALS,APP_CONFIG_ADMIN@RS_OTHER,"
                        + workflow + ',' + otherForm)) {
            assertFalse(current.authorize(selectionEvidence(roles)).allowed());
        }
    }

    private ApprovalPilotPepRegistry.RequestEvidence selectionEvidence(String roles) {
        return new ApprovalPilotPepRegistry.RequestEvidence(
                "GET", "/v1/admin/workflows/14d7b229-4752-4a50-8ac1-ecc129620649/planning-selection",
                Set.of("ADMIN.APPROVAL_WORKFLOW:UPDATE", "ACTION.APPROVAL_FORM:VIEW"),
                roles, Set.of(), ApprovalPep10AuthorityKeys.SELECTION_ROUTE,
                ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL);
    }
}
