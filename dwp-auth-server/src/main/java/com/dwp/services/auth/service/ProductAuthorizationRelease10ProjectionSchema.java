package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Closed response-only release10 descriptors; immutable release admission is independently pinned. */
final class ProductAuthorizationRelease10ProjectionSchema {
    private static final Map<String, Schema> SCHEMAS = Map.ofEntries(
Map.entry("route.approvals.admin.retention-claim.data", new Schema("full-management", "GET", "/v1/admin/retention/claims/{claimId}", "ApprovalRetentionClaim", "5c106536ba24f7054be87e382798275cafc1e81e6e9637549097d1316fb05719", List.of("approvals.operations.read"), List.of(), true)),
            Map.entry("route.approvals.admin.retention-policy.data", new Schema("full-management", "GET", "/v1/admin/retention/policy", "ApprovalRetentionPolicy", "d3ad46a0ef742210a3bba4b9a1482ab79dd7e7afc227b86571dc8dfbd44705e3", List.of("approvals.policy.read"), List.of(), true)),
            Map.entry("route.approvals.admin.retention-record.data", new Schema("full-management", "GET", "/v1/admin/retention/records/{requestId}", "ApprovalRetentionRecord", "bd37568321e12a5bcdcc3d7e65ff2de8579ffabebad91a8b4851656b264f327b", List.of("approvals.operations.read"), List.of(), true)),
            Map.entry("route.approvals.admin.workflow-planning-simulation.data", new Schema("full-management", "POST", "/v1/admin/workflows/{workflowId}/versions/{versionId}/simulation", "ApprovalWorkflowPlanningResult", "e42aa72885d0cf219bfcd037689a35c93d198009ca8524af48030f341ee5f070", List.of("approvals.admin.workflow-planning-form.read", "approvals.admin.workflow-planning-simulation.read"), List.of("predicate.approval.workflow-planning-simulation.v1"), true)),
            Map.entry("route.approvals.work.signature-audit.data", new Schema("full-work", "GET", "/v1/signature-requests/{signatureRequestId}/audit", "ApprovalSignatureAudit", "928789cc010f28497c7b7ab76139325b77f2b13afe7a11890536c6bb7380037c", List.of("approvals.work.signature.read"), List.of("predicate.approval.signature-source.v1"), false)),
            Map.entry("route.approvals.work.signature-command-receipt.data", new Schema("approval.signature.command-receipt.v1", "GET", "/v1/signature-command-receipts/{idempotencyKey}", "ApprovalSignatureCommandReceiptMetadata", "90ca054ab5d043930c07e62b263630e9165e90f692f26cee352c7ee232de5012", List.of("approvals.work.signature.read"), List.of("predicate.approval.signature-command-receipt.v1"), true)),
            Map.entry("route.approvals.work.signature-context.data", new Schema("full-work", "GET", "/v1/requests/{requestId}/signature-context", "ApprovalSignatureContext", "1fc3f80e18997f235440d0125fe039a0c1b6ccfb52897b0ff614356df213f5bd", List.of("approvals.work.signature.read"), List.of("predicate.approval.signature-source.v1"), false)),
            Map.entry("route.approvals.work.signature-request.data", new Schema("full-work", "GET", "/v1/signature-requests/{signatureRequestId}", "ApprovalSignatureCeremony", "41ea1a14b49147ef04a54649f82637cef45220879768d3b8ff84ddfc8fdf4489", List.of("approvals.work.signature.read"), List.of("predicate.approval.signature-source.v1"), false)));
    private static final Set<String> SCHEMA_KEYS = SCHEMAS.values().stream()
            .map(Schema::key).collect(Collectors.toUnmodifiableSet());

    private ProductAuthorizationRelease10ProjectionSchema() { }
    static boolean isRelease10DataRoute(String key) { return SCHEMAS.containsKey(key); }

