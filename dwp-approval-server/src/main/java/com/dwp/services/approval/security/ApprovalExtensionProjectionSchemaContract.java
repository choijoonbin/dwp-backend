package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Actual v9 owner response records and a separate raw-binary transport contract. */
final class ApprovalExtensionProjectionSchemaContract {
    private static final String RESOURCE = "/product-authorization/approval-extension-projections-v9.generated.json";
    private static final String CHECKSUM = "fd8af0556f2192b5cba1cb2f0cb92e85fb33c7c1f2af7b8da9c6295f36a5d528";
    private static final String CLOSURE_CHECKSUM = "4a65ce626e1d22f79b955ed5d384396066a21d2fd06e0f12dedab2c54a6c8ec4";
    private static final Set<String> BASE_FIELDS = Set.of("apiBindingKey", "projectionPolicyKey", "responseSchemaKey");
    private static final Set<String> RECORD_FIELDS = Set.of("apiBindingKey", "projectionPolicyKey",
            "responseSchemaKey", "schemaVersion", "openApiSchemaSha256", "additionalProperties");
    private static final Map<String, Definition> DEFINITIONS = Map.ofEntries(
            Map.entry("route.approvals.admin.attachment-policy.data", new Definition("ApprovalAttachmentPolicy",
                    "2cb1a0df8b68b16f49f6ae27ac9858c31d11ce278d295934a8b8521a76bd7c8d", false)),
            Map.entry("route.approvals.admin.form-publish-review.data", new Definition("ApprovalFormLifecycleReview",
                    "cf84f33d56d1affbf78db19e557f13b2e3fc533961d37d5850b2dab8d10b84c2", false)),
            Map.entry("route.approvals.admin.form-version-detail.data", new Definition("ApprovalFormLifecycleVersion",
                    "fab88e34e8445ed5f9face5688f89661e527e5d34c2963dc930557c9db1a3cdf", false)),
            Map.entry("route.approvals.admin.form-version-diff.data", new Definition("ApprovalFormLifecycleDiff",
                    "0701251b9a276bba2791aa4fdcbb8767680ac95d35aaaa2164e4e30cec181fbf", false)),
            Map.entry("route.approvals.admin.form-version-history.data", new Definition("ApprovalFormLifecycleHistory",
                    "6f066bce11bab60d5d7d8db4c51aae0dc3789e06bd7af2ab8682bf78cd7739c2", false)),
            Map.entry("route.approvals.admin.form-working-draft.data", new Definition("ApprovalFormLifecycleWorkspace",
                    "472ca94cc33d0bc2a85ca8f8ac6689a9801e3c3916b1d1d33c3d3ec21046d5c5", false)),
            Map.entry("route.approvals.admin.policy-impact.data", new Definition("ApprovalPolicyImpactResult",
                    "652af4b87f0d35380d22136bfb37c7381241defb1175433561bc32368a4363e0", false)),
            Map.entry("route.approvals.work.attachment-download-content.data", new Definition("ApprovalAttachmentDownloadBytesV1",
                    "d9726a8a1015a05c5062b02bf0dbd5fe455824f7da3ab3700db95708d4c5c2bd", true)),
            Map.entry("route.approvals.work.attachment-upload.data", new Definition("ApprovalAttachmentUpload",
                    "0385b3eadafb5bb57c215262f4a4297a3ee33a572f1f2cc203d20c4eeb7dd20c", false)),
            Map.entry("route.approvals.work.information-command-receipt.data", new Definition("ApprovalInformationCommandReceipt",
                    "eff26ae76e19c6dd359e3c7941af868ed8df1aa61f84fa141a6e7701a3164458", false)),
            Map.entry("route.approvals.work.request-attachments.data", new Definition("ApprovalAttachmentAttachments",
                    "46026087765a71b0ad5ddab65dd73d048f4ffe96862cbde6688f5b3859e9dcc4", false)),
            Map.entry("route.approvals.work.task-attachments.data", new Definition("ApprovalAttachmentAttachments",
                    "46026087765a71b0ad5ddab65dd73d048f4ffe96862cbde6688f5b3859e9dcc4", false)));

    private ApprovalExtensionProjectionSchemaContract() { }

    static boolean isExtensionDataRoute(String routeKey) { return DEFINITIONS.containsKey(routeKey); }

