package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Sealed Source11 recovery response graphs derived from an actual owner export. */
final class ApprovalRecovery11ProjectionSchemaContract {
    private static final String RESOURCE =
            "/product-authorization/approval-recovery11-projections.generated.json";
    private static final String CHECKSUM =
            "8ab843579dd0e0b9b4a535a103b91ef490bb7912a4fcfad7a58f5a26dde703ce";
    private static final String CLOSURE =
            "cacbec324f3836582ffbe85f22fcf2002f5d9d2ab6948ce90685807d9fbb2149";
    private static final Set<String> FIELDS = Set.of(
            "apiBindingKey", "projectionPolicyKey", "responseSchemaKey",
            "schemaVersion", "openApiSchemaSha256", "additionalProperties");
    private static final String RECEIPT_PROFILE =
            "approval.retention.command-receipt.original-authority.v1";
    private static final String RECEIPT_SCHEMA = "ApprovalRetentionCommandReceipt";
    private static final String RECEIPT_HASH =
            "95d520bbcaee4a7c3d2283cb18599d457b441e07e516cf7e335987d696eb8fbd";
    private static final Map<String, Definition> DEFINITIONS = Map.of(
            "route.approvals.admin.retention-policy-initialization-command.data",
            new Definition(RECEIPT_PROFILE, RECEIPT_SCHEMA, RECEIPT_HASH,
                    "/v1/admin/retention/policy-initialization-commands/{idempotencyKey}", "*/*"),
            "route.approvals.admin.retention-policy-draft-command.data",
            new Definition(RECEIPT_PROFILE, RECEIPT_SCHEMA, RECEIPT_HASH,
                    "/v1/admin/retention/policies/{policyId}/draft-commands/{idempotencyKey}", "*/*"),
            "route.approvals.admin.retention-policy-publication-command.data",
            new Definition(RECEIPT_PROFILE, RECEIPT_SCHEMA, RECEIPT_HASH,
                    "/v1/admin/retention/policies/{policyId}/publication-commands/{idempotencyKey}", "*/*"),
            "route.approvals.admin.retention-record-command.data",
            new Definition(RECEIPT_PROFILE, RECEIPT_SCHEMA, RECEIPT_HASH,
                    "/v1/admin/retention/records/{requestId}/claim-commands/{idempotencyKey}", "*/*"),
            "route.approvals.admin.workflow-planning-selection.data",
            new Definition("full-management", "ApprovalWorkflowPlanningSelection",
                    "ca0ac253c38917b2d75c1793ffda73de9797fd9840edf0c5b558c62f6bd039f3",
                    "/v1/admin/workflows/{workflowId}/planning-selection", "application/json"));

    private ApprovalRecovery11ProjectionSchemaContract() { }

    static boolean isRecovery11DataRoute(String key) {
        return DEFINITIONS.containsKey(key);
    }

    static boolean isOriginalAuthorityReceiptRoute(String key) {
        Definition definition = DEFINITIONS.get(key);
        return definition != null && RECEIPT_PROFILE.equals(definition.profile());
    }

    static boolean matches(String key, String profile, JsonNode projection) {
        Definition expected = DEFINITIONS.get(key);
        if (expected == null || projection == null || !projection.isObject()) return false;
        Set<String> fields = new LinkedHashSet<>();
        projection.fieldNames().forEachRemaining(fields::add);
        return fields.equals(FIELDS)
                && expected.profile().equals(profile)
                && (key + ".binding.01").equals(projection.path("apiBindingKey").asText())
                && (key + '.' + profile + ".projection.v1")
                .equals(projection.path("projectionPolicyKey").asText())
                && expected.schema().equals(projection.path("responseSchemaKey").asText())
                && projection.path("schemaVersion").isIntegralNumber()
                && projection.path("schemaVersion").asInt() == 1
                && expected.hash().equals(projection.path("openApiSchemaSha256").asText())
                && projection.path("additionalProperties").isBoolean()
                && !projection.path("additionalProperties").asBoolean();
    }

    static void validateResource(ObjectMapper mapper) {
        try (var stream = ApprovalRecovery11ProjectionSchemaContract.class
                .getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException(
                    "Approval recovery11 response schemas absent");
            JsonNode document = mapper.readTree(stream);
            require(document instanceof ObjectNode,
                    "Approval recovery11 schema document must be an object");
            validateDocument(mapper, (ObjectNode) document);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Approval recovery11 schema load failed", exception);
        }
    }

    static void validateDocument(ObjectMapper mapper, ObjectNode document) {
        ObjectNode payload = document.deepCopy();
        JsonNode checksum = payload.remove("checksum");
        require(checksum != null && CHECKSUM.equals(checksum.asText())
                        && CHECKSUM.equals(ApprovalPepProjectionLineage.sha256(mapper, payload)),
                "Approval recovery11 response schema checksum drift");
        require(document.path("schemaVersion").asInt() == 1
                        && "approval-recovery11-projections".equals(
                        document.path("projectionKey").asText())
                        && "product-surfaces".equals(
                        document.path("registryRef").path("bundleKey").asText())
                        && document.path("registryRef").path("version").asInt() == 11
                        && ApprovalPepProjectionLineage.RECOVERY11_CHECKSUM.equals(
                        document.path("registryRef").path("sha256").asText())
                        && document.path("bindingCount").asInt() == 5
                        && document.path("bindings").isArray()
                        && document.path("bindings").size() == 5
                        && document.path("schemas").isObject()
                        && document.path("schemas").size() == 4
                        && CLOSURE.equals(document.path("schemaClosureSha256").asText())
                        && CLOSURE.equals(ApprovalPepProjectionLineage.sha256(
                        mapper, document.path("schemas"))),
                "Approval recovery11 response schema envelope drift");
        Set<String> routes = new LinkedHashSet<>();
        for (JsonNode binding : document.path("bindings")) {
            String key = binding.path("routeContractKey").asText();
            Definition definition = DEFINITIONS.get(key);
            require(definition != null && routes.add(key),
                    "Approval recovery11 duplicate or unknown schema binding");
            ObjectNode metadata = mapper.createObjectNode();
            FIELDS.forEach(field -> metadata.set(field, binding.path(field)));
            require("JSON_RECORD".equals(binding.path("schemaKind").asText())
                            && "GET".equals(binding.path("method").asText())
                            && definition.servicePath().equals(
                            binding.path("servicePath").asText())
                            && binding.path("contentTypes").isArray()
                            && binding.path("contentTypes").size() == 1
                            && definition.contentType().equals(
                            binding.path("contentTypes").get(0).asText())
                            && matches(key, binding.path("profileKey").asText(), metadata),
                    "Approval recovery11 response schema binding drift");
        }
        require(routes.equals(DEFINITIONS.keySet()),
                "Approval recovery11 response schema coverage drift");
        JsonNode receipt = document.path("schemas").path(RECEIPT_SCHEMA);
        require(receipt.path("additionalProperties").isBoolean()
                        && !receipt.path("additionalProperties").asBoolean()
                        && receipt.path("properties").size() == 14
                        && receipt.path("properties").path("status").path("enum").size() == 1
                        && "COMMITTED".equals(receipt.path("properties").path("status")
                        .path("enum").get(0).asText()),
                "Approval recovery11 receipt schema drift");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private record Definition(
            String profile, String schema, String hash,
            String servicePath, String contentType) { }
}
