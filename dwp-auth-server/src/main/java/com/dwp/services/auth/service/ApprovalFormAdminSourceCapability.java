package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static com.dwp.services.auth.service.ProductAuthorizationAuthoritySupport.*;

/** Independent source permission, bounded by the actual current FORM VIEW duty evaluation. */
final class ApprovalFormAdminSourceCapability {
    static final String ROUTE = "route.approvals.admin.form-field-candidates.data";
    static final String SOURCE = "approvals.form-user-directory.read";
    private static final String PERMISSION = "ACTION.APPROVAL_FORM_USER_DIRECTORY:VIEW";
    private static final String OWNER = "approvals.design.read";
    private static final String RESOURCE_SET = "RS_APPROVALS";

    private ApprovalFormAdminSourceCapability() { }

    static boolean applies(ProductSurfaceAuthorityDtos.EvaluateRequest request,
            ProductAuthorizationContractDtos.AccessProfile profile) {
        var access = profile.requiredAccess();
        return ROUTE.equals(request.routeContractKey()) && "approvals".equals(request.productKey())
                && "approvals.admin".equals(request.surfaceKey()) && request.directRouteEvaluation()
                && Set.of(ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                          ProductSurfaceAuthorityDtos.AccessMode.ELEVATED).contains(request.activeAccessMode())
                && "full-management".equals(profile.profileKey()) && profile.readOnly()
                && access != null && "CAPABILITY_EXPRESSION".equals(access.type())
                && "ALL".equals(access.mode()) && List.of(OWNER, SOURCE).equals(access.capabilityContractKeys())
                && List.of("predicate.approval.form-scoped-reference.v1").equals(profile.predicatePolicyKeys())
                && List.of("OBJECT").equals(profile.targetBindingKinds());
    }

    static Evaluation evaluate(ProductSurfaceAuthorityDtos.EvaluateRequest request, Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            ProductAuthorizationContractDtos.AccessProfile profile, Evaluation owner) {
        if (!applies(request, profile) || request.tenantId() <= 0 || request.actorId() <= 0
                || request.supportSessionRef() != null || request.supportRevision() != null
                || identity.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))
                || !identity.hasPermission(PERMISSION)) return denied();
        if (!owner.allowed()) return owner;
        var source = registry.capabilitiesByKey().get(SOURCE);
        if (!validSource(source) || owner.requiresProductEligibility() || !owner.effectiveReadOnly()
                || owner.accessSource() != ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT
                || owner.scopes().isEmpty() || owner.grants().isEmpty()) return denied();
        List<ProductSurfaceAuthorityDtos.EffectiveGrant> grants = new ArrayList<>(owner.grants());
        Set<String> scopeKeys = owner.scopes().stream().map(ProductSurfaceAuthorityDtos.EffectiveScope::key)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> intersection = new java.util.HashSet<>();
        for (var grant : owner.grants()) {
            if (!(grant instanceof ProductSurfaceAuthorityDtos.CapabilityGrant current)
                    || !OWNER.equals(current.capabilityContractKey())
                    || !"ADMIN.APPROVAL_DESIGN:VIEW".equals(current.resolvedCapabilityCode())
                    || !current.readOnly() || current.requiresProductEntitlement()
                    || current.activationState() != ProductSurfaceAuthorityDtos.ActivationState.ACTIVE
                    || current.responsibility() == null
                    || !"APP_CONFIG_ADMIN".equals(current.responsibility().code())
                    || !RESOURCE_SET.equals(current.responsibility().resourceSetKey())
                    || current.scopeKeys().isEmpty() || !scopeKeys.containsAll(current.scopeKeys())) return denied();
            intersection.addAll(current.scopeKeys());
            grants.add(new ProductSurfaceAuthorityDtos.CapabilityGrant(SOURCE, PERMISSION,
                    capabilityAuthority(source.authorityMode()), profile.predicatePolicyKeys(),
                    responsibilityRequirement(source.responsibilityRequirement()), current.responsibility(),
                    current.scopeKeys(), false, true, ProductSurfaceAuthorityDtos.ActivationState.ACTIVE,
                    owner.validUntil()));
        }
        if (!intersection.equals(scopeKeys) || owner.scopes().stream()
                .anyMatch(scope -> !"RESOURCE_SET".equals(scope.kind()) || !scope.readOnly())
                || (request.contextScopeKey() != null && !intersection.contains(request.contextScopeKey()))) return denied();
        // No directory duty is invented: its permission was independently loaded above.
        return Evaluation.allowed(owner.accessSource(), grants, owner.scopes(), true, owner.validUntil(),
                false, source.resourceKey());
    }

    private static boolean validSource(ProductAuthorizationContractDtos.CapabilityContract source) {
        return source != null && SOURCE.equals(source.contractKey())
                && "approvals".equals(source.productKey()) && "approvals.admin".equals(source.surfaceKey())
                && "ACTIVE".equals(source.lifecycleState()) && "VIEW".equals(source.action())
                && "PERMISSION".equals(source.authorityMode()) && PERMISSION.equals(source.resolvedCapabilityCode())
                && "ACTION.APPROVAL_FORM_USER_DIRECTORY".equals(source.resourceKey())
                && "REQUIRED".equals(source.responsibilityRequirement())
                && "APP_CONFIG_ADMIN".equals(source.requiredResponsibilityCode())
                && ("APP_RESOURCE_SET:" + RESOURCE_SET).equals(source.scopeResolver())
                && !source.requiresProductEntitlement() && source.activationPolicy() == null
                && source.sodPolicyId() == null && "LOW".equals(source.riskTier());
    }

    private static Evaluation denied() {
        return Evaluation.denied(ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                "EXPLICIT_FORM_SOURCE_AND_CURRENT_OWNER_SCOPE_REQUIRED");
    }
}
