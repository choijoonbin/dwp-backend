package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Closed work DATA schemas derived from the frozen v7 Approval OpenAPI. */
final class ApprovalWorkProjectionSchemaContract {
    private static final String RESOURCE =
            "/product-authorization/approval-work-projections-v7.generated.json";
    private static final String CHECKSUM = "c25ac256424a210ef0834170f32857472bf8a5c74d1539551da34efb69cd5152";
    private static final String CLOSURE_CHECKSUM = "92400af19e64105677869bfa2d4f15b4da3eb83fd278f00bbeb08c82b44c308f";
    private static final Map<String, Definition> SCHEMAS = Map.of(
            "route.approvals.work.draft-command-reconciliation.data",
            new Definition("DraftReconciliation",
                    "5f077c0c323ef1821cbc5f3afdfd120899ef576d57099973a4f3883cb3a120cd"),
            "route.approvals.work.request-draft-revision.data",
            new Definition("DraftRevisionDetail",
                    "953f4fa55851c3e2ee3b4cafb406ab0a01fd96556d8cbc545f326949d8f9822a"),
            "route.approvals.work.request-draft-revisions.data",
            new Definition("PageDraftRevision",
                    "bccf9505e49ba2d0ad81b3ab7263751a511529764dd87e6e2e7161fe167f6ce6"),
            "route.approvals.work.requests-search.data",
            new Definition("PageRequestSummary",
                    "6d9c0c9fd273bee297f97d44f91b7147f3f966bc0c090c6712b2ebe62aaba46a"),
            "route.approvals.work.tasks-search.data",
            new Definition("PageTaskSummary",
                    "27ab4bbfb93395f73d300d67ad423f7cf5f4eb04a6b9025ab665711b6334b54a"));

    private ApprovalWorkProjectionSchemaContract() { }

    static boolean isWorkDataRoute(String routeKey) { return SCHEMAS.containsKey(routeKey); }

    static boolean matches(String routeKey, String profileKey, JsonNode projection) {
        Definition expected = SCHEMAS.get(routeKey);
        return expected != null && "full-work".equals(profileKey)
                && (routeKey + ".binding.01").equals(projection.path("apiBindingKey").asText())
                && (routeKey + ".full-work.projection.v1")
                .equals(projection.path("projectionPolicyKey").asText())
                && expected.schemaKey().equals(projection.path("responseSchemaKey").asText())
                && projection.path("schemaVersion").isIntegralNumber()
                && projection.path("schemaVersion").asInt() == 1
                && expected.sha256().equals(projection.path("openApiSchemaSha256").asText())
                && projection.path("additionalProperties").isBoolean()
                && !projection.path("additionalProperties").asBoolean();
    }

    static void validateResource(ObjectMapper mapper) {
        try (InputStream stream = ApprovalWorkProjectionSchemaContract.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Approval v7 work schemas are absent");
            validateDocument(mapper, (ObjectNode) mapper.readTree(stream));
        } catch (IOException exception) {
            throw new IllegalStateException("Approval v7 work schema load failed", exception);
        }
    }

    static void validateDocument(ObjectMapper mapper, ObjectNode document) {
        ObjectNode payload = document.deepCopy();
        JsonNode checksum = payload.remove("checksum");
        require(checksum != null && CHECKSUM.equals(checksum.asText())
                        && CHECKSUM.equals(ApprovalPepProjectionLineage.sha256(mapper, payload)),
                "Approval v7 work schema checksum drift");
        require(document.path("schemaVersion").asInt() == 1
                        && "approval-work-projections-v7".equals(document.path("projectionKey").asText())
                        && "product-surfaces".equals(document.path("registryRef").path("bundleKey").asText())
                        && document.path("registryRef").path("version").asInt() == 7
                        && ApprovalPepProjectionLineage.WORK_V7_CHECKSUM
                        .equals(document.path("registryRef").path("sha256").asText())
                        && document.path("bindingCount").asInt() == 5
                        && document.path("bindings").isArray()
                        && document.path("bindings").size() == 5
                        && document.path("schemas").size() == 10
                        && CLOSURE_CHECKSUM.equals(document.path("schemaClosureSha256").asText())
                        && CLOSURE_CHECKSUM.equals(ApprovalPepProjectionLineage.sha256(
                                mapper, document.path("schemas"))),
                "Approval v7 work schema envelope drift");
        Set<String> routes = new HashSet<>();
        for (JsonNode binding : document.path("bindings")) {
            String routeKey = binding.path("routeContractKey").asText();
            require(routes.add(routeKey) && matches(
                            routeKey, binding.path("profileKey").asText(), binding),
                    "Approval v7 work schema binding drift");
        }
        require(routes.equals(SCHEMAS.keySet()), "Approval v7 work schema coverage drift");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private record Definition(String schemaKey, String sha256) { }
}

