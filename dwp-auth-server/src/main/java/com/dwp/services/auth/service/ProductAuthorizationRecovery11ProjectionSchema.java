package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact Source11 DATA routes and original-authority receipt profile. */
final class ProductAuthorizationRecovery11ProjectionSchema {
    static final String RECEIPT_PROFILE =
            "approval.retention.command-receipt.original-authority.v1";
    static final String RECEIPT_PREDICATE =
            "predicate.approval.retention-command-original-authority.v1";
    private static final String RECEIPT_SCHEMA = "ApprovalRetentionCommandReceipt";
    private static final String RECEIPT_HASH =
            "95d520bbcaee4a7c3d2283cb18599d457b441e07e516cf7e335987d696eb8fbd";
    private static final Map<String, Schema> SCHEMAS = Map.of(
            "route.approvals.admin.retention-policy-initialization-command.data",
            new Schema(RECEIPT_PROFILE,
                    "/v1/admin/retention/policy-initialization-commands/{idempotencyKey}",
                    RECEIPT_SCHEMA, RECEIPT_HASH, List.of("approvals.policy.update"),
                    List.of(RECEIPT_PREDICATE)),
            "route.approvals.admin.retention-policy-draft-command.data",
            new Schema(RECEIPT_PROFILE,
                    "/v1/admin/retention/policies/{policyId}/draft-commands/{idempotencyKey}",
                    RECEIPT_SCHEMA, RECEIPT_HASH, List.of("approvals.policy.update"),
                    List.of(RECEIPT_PREDICATE)),
            "route.approvals.admin.retention-policy-publication-command.data",
            new Schema(RECEIPT_PROFILE,
                    "/v1/admin/retention/policies/{policyId}/publication-commands/{idempotencyKey}",
                    RECEIPT_SCHEMA, RECEIPT_HASH, List.of("approvals.policy.publish"),
                    List.of(RECEIPT_PREDICATE)),
            "route.approvals.admin.retention-record-command.data",
            new Schema(RECEIPT_PROFILE,
                    "/v1/admin/retention/records/{requestId}/claim-commands/{idempotencyKey}",
                    RECEIPT_SCHEMA, RECEIPT_HASH, List.of("approvals.operations.execute"),
                    List.of(RECEIPT_PREDICATE)),
            "route.approvals.admin.workflow-planning-selection.data",
            new Schema("full-management",
                    "/v1/admin/workflows/{workflowId}/planning-selection",
                    "ApprovalWorkflowPlanningSelection",
                    "ca0ac253c38917b2d75c1793ffda73de9797fd9840edf0c5b558c62f6bd039f3",
                    List.of("approvals.admin.workflow-planning-form.read",
                            "approvals.admin.workflow-planning-simulation.read"),
                    List.of("predicate.approval.workflow-planning-selection.v1")));
    private static final Set<String> SCHEMA_KEYS = SCHEMAS.values().stream()
            .map(Schema::key).collect(Collectors.toUnmodifiableSet());

    private ProductAuthorizationRecovery11ProjectionSchema() { }

    static boolean isRecovery11DataRoute(String key) {
        return SCHEMAS.containsKey(key);
    }

    static boolean isOriginalAuthorityReceiptRoute(String key) {
        Schema schema = SCHEMAS.get(key);
        return schema != null && RECEIPT_PROFILE.equals(schema.profile());
    }