    static boolean matches(String routeKey, String profileKey, JsonNode projection) {
        Definition expected = DEFINITIONS.get(routeKey);
        if (expected == null || projection == null || !projection.isObject()) return false;
        String expectedProfile = routeKey.startsWith("route.approvals.admin.") ? "full-management" : "full-work";
        Set<String> fields = new LinkedHashSet<>();
        projection.fieldNames().forEachRemaining(fields::add);
        return fields.equals(expected.binary() ? BASE_FIELDS : RECORD_FIELDS)
                && expectedProfile.equals(profileKey)
                && (routeKey + ".binding.01").equals(projection.path("apiBindingKey").asText())
                && (routeKey + "." + expectedProfile + ".projection.v1").equals(projection.path("projectionPolicyKey").asText())
                && expected.schemaKey().equals(projection.path("responseSchemaKey").asText())
                && (expected.binary() || (projection.path("schemaVersion").isIntegralNumber()
                && projection.path("schemaVersion").asInt() == 1
                && expected.sha256().equals(projection.path("openApiSchemaSha256").asText())
                && projection.path("additionalProperties").isBoolean()
                && !projection.path("additionalProperties").asBoolean()));
    }

    static void validateResource(ObjectMapper mapper) {
        try (InputStream stream = ApprovalExtensionProjectionSchemaContract.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Approval v9 extension schemas are absent");
            JsonNode document = mapper.readTree(stream);
            require(document instanceof ObjectNode, "Approval v9 schema document must be an object");
            validateDocument(mapper, (ObjectNode) document);
        } catch (IOException exception) {
            throw new IllegalStateException("Approval v9 extension schema load failed", exception);
        }
    }

    static void validateDocument(ObjectMapper mapper, ObjectNode document) {
        ObjectNode payload = document.deepCopy();
        JsonNode checksum = payload.remove("checksum");
        require(checksum != null && CHECKSUM.equals(checksum.asText())
                && CHECKSUM.equals(ApprovalPepProjectionLineage.sha256(mapper, payload)),
                "Approval v9 extension schema checksum drift");
        ObjectNode graph = mapper.createObjectNode();
        graph.set("schemas", document.path("schemas"));
        graph.set("binaryResponses", document.path("binaryResponses"));
        require(document.path("schemaVersion").asInt() == 1
                && "approval-extension-projections-v9".equals(document.path("projectionKey").asText())
                && "product-surfaces".equals(document.path("registryRef").path("bundleKey").asText())
                && document.path("registryRef").path("version").asInt() == 9
                && ApprovalPepProjectionLineage.EXTENSION_V9_CHECKSUM.equals(document.path("registryRef").path("sha256").asText())
                && document.path("bindingCount").asInt() == 12
                && document.path("bindings").isArray() && document.path("bindings").size() == 12
                && document.path("schemas").isObject() && document.path("schemas").size() == 24
                && document.path("binaryResponses").isObject() && document.path("binaryResponses").size() == 1
                && CLOSURE_CHECKSUM.equals(document.path("schemaClosureSha256").asText())
                && CLOSURE_CHECKSUM.equals(ApprovalPepProjectionLineage.sha256(mapper, graph)),
                "Approval v9 extension schema envelope drift");
        Set<String> routes = new HashSet<>();
        for (JsonNode binding : document.path("bindings")) {
            String routeKey = binding.path("routeContractKey").asText();
            Definition definition = DEFINITIONS.get(routeKey);
            require(definition != null && routes.add(routeKey), "Approval v9 schema binding is unknown or duplicated");
            ObjectNode metadata = mapper.createObjectNode();
            for (String field : definition.binary() ? BASE_FIELDS : RECORD_FIELDS) metadata.set(field, binding.path(field));
            require(matches(routeKey, binding.path("profileKey").asText(), metadata)
                    && definition.sha256().equals(binding.path("openApiSchemaSha256").asText())
                    && (definition.binary() ? "RAW_BINARY" : "JSON_RECORD").equals(binding.path("schemaKind").asText()),
                    "Approval v9 extension schema binding drift");
        }
        require(routes.equals(DEFINITIONS.keySet()), "Approval v9 extension schema coverage drift");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private record Definition(String schemaKey, String sha256, boolean binary) { }
}