    static boolean matches(ProductAuthorizationContractDtos.GovernedRoute route, String profileKey,
            ProductAuthorizationContractDtos.ResponseProjectionBinding projection) {
        Schema schema = SCHEMAS.get(route.routeContractKey());
        if (schema == null || projection == null || !"DATA".equals(route.routeKind())
                || !Boolean.TRUE.equals(route.sideEffectFree()) || route.subject() == null
                || !"PRODUCT".equals(route.subject().type())
                || !"approvals".equals(route.subject().productKey())) return false;
        boolean work = route.routeContractKey().startsWith("route.approvals.work.");
        String surface = work ? "approvals.work" : "approvals.admin";
        if (!surface.equals(route.subject().surfaceKey()) || !surface.equals(route.navigationContextId())
                || !schema.profile().equals(profileKey) || route.uiRouteId() != null || route.uiRoutePattern() != null
                || route.accessProfiles() == null || route.accessProfiles().size() != 1
                || route.servicePepBindings() == null || route.servicePepBindings().size() != 1
                || route.gatewayApiBindings() == null || route.gatewayApiBindings().size() != 1) return false;
        var profile = route.accessProfiles().getFirst();
        var access = profile.requiredAccess();
        if (!profileKey.equals(profile.profileKey()) || profile.precedence() != 300
                || profile.readOnly() != schema.readOnly()
                || !List.of("NORMAL", "ELEVATED").equals(profile.activeAccessModes())
                || !(work ? List.of("SELF", "OBJECT") : List.of("OBJECT")).equals(profile.targetBindingKinds())
                || !schema.predicates().equals(profile.predicatePolicyKeys())
                || profile.responseProjectionBindings() == null || profile.responseProjectionBindings().size() != 1
                || !projection.equals(profile.responseProjectionBindings().getFirst()) || access == null) return false;
        if (schema.capabilities().size() == 2) {
            if (!"CAPABILITY_EXPRESSION".equals(access.type()) || !"ALL".equals(access.mode())
                    || !schema.capabilities().equals(access.capabilityContractKeys())
                    || access.capabilityContractKey() != null || access.accessPolicyKey() != null) return false;
        } else if (!"CAPABILITY".equals(access.type())
                || !schema.capabilities().getFirst().equals(access.capabilityContractKey())
                || access.accessPolicyKey() != null || access.mode() != null || access.capabilityContractKeys() != null) return false;
        String bindingKey = route.routeContractKey() + ".binding.01";
        var service = route.servicePepBindings().getFirst();
        var gateway = route.gatewayApiBindings().getFirst();
        return bindingKey.equals(service.bindingKey()) && bindingKey.equals(gateway.bindingKey())
                && "approval".equals(service.serviceKey())
                && schema.method().equals(service.method()) && schema.method().equals(gateway.method())
                && schema.path().equals(service.path()) && ("/api/approvals" + schema.path()).equals(gateway.path())
                && bindingKey.equals(projection.apiBindingKey())
                && (route.routeContractKey() + '.' + profileKey + ".projection.v1").equals(projection.projectionPolicyKey())
                && schema.key().equals(projection.responseSchemaKey())
                && Integer.valueOf(1).equals(projection.schemaVersion())
                && schema.hash().equals(projection.openApiSchemaSha256())
                && Boolean.FALSE.equals(projection.additionalProperties());
    }

    static void validateCoverage(ProductAuthorizationContractDtos.BundleContract contract) {
        if (contract.version() != 10) return;
        Set<String> routes = new java.util.HashSet<>();
        int inherited = 0;
        for (var route : contract.routes()) {
            if (isRelease10DataRoute(route.routeContractKey())) {
                require(routes.add(route.routeContractKey()) && route.accessProfiles() != null
                        && route.accessProfiles().size() == 1);
                var profile = route.accessProfiles().getFirst();
                require(profile.responseProjectionBindings() != null && profile.responseProjectionBindings().size() == 1
                        && matches(route, profile.profileKey(), profile.responseProjectionBindings().getFirst()));
            } else {
                if (route.accessProfiles() != null) for (var profile : route.accessProfiles()) {
                    if (profile.responseProjectionBindings() != null) require(profile.responseProjectionBindings().stream()
                            .noneMatch(projection -> SCHEMA_KEYS.contains(projection.responseSchemaKey())));
                }
                if (ProductAuthorizationExtensionProjectionSchema.isExtensionDataRoute(route.routeContractKey())) {
                    require(route.accessProfiles() != null && route.accessProfiles().size() == 1);
                    var profile = route.accessProfiles().getFirst();
                    require(profile.responseProjectionBindings() != null && profile.responseProjectionBindings().size() == 1
                            && ProductAuthorizationExtensionProjectionSchema.matches(route, profile.profileKey(),
                            profile.responseProjectionBindings().getFirst()));
                    inherited++;
                }
            }
        }
        require(routes.equals(SCHEMAS.keySet()) && inherited == 12);
    }

    private static void require(boolean value) {
        if (!value) throw new IllegalArgumentException("Invalid exact release10 response projection coverage.");
    }
    private record Schema(String profile, String method, String path, String key, String hash,
                          List<String> capabilities, List<String> predicates, boolean readOnly) { }
}