    static boolean matches(ProductAuthorizationContractDtos.GovernedRoute route,
            String profileKey,
            ProductAuthorizationContractDtos.ResponseProjectionBinding projection) {
        Schema schema = SCHEMAS.get(route.routeContractKey());
        if (schema == null || projection == null || !"DATA".equals(route.routeKind())
                || !Boolean.TRUE.equals(route.sideEffectFree()) || route.subject() == null
                || !"PRODUCT".equals(route.subject().type())
                || !"approvals".equals(route.subject().productKey())
                || !"approvals.admin".equals(route.subject().surfaceKey())
                || !"approvals.admin".equals(route.navigationContextId())
                || !schema.profile().equals(profileKey)
                || route.uiRouteId() != null || route.uiRoutePattern() != null
                || route.accessProfiles() == null || route.accessProfiles().size() != 1
                || route.servicePepBindings() == null || route.servicePepBindings().size() != 1
                || route.gatewayApiBindings() == null || route.gatewayApiBindings().size() != 1) {
            return false;
        }
        var profile = route.accessProfiles().getFirst();
        var access = profile.requiredAccess();
        if (!profileKey.equals(profile.profileKey()) || profile.precedence() != 300
                || !profile.readOnly()
                || !List.of("NORMAL", "ELEVATED").equals(profile.activeAccessModes())
                || !List.of("OBJECT").equals(profile.targetBindingKinds())
                || !schema.predicates().equals(profile.predicatePolicyKeys())
                || profile.responseProjectionBindings() == null
                || profile.responseProjectionBindings().size() != 1
                || !projection.equals(profile.responseProjectionBindings().getFirst())
                || access == null) return false;
        if (schema.capabilities().size() == 2) {
            if (!"CAPABILITY_EXPRESSION".equals(access.type())
                    || !"ALL".equals(access.mode())
                    || !schema.capabilities().equals(access.capabilityContractKeys())
                    || access.capabilityContractKey() != null
                    || access.accessPolicyKey() != null) return false;
        } else if (!"CAPABILITY".equals(access.type())
                || !schema.capabilities().getFirst().equals(access.capabilityContractKey())
                || access.accessPolicyKey() != null || access.mode() != null
                || access.capabilityContractKeys() != null) return false;
        String bindingKey = route.routeContractKey() + ".binding.01";
        var service = route.servicePepBindings().getFirst();
        var gateway = route.gatewayApiBindings().getFirst();
        return bindingKey.equals(service.bindingKey())
                && bindingKey.equals(gateway.bindingKey())
                && "approval".equals(service.serviceKey())
                && "GET".equals(service.method()) && "GET".equals(gateway.method())
                && schema.path().equals(service.path())
                && ("/api/approvals" + schema.path()).equals(gateway.path())
                && bindingKey.equals(projection.apiBindingKey())
                && (route.routeContractKey() + '.' + profileKey + ".projection.v1")
                .equals(projection.projectionPolicyKey())
                && schema.key().equals(projection.responseSchemaKey())
                && Integer.valueOf(1).equals(projection.schemaVersion())
                && schema.hash().equals(projection.openApiSchemaSha256())
                && Boolean.FALSE.equals(projection.additionalProperties());
    }

    static void validateCoverage(ProductAuthorizationContractDtos.BundleContract contract) {
        if (contract.version() != 11) return;
        Set<String> routes = new java.util.HashSet<>();
        for (var route : contract.routes()) {
            if (isRecovery11DataRoute(route.routeContractKey())) {
                require(routes.add(route.routeContractKey())
                        && route.accessProfiles() != null
                        && route.accessProfiles().size() == 1);
                var profile = route.accessProfiles().getFirst();
                require(profile.responseProjectionBindings() != null
                        && profile.responseProjectionBindings().size() == 1
                        && matches(route, profile.profileKey(),
                        profile.responseProjectionBindings().getFirst()));
            } else if (route.accessProfiles() != null) {
                for (var profile : route.accessProfiles()) {
                    if (profile.responseProjectionBindings() != null) {
                        require(profile.responseProjectionBindings().stream()
                                .noneMatch(projection -> SCHEMA_KEYS.contains(
                                projection.responseSchemaKey())));
                    }
                }
            }
        }
        require(routes.equals(SCHEMAS.keySet()));
    }

    private static void require(boolean value) {
        if (!value) throw new IllegalArgumentException(
                "Invalid exact recovery11 response projection coverage.");
    }

    private record Schema(String profile, String path, String key, String hash,
                          List<String> capabilities, List<String> predicates) { }
}
