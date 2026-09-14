package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Closed document/USER DATA schemas derived from the frozen v8 Approval OpenAPI. */
final class ApprovalDocumentProjectionSchemaContract {
    private static final String RESOURCE =
            "/product-authorization/approval-document-projections-v8.generated.json";
    private static final String CHECKSUM = "8fd5bd6d861a3a45d7029ba485c9244c6b83902301cff89cddd767128b4f9327";
    private static final String CLOSURE_CHECKSUM = "26dee9603cb2b12511e0f49a773d5e8511b59a78acb5ace3ce755716735ce535";
    private static final Map<String, Definition> SCHEMAS = Map.of(
            "route.approvals.work.request-document-tools.data",
            new Definition("ApprovalDocumentTools",
                    "104ee05c1735728ba812d7cb15b3ffd09910fb2e0ef0981c9463f19d1a67eb91"),
            "route.approvals.work.task-document-tools.data",
            new Definition("ApprovalDocumentTools",
                    "104ee05c1735728ba812d7cb15b3ffd09910fb2e0ef0981c9463f19d1a67eb91"),
            "route.approvals.work.request-comments.data",
            new Definition("ApprovalDocumentComments",
                    "d657efaebafbe054c93df2a04095059388a0566c4a95fa8ec05753193b9e8922"),
            "route.approvals.work.task-comments.data",
            new Definition("ApprovalDocumentComments",
                    "d657efaebafbe054c93df2a04095059388a0566c4a95fa8ec05753193b9e8922"),
            "route.approvals.admin.document-policy.data",
            new Definition("ApprovalDocumentPolicy",
                    "e517f31c85c577a1ac61745dce9c165eec9b7c827912ae4cd7a868b2393f0f86"),
            "route.approvals.admin.document-hold.data",
            new Definition("ApprovalDocumentHold",
                    "316249dbd4512224e0891bc255ca4232c9e0f47e428f130acda812103b491ece"),
            "route.approvals.work.form-field-candidates.data",
            new Definition("ApprovalFormUserCandidates",
                    "dac88c0850351501e32155d1608441572a85b30107d96848dc4e9ee9094a5861"),
            "route.approvals.admin.form-field-candidates.data",
            new Definition("ApprovalFormUserCandidates",
                    "dac88c0850351501e32155d1608441572a85b30107d96848dc4e9ee9094a5861"));

    private ApprovalDocumentProjectionSchemaContract() { }

    static boolean isDocumentDataRoute(String routeKey) { return SCHEMAS.containsKey(routeKey); }

    static boolean matches(String routeKey, String profileKey, JsonNode projection) {
        Definition expected = SCHEMAS.get(routeKey);
        String expectedProfile = routeKey.startsWith("route.approvals.work.") ? "full-work" : "full-management";
        return expected != null && expectedProfile.equals(profileKey)
                && (routeKey + ".binding.01").equals(projection.path("apiBindingKey").asText())
                && (routeKey + "." + expectedProfile + ".projection.v1")
                .equals(projection.path("projectionPolicyKey").asText())
                && expected.schemaKey().equals(projection.path("responseSchemaKey").asText())
                && projection.path("schemaVersion").isIntegralNumber()
                && projection.path("schemaVersion").asInt() == 1
                && expected.sha256().equals(projection.path("openApiSchemaSha256").asText())
                && projection.path("additionalProperties").isBoolean()
                && !projection.path("additionalProperties").asBoolean();
    }

    static void validateResource(ObjectMapper mapper) {
        try (InputStream stream = ApprovalDocumentProjectionSchemaContract.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Approval v8 work schemas are absent");
            validateDocument(mapper, (ObjectNode) mapper.readTree(stream));
        } catch (IOException exception) {
            throw new IllegalStateException("Approval v8 work schema load failed", exception);
        }
    }

    static void validateDocument(ObjectMapper mapper, ObjectNode document) {
        ObjectNode payload = document.deepCopy();
        JsonNode checksum = payload.remove("checksum");
        require(checksum != null && CHECKSUM.equals(checksum.asText())
                        && CHECKSUM.equals(ApprovalPepProjectionLineage.sha256(mapper, payload)),
                "Approval v8 work schema checksum drift");
        require(document.path("schemaVersion").asInt() == 1
                        && "approval-document-projections-v8".equals(document.path("projectionKey").asText())
                        && "product-surfaces".equals(document.path("registryRef").path("bundleKey").asText())
                        && document.path("registryRef").path("version").asInt() == 8
                        && ApprovalPepProjectionLineage.DOCUMENT_V8_CHECKSUM
                        .equals(document.path("registryRef").path("sha256").asText())
                        && document.path("bindingCount").asInt() == 8
                        && document.path("bindings").isArray()
                        && document.path("bindings").size() == 8
                        && document.path("schemas").size() == 13
                        && CLOSURE_CHECKSUM.equals(document.path("schemaClosureSha256").asText())
                        && CLOSURE_CHECKSUM.equals(ApprovalPepProjectionLineage.sha256(
                                mapper, document.path("schemas"))),
                "Approval v8 work schema envelope drift");
        Set<String> routes = new HashSet<>();
        for (JsonNode binding : document.path("bindings")) {
            String routeKey = binding.path("routeContractKey").asText();
            require(routes.add(routeKey) && matches(
                            routeKey, binding.path("profileKey").asText(), binding),
                    "Approval v8 work schema binding drift");
        }
        require(routes.equals(SCHEMAS.keySet()), "Approval v8 work schema coverage drift");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private record Definition(String schemaKey, String sha256) { }
}
