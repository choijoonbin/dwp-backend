package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Closed response-only v9 structure. Admission still requires a separately sealed immutable release pin. */
final class ProductAuthorizationExtensionProjectionSchema {
    private static final String ADMIN = "route.approvals.admin.";
    private static final String WORK = "route.approvals.work.";
    private static final String BINARY = WORK + "attachment-download-content.data";
    private static final String IMPACT = ADMIN + "policy-impact.data";
    private static final List<String> IMPACT_CAPABILITIES = List.of("approvals.design.read", "approvals.operations.read", "approvals.policy.read");
    private static final Map<String, Schema> SCHEMAS = Map.ofEntries(
            json(ADMIN + "attachment-policy.data", "GET", "/v1/admin/attachments/policy", "ApprovalAttachmentPolicy", "2cb1a0df8b68b16f49f6ae27ac9858c31d11ce278d295934a8b8521a76bd7c8d", "approvals.policy.read"),
            json(ADMIN + "form-publish-review.data", "GET", "/v1/admin/forms/{formId}/publish-review", "ApprovalFormLifecycleReview", "cf84f33d56d1affbf78db19e557f13b2e3fc533961d37d5850b2dab8d10b84c2", "approvals.design.read"),
            json(ADMIN + "form-version-detail.data", "GET", "/v1/admin/forms/{formId}/versions/{formVersionId}", "ApprovalFormLifecycleVersion", "fab88e34e8445ed5f9face5688f89661e527e5d34c2963dc930557c9db1a3cdf", "approvals.design.read"),
            json(ADMIN + "form-version-diff.data", "GET", "/v1/admin/forms/{formId}/diff", "ApprovalFormLifecycleDiff", "0701251b9a276bba2791aa4fdcbb8767680ac95d35aaaa2164e4e30cec181fbf", "approvals.design.read"),
            json(ADMIN + "form-version-history.data", "GET", "/v1/admin/forms/{formId}/versions", "ApprovalFormLifecycleHistory", "6f066bce11bab60d5d7d8db4c51aae0dc3789e06bd7af2ab8682bf78cd7739c2", "approvals.design.read"),
            json(ADMIN + "form-working-draft.data", "GET", "/v1/admin/forms/{formId}/working-draft", "ApprovalFormLifecycleWorkspace", "472ca94cc33d0bc2a85ca8f8ac6689a9801e3c3916b1d1d33c3d3ec21046d5c5", "approvals.design.read"),
            json(IMPACT, "GET", "/v1/admin/policies/{policyId}/impact", "ApprovalPolicyImpactResult", "652af4b87f0d35380d22136bfb37c7381241defb1175433561bc32368a4363e0", null),
            Map.entry(BINARY, new Schema("GET", "/v1/attachment-downloads/{grantId}/content", "ApprovalAttachmentDownloadBytesV1", "d9726a8a1015a05c5062b02bf0dbd5fe455824f7da3ab3700db95708d4c5c2bd", "approvals.work.attachment-download.read", true)),
            json(WORK + "attachment-upload.data", "GET", "/v1/attachment-uploads/{uploadId}", "ApprovalAttachmentUpload", "0385b3eadafb5bb57c215262f4a4297a3ee33a572f1f2cc203d20c4eeb7dd20c", "approvals.work.attachment-upload.read"),
            json(WORK + "information-command-receipt.data", "POST", "/v1/requests/{requestId}/information-commands/{originalKey}/receipt", "ApprovalInformationCommandReceipt", "eff26ae76e19c6dd359e3c7941af868ed8df1aa61f84fa141a6e7701a3164458", "approvals.work.information-command-receipt.read"),
            json(WORK + "request-attachments.data", "GET", "/v1/requests/{requestId}/attachments", "ApprovalAttachmentAttachments", "46026087765a71b0ad5ddab65dd73d048f4ffe96862cbde6688f5b3859e9dcc4", "approvals.work.request.read"),
            json(WORK + "task-attachments.data", "GET", "/v1/tasks/{taskId}/attachments", "ApprovalAttachmentAttachments", "46026087765a71b0ad5ddab65dd73d048f4ffe96862cbde6688f5b3859e9dcc4", "approvals.work.task.read"));
    private static final Set<String> SCHEMA_KEYS = SCHEMAS.values().stream().map(Schema::key).collect(Collectors.toUnmodifiableSet());

    private ProductAuthorizationExtensionProjectionSchema() { }
    static boolean isExtensionDataRoute(String key) { return SCHEMAS.containsKey(key); }

