package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Sealed release10 response graphs, including explicit OpenAPI3.1 nullable unions. */
final class ApprovalRelease10ProjectionSchemaContract {
    private static final String RESOURCE = "/product-authorization/approval-release10-projections.generated.json";
    private static final String CHECKSUM = "f6eda1dba67a3733563d27b6aa6920e0242b213b4f954ffeaac5fe81ddda8cde";
    private static final String CLOSURE = "9cdd8e9f4aef77ccb534277515c3cd55ac2f1397dbc518b0a6e21adb7f366ee6";
    private static final Set<String> FIELDS = Set.of("apiBindingKey", "projectionPolicyKey",
            "responseSchemaKey", "schemaVersion", "openApiSchemaSha256", "additionalProperties");
    private static final Map<String, Definition> DEFINITIONS = Map.ofEntries(
            Map.entry("route.approvals.admin.retention-claim.data", new Definition("full-management", "ApprovalRetentionClaim", "5c106536ba24f7054be87e382798275cafc1e81e6e9637549097d1316fb05719")),
            Map.entry("route.approvals.admin.retention-policy.data", new Definition("full-management", "ApprovalRetentionPolicy", "d3ad46a0ef742210a3bba4b9a1482ab79dd7e7afc227b86571dc8dfbd44705e3")),
            Map.entry("route.approvals.admin.retention-record.data", new Definition("full-management", "ApprovalRetentionRecord", "bd37568321e12a5bcdcc3d7e65ff2de8579ffabebad91a8b4851656b264f327b")),
            Map.entry("route.approvals.admin.workflow-planning-simulation.data", new Definition("full-management", "ApprovalWorkflowPlanningResult", "e42aa72885d0cf219bfcd037689a35c93d198009ca8524af48030f341ee5f070")),
            Map.entry("route.approvals.work.signature-audit.data", new Definition("full-work", "ApprovalSignatureAudit", "928789cc010f28497c7b7ab76139325b77f2b13afe7a11890536c6bb7380037c")),
            Map.entry("route.approvals.work.signature-command-receipt.data", new Definition("approval.signature.command-receipt.v1", "ApprovalSignatureCommandReceiptMetadata", "90ca054ab5d043930c07e62b263630e9165e90f692f26cee352c7ee232de5012")),
            Map.entry("route.approvals.work.signature-context.data", new Definition("full-work", "ApprovalSignatureContext", "1fc3f80e18997f235440d0125fe039a0c1b6ccfb52897b0ff614356df213f5bd")),
            Map.entry("route.approvals.work.signature-request.data", new Definition("full-work", "ApprovalSignatureCeremony", "41ea1a14b49147ef04a54649f82637cef45220879768d3b8ff84ddfc8fdf4489")));

    private ApprovalRelease10ProjectionSchemaContract() { }
    static boolean isRelease10DataRoute(String key) { return DEFINITIONS.containsKey(key); }

    static boolean matches(String key, String profile, JsonNode projection) {
        Definition expected = DEFINITIONS.get(key);
        if (expected == null || projection == null || !projection.isObject()) return false;
        Set<String> fields = new LinkedHashSet<>();
        projection.fieldNames().forEachRemaining(fields::add);
        return fields.equals(FIELDS) && expected.profile().equals(profile)
                && (key + ".binding.01").equals(projection.path("apiBindingKey").asText())
                && (key + '.' + profile + ".projection.v1").equals(projection.path("projectionPolicyKey").asText())
                && expected.schema().equals(projection.path("responseSchemaKey").asText())
                && projection.path("schemaVersion").isIntegralNumber() && projection.path("schemaVersion").asInt() == 1
                && expected.hash().equals(projection.path("openApiSchemaSha256").asText())
                && projection.path("additionalProperties").isBoolean() && !projection.path("additionalProperties").asBoolean();
    }

    static void validateResource(ObjectMapper mapper) {
        try (var stream = ApprovalRelease10ProjectionSchemaContract.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Approval release10 response schemas absent");
            JsonNode document = mapper.readTree(stream);
            require(document instanceof ObjectNode, "Approval release10 schema document must be an object");
            validateDocument(mapper, (ObjectNode) document);
        } catch (IOException exception) {
            throw new IllegalStateException("Approval release10 schema load failed", exception);
        }
    }

    static void validateDocument(ObjectMapper mapper, ObjectNode document) {
        ObjectNode payload = document.deepCopy();
        JsonNode checksum = payload.remove("checksum");
        require(checksum != null && CHECKSUM.equals(checksum.asText())
                && CHECKSUM.equals(ApprovalPepProjectionLineage.sha256(mapper, payload)),
                "Approval release10 response schema checksum drift");
        require(document.path("schemaVersion").asInt() == 1
                && "approval-release10-projections".equals(document.path("projectionKey").asText())
                && "product-surfaces".equals(document.path("registryRef").path("bundleKey").asText())
                && document.path("registryRef").path("version").asInt() == 10
                && ApprovalPepProjectionLineage.RELEASE10_CHECKSUM.equals(document.path("registryRef").path("sha256").asText())
                && document.path("bindingCount").asInt() == 8
                && document.path("bindings").isArray() && document.path("bindings").size() == 8
                && document.path("schemas").isObject() && document.path("schemas").size() == 17
                && CLOSURE.equals(document.path("schemaClosureSha256").asText())
                && CLOSURE.equals(ApprovalPepProjectionLineage.sha256(mapper, document.path("schemas"))),
                "Approval release10 response schema envelope drift");
        Set<String> routes = new LinkedHashSet<>();
        for (var binding : document.path("bindings")) {
            String key = binding.path("routeContractKey").asText();
            require(routes.add(key), "Approval release10 duplicate schema binding");
            ObjectNode metadata = mapper.createObjectNode();
            FIELDS.forEach(field -> metadata.set(field, binding.path(field)));
            require("JSON_RECORD".equals(binding.path("schemaKind").asText())
                    && matches(key, binding.path("profileKey").asText(), metadata),
                    "Approval release10 response schema binding drift");
        }
        require(routes.equals(DEFINITIONS.keySet()), "Approval release10 response schema coverage drift");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    private record Definition(String profile, String schema, String hash) { }
}
