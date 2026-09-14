package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ApprovalFormAdminSourceCapabilityTest {
    private static final String ROUTE = ApprovalFormAdminSourceCapability.ROUTE;
    private static final String SOURCE = ApprovalFormAdminSourceCapability.SOURCE;
    private static final String PERMISSION = "ACTION.APPROVAL_FORM_USER_DIRECTORY:VIEW";
    private static final OffsetDateTime EXPIRY = OffsetDateTime.parse("2026-09-14T02:01:00Z");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private ProductAuthorizationContractDtos.BundleContract contract;
    private Registry registry;
    private ProductAuthorizationContractDtos.AccessProfile profile;

    @BeforeEach
    void consumeActualSealedEight() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/product-authorization/product-surfaces-v1.bundle-v8.generated.json")) {
            contract = JSON.readValue(input, ProductAuthorizationContractDtos.BundleContract.class);
        }
        registry = new Registry(contract);
        profile = registry.routesByKey().get(ROUTE).accessProfiles().getFirst();
    }

    @Test
    void actualClosedProfileRequiresSourcePermissionAndRetainsOnlyCurrentOwnerScopeAndExpiry() {
        var owner = owner();
        var result = evaluate(identity(Set.of(PERMISSION), Set.of()), owner);
        assertThat(result.allowed()).isTrue();
        assertThat(result.scopes()).isEqualTo(owner.scopes());
        assertThat(result.validUntil()).isEqualTo(EXPIRY);
        assertThat(result.effectiveReadOnly()).isTrue();
        assertThat(result.grants()).hasSize(2);
        var source = (ProductSurfaceAuthorityDtos.CapabilityGrant) result.grants().getLast();
        assertThat(source.capabilityContractKey()).isEqualTo(SOURCE);
        assertThat(source.resolvedCapabilityCode()).isEqualTo(PERMISSION);
        assertThat(source.scopeKeys()).containsExactly("current-owner-scope");
        assertThat(source.responsibility()).isEqualTo(owner.grants().stream()
                .map(ProductSurfaceAuthorityDtos.CapabilityGrant.class::cast).findFirst().orElseThrow().responsibility());
        assertThat(source.validUntil()).isEqualTo(EXPIRY);
    }

    @Test
    void permissionAliasesAndFormAppManageOrCreateDoNotSubstituteForSourceView() {
        for (var permissions : List.of(Set.<String>of(), Set.of("DWP_APPROVAL_FORM_USER_DIRECTORY:VIEW"),
                Set.of("ADMIN.APPROVAL_DESIGN:VIEW", "APP.APPROVALS:VIEW"),
                Set.of("ACTION.APPROVAL_REQUEST:CREATE", "ACTION.APPROVAL_REQUEST:MANAGE"))) {
            assertThat(evaluate(identity(permissions, Set.of()), owner()).allowed()).isFalse();
        }
    }

    @Test
    void sourceViewAloneCannotHealCurrentOwnerDenialsIncludingSodAndRevocation() {
        for (var decision : List.of(ProductSurfaceAuthorityDtos.Decision.SOD_CONFLICT,
                ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID, ProductSurfaceAuthorityDtos.Decision.EXPIRED,
                ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE, ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED)) {
            var owner = Evaluation.denied(decision, "CURRENT_OWNER_DENIED");
            assertThat(evaluate(identity(Set.of(PERMISSION), Set.of()), owner)).isSameAs(owner);
        }
        assertThat(evaluate(identity(Set.of(PERMISSION), Set.of("PROVIDER_OPERATOR")), owner()).allowed()).isFalse();
    }

    @Test
    void onlyExactAdminDataRouteAndApprovedNormalOrElevatedProfileCanUseThisHelper() throws Exception {
        for (String route : List.of("route.approvals.admin.form-user-candidates.data",
                "route.approvals.work.form-field-candidates.data", "route.approvals.admin.forms.data", "")) {
            assertThat(ApprovalFormAdminSourceCapability.applies(request(route, "approvals.admin", "NORMAL", null, null), profile)).isFalse();
        }
        assertThat(ApprovalFormAdminSourceCapability.applies(request(ROUTE, "approvals.work", "NORMAL", null, null), profile)).isFalse();
        for (var mode : ProductSurfaceAuthorityDtos.AccessMode.values()) {
            assertThat(ApprovalFormAdminSourceCapability.applies(request(ROUTE, "approvals.admin", mode.name(), null, null), profile))
                    .isEqualTo(mode == ProductSurfaceAuthorityDtos.AccessMode.NORMAL || mode == ProductSurfaceAuthorityDtos.AccessMode.ELEVATED);
        }
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.put("readOnly", false), node -> node.put("profileKey", "candidate-alias"),
                node -> ((ObjectNode) node.get("requiredAccess")).put("mode", "ANY"),
                node -> node.putArray("predicatePolicyKeys"), node -> node.putArray("targetBindingKinds").add("REQUEST"))) {
            ObjectNode node = JSON.valueToTree(profile); mutation.accept(node);
            assertThat(ApprovalFormAdminSourceCapability.applies(request(),
                    JSON.treeToValue(node, ProductAuthorizationContractDtos.AccessProfile.class))).isFalse();
        }
    }

    @Test
    void sourceMetadataChangesDoNotExpandTheSealedSourceCapability() throws Exception {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.put("resourceKey", "APP.APPROVALS"), node -> node.put("action", "MANAGE"),
                node -> node.put("resolvedCapabilityCode", "DWP_APPROVAL_FORM_USER_DIRECTORY:VIEW"),
                node -> node.put("scopeResolver", "APP_RESOURCE_SET:RS_OTHER"),
                node -> node.put("responsibilityRequirement", "NOT_REQUIRED"), node -> node.put("requiresProductEntitlement", true),
                node -> node.put("activationPolicy", "STEPUP-MGMT-HIGH-V1"), node -> node.put("lifecycleState", "RETIRED"))) {
            ObjectNode node = JSON.valueToTree(contract);
            for (var capability : node.withArray("capabilities")) {
                if (SOURCE.equals(capability.path("contractKey").asText())) mutation.accept((ObjectNode) capability);
            }
            var mutated = new Registry(JSON.treeToValue(node, ProductAuthorizationContractDtos.BundleContract.class));
            assertThat(ApprovalFormAdminSourceCapability.evaluate(request(), mutated,
                    identity(Set.of(PERMISSION), Set.of()), profile, owner()).allowed()).isFalse();
        }
    }

    @Test
    void ownerGrantsMustCoverExactlyTheReadonlyOwnerScopesWithApprovalResponsibility() throws Exception {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.put("capabilityContractKey", SOURCE), node -> node.put("readOnly", false),
                node -> node.put("activationState", "REVOKED"), node -> node.put("requiresProductEntitlement", true),
                node -> node.putArray("scopeKeys").add("other-scope"), node -> node.putArray("scopeKeys"),
                node -> ((ObjectNode) node.get("responsibility")).put("resourceSetKey", "RS_OTHER"))) {
            ObjectNode node = JSON.valueToTree(owner().grants().getFirst()); mutation.accept(node);
            var grant = JSON.treeToValue(node, ProductSurfaceAuthorityDtos.CapabilityGrant.class);
            var invalid = Evaluation.allowed(ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT,
                    List.of(grant), owner().scopes(), true, EXPIRY, false, "ADMIN.APPROVAL_DESIGN");
            assertThat(evaluate(identity(Set.of(PERMISSION), Set.of()), invalid).allowed()).isFalse();
        }
        var extra = new ProductSurfaceAuthorityDtos.EffectiveScope("extra-scope", "RESOURCE_SET", "Extra", false, true, EXPIRY);
        var invalid = Evaluation.allowed(ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT,
                owner().grants(), List.of(owner().scopes().getFirst(), extra), true, EXPIRY, false, null);
        assertThat(evaluate(identity(Set.of(PERMISSION), Set.of()), invalid).allowed()).isFalse();
        assertThat(ApprovalFormAdminSourceCapability.evaluate(request(ROUTE, "approvals.admin", "NORMAL", "RS_OTHER", null),
                registry, identity(Set.of(PERMISSION), Set.of()), profile, owner()).allowed()).isFalse();
        assertThat(ApprovalFormAdminSourceCapability.evaluate(request(ROUTE, "approvals.admin", "NORMAL", null, "support-session"),
                registry, identity(Set.of(PERMISSION), Set.of()), profile, owner()).allowed()).isFalse();
    }

    private Evaluation evaluate(ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity, Evaluation owner) {
        return ApprovalFormAdminSourceCapability.evaluate(request(), registry, identity, profile, owner);
    }

    private static ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity(Set<String> permissions, Set<String> roles) {
        return new ProductAuthorizationIdentityEvidenceService.IdentityEvidence(permissions, roles, List.of(), List.of(), "current-auth");
    }

    private static ProductSurfaceAuthorityDtos.EvaluateRequest request() {
        return request(ROUTE, "approvals.admin", "NORMAL", "current-owner-scope", null);
    }

    private static ProductSurfaceAuthorityDtos.EvaluateRequest request(String route, String surface, String mode, String scope, String support) {
        return new ProductSurfaceAuthorityDtos.EvaluateRequest(10L, 20L, "approvals", surface,
                ProductSurfaceAuthorityDtos.AccessMode.valueOf(mode), route, null, scope, support, null, List.of());
    }

    private static Evaluation owner() {
        var grant = new ProductSurfaceAuthorityDtos.CapabilityGrant("approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW",
                ProductSurfaceAuthorityDtos.CapabilityAuthorityMode.PERMISSION, List.of("predicate.approval.form-scoped-reference.v1"),
                ProductSurfaceAuthorityDtos.ResponsibilityRequirement.REQUIRED,
                new ProductSurfaceAuthorityDtos.Responsibility("APP_CONFIG_ADMIN", "RS_APPROVALS"),
                List.of("current-owner-scope"), false, true, ProductSurfaceAuthorityDtos.ActivationState.ACTIVE, EXPIRY);
        var scope = new ProductSurfaceAuthorityDtos.EffectiveScope("current-owner-scope", "RESOURCE_SET", "Owner", true, true, EXPIRY);
        return Evaluation.allowed(ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT, List.of(grant), List.of(scope), true, EXPIRY, false, null);
    }
}
