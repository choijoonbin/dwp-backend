package com.dwp.gateway.filter;

/** Shared header names; the public filter constants retain their original identity. */
final class ProductSurfaceDecisionHeaders {

    private ProductSurfaceDecisionHeaders() {
    }

    static final String ROUTE_HEADER = "X-DWP-Route-Contract-Key";
    static final String CURRENT_REVISION_HEADER = "X-DWP-Current-Decision-Revision";
    static final String CURRENT_REVALIDATE_AT_HEADER = "X-DWP-Current-Revalidate-At";
    static final String EXPECTED_REVISION_HEADER = "X-DWP-Expected-Decision-Revision";
    static final String RESPONSE_REVISION_HEADER = "X-DWP-Decision-Revision";
    static final String CONTEXT_HEADER = "X-DWP-Context-Key";
    static final String SCOPE_HEADER = "X-DWP-Context-Scope-Key";
    static final String ACTIVE_ACCESS_MODE_HEADER = "X-DWP-Active-Access-Mode";
    static final String SUBJECT_PRINCIPAL_HEADER =
            "X-DWP-Subject-Principal-Public-ID";
    static final String FIELD_POLICY_REVISION_HEADER =
            "X-DWP-Field-Policy-Revision";
    static final String FIELD_POLICY_KEY_HEADER = "X-DWP-Field-Policy-Key";
    static final String AUTHORIZATION_REVISION_HEADER =
            "X-DWP-Authorization-Revision";
    static final String POPULATION_KIND_HEADER = "X-DWP-Population-Kind";
    static final String PURPOSE_HEADER = "X-Purpose-Code";
    static final String EXPECTED_OBJECT_VERSION_HEADER =
            "X-DWP-Expected-Object-Version";
    static final String IF_MATCH_HEADER = "If-Match";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String HRM012_ACTION_ROUTE =
            "route.hcm.operations.workforce-ingestion.action";
    static final String HRM012_MARKER_HEADER =
            "X-DWP-HRM012-Owner-Receipt-Authority";
    static final String STEP_UP_CHALLENGE_HEADER = "X-DWP-Step-Up-Challenge";
    static final String PAY_GROUP_PUBLIC_ID_HEADER = "X-DWP-Pay-Group-Public-Id";
    static final String VERIFIED_LEGAL_ENTITY_HEADER =
            "X-DWP-Verified-Legal-Entity-Public-ID";
    static final String LEGAL_ENTITY_OWNER_EVIDENCE_HEADER =
            "X-DWP-Legal-Entity-Owner-Evidence";
    static final String MASKED_FIELD_GROUPS_HEADER =
            "X-DWP-Masked-Field-Groups";
    static final String OMITTED_FIELD_GROUPS_HEADER =
            "X-DWP-Omitted-Field-Groups";
    static final String SCOPE_QUERY_PARAMETER = ProductSurfaceScopeSelection.SCOPE_QUERY_PARAMETER;
}
