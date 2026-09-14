package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import java.util.Map;

/** Exact response-only pins for the immutable v8 document and USER DATA descriptors. */
final class ProductAuthorizationDocumentProjectionSchema {
    private static final Map<String, Map.Entry<String, String>> SCHEMAS = Map.of(
            "route.approvals.work.request-document-tools.data",
            Map.entry("ApprovalDocumentTools", "104ee05c1735728ba812d7cb15b3ffd09910fb2e0ef0981c9463f19d1a67eb91"),
            "route.approvals.work.task-document-tools.data",
            Map.entry("ApprovalDocumentTools", "104ee05c1735728ba812d7cb15b3ffd09910fb2e0ef0981c9463f19d1a67eb91"),
            "route.approvals.work.request-comments.data",
            Map.entry("ApprovalDocumentComments", "d657efaebafbe054c93df2a04095059388a0566c4a95fa8ec05753193b9e8922"),
            "route.approvals.work.task-comments.data",
            Map.entry("ApprovalDocumentComments", "d657efaebafbe054c93df2a04095059388a0566c4a95fa8ec05753193b9e8922"),
            "route.approvals.admin.document-policy.data",
            Map.entry("ApprovalDocumentPolicy", "e517f31c85c577a1ac61745dce9c165eec9b7c827912ae4cd7a868b2393f0f86"),
            "route.approvals.admin.document-hold.data",
            Map.entry("ApprovalDocumentHold", "316249dbd4512224e0891bc255ca4232c9e0f47e428f130acda812103b491ece"),
            "route.approvals.work.form-field-candidates.data",
            Map.entry("ApprovalFormUserCandidates", "dac88c0850351501e32155d1608441572a85b30107d96848dc4e9ee9094a5861"),
            "route.approvals.admin.form-field-candidates.data",
            Map.entry("ApprovalFormUserCandidates", "dac88c0850351501e32155d1608441572a85b30107d96848dc4e9ee9094a5861"));

    private ProductAuthorizationDocumentProjectionSchema() { }

    static boolean isDocumentDataRoute(String key) { return SCHEMAS.containsKey(key); }

    static boolean matches(String key, String profileKey,
            ProductAuthorizationContractDtos.ResponseProjectionBinding projection) {
        Map.Entry<String, String> expected = SCHEMAS.get(key);
        String expectedProfile = key.startsWith("route.approvals.work.") ? "full-work" : "full-management";
        return expected != null && expectedProfile.equals(profileKey)
                && (key + ".binding.01").equals(projection.apiBindingKey())
                && (key + "." + expectedProfile + ".projection.v1").equals(projection.projectionPolicyKey())
                && expected.getKey().equals(projection.responseSchemaKey())
                && Integer.valueOf(1).equals(projection.schemaVersion())
                && expected.getValue().equals(projection.openApiSchemaSha256())
                && Boolean.FALSE.equals(projection.additionalProperties());
    }
}