    static boolean matches(ProductAuthorizationContractDtos.GovernedRoute route, String profileKey,
            ProductAuthorizationContractDtos.ResponseProjectionBinding projection) {
        var schema = SCHEMAS.get(route.routeContractKey());
        if (schema == null || projection == null || !"DATA".equals(route.routeKind()) || !Boolean.TRUE.equals(route.sideEffectFree())
                || route.subject() == null || !"PRODUCT".equals(route.subject().type()) || !"approvals".equals(route.subject().productKey())) return false;
        String surface = route.routeContractKey().startsWith(WORK) ? "approvals.work" : "approvals.admin";
        String expectedProfile = surface.equals("approvals.work") ? "full-work" : "full-management";
        if (!surface.equals(route.subject().surfaceKey()) || !surface.equals(route.navigationContextId())
                || !expectedProfile.equals(profileKey) || route.uiRouteId() != null || route.uiRoutePattern() != null
                || route.accessProfiles() == null || route.accessProfiles().size() != 1
                || route.servicePepBindings() == null || route.servicePepBindings().size() != 1
                || route.gatewayApiBindings() == null || route.gatewayApiBindings().size() != 1) return false;
        var profile = route.accessProfiles().getFirst(); var access = profile.requiredAccess();
        if (!profileKey.equals(profile.profileKey()) || profile.precedence() != 300 || !profile.readOnly()
                || !List.of("NORMAL", "ELEVATED").equals(profile.activeAccessModes()) || !List.of("OBJECT").equals(profile.targetBindingKinds())
                || profile.responseProjectionBindings() == null || profile.responseProjectionBindings().size() != 1
                || !projection.equals(profile.responseProjectionBindings().getFirst()) || access == null) return false;
        if (route.routeContractKey().equals(IMPACT)) {
            if (!"CAPABILITY_EXPRESSION".equals(access.type()) || !"ALL".equals(access.mode())
                    || !IMPACT_CAPABILITIES.equals(access.capabilityContractKeys()) || access.capabilityContractKey() != null || access.accessPolicyKey() != null) return false;
        } else if (!"CAPABILITY".equals(access.type()) || !schema.capability().equals(access.capabilityContractKey())
                || access.accessPolicyKey() != null || access.mode() != null || access.capabilityContractKeys() != null) return false;
        String bindingKey = route.routeContractKey() + ".binding.01";
        var service = route.servicePepBindings().getFirst(); var gateway = route.gatewayApiBindings().getFirst();
        if (!bindingKey.equals(service.bindingKey()) || !bindingKey.equals(gateway.bindingKey()) || !"approval".equals(service.serviceKey())
                || !schema.method().equals(service.method()) || !schema.method().equals(gateway.method())
                || !schema.path().equals(service.path()) || !("/api/approvals" + schema.path()).equals(gateway.path())
                || !bindingKey.equals(projection.apiBindingKey()) || !(route.routeContractKey() + '.' + profileKey + ".projection.v1").equals(projection.projectionPolicyKey())
                || !schema.key().equals(projection.responseSchemaKey())) return false;
        return schema.binary()
                ? projection.schemaVersion() == null && projection.openApiSchemaSha256() == null && projection.additionalProperties() == null
                : Integer.valueOf(1).equals(projection.schemaVersion()) && schema.hash().equals(projection.openApiSchemaSha256()) && Boolean.FALSE.equals(projection.additionalProperties());
    }

    static void validateCoverage(ProductAuthorizationContractDtos.BundleContract contract) {
        if (contract.version() != 9) return;
        int count = 0;
        for (var route : contract.routes()) {
            if (isExtensionDataRoute(route.routeContractKey())) {
                require(route.accessProfiles() != null && route.accessProfiles().size() == 1);
                var profile = route.accessProfiles().getFirst();
                require(profile.responseProjectionBindings() != null && profile.responseProjectionBindings().size() == 1
                        && matches(route, profile.profileKey(), profile.responseProjectionBindings().getFirst()));
                count++;
            } else if (route.accessProfiles() != null) for (var profile : route.accessProfiles()) {
                if (profile.responseProjectionBindings() != null) require(profile.responseProjectionBindings().stream()
                        .noneMatch(projection -> SCHEMA_KEYS.contains(projection.responseSchemaKey())));
            }
        }
        require(count == 12 && contract.routes().stream().filter(route -> isExtensionDataRoute(route.routeContractKey()))
                .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey).collect(Collectors.toSet()).equals(SCHEMAS.keySet()));
    }

    private static Map.Entry<String, Schema> json(String route, String method, String path, String key, String hash, String capability) {
        return Map.entry(route, new Schema(method, path, key, hash, capability, false));
    }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException("Invalid exact v9 extension response projection coverage."); }
    private record Schema(String method, String path, String key, String hash, String capability, boolean binary) { }
}
