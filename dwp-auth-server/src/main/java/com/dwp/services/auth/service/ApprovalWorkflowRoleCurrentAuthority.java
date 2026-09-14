package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.TenantRepository;
import com.dwp.services.auth.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Planning requires current request UPDATE and form VIEW; no USER-source or task-voter authority is borrowed. */
@Component
public final class ApprovalWorkflowRoleCurrentAuthority {
    private final ProductAuthorizationIdentityEvidenceService identities;
    private final ProductSurfaceAuthorityService surfaces;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final Clock clock;

    @Autowired
    public ApprovalWorkflowRoleCurrentAuthority(ProductAuthorizationIdentityEvidenceService identities,
            ProductSurfaceAuthorityService surfaces, UserRepository users, TenantRepository tenants) {
        this(identities, surfaces, users, tenants, Clock.systemUTC());
    }

    ApprovalWorkflowRoleCurrentAuthority(ProductAuthorizationIdentityEvidenceService identities,
            ProductSurfaceAuthorityService surfaces, UserRepository users, TenantRepository tenants, Clock clock) {
        this.identities = identities; this.surfaces = surfaces; this.users = users; this.tenants = tenants; this.clock = clock;
    }

    public Evidence require(ApprovalWorkflowRoleProofVerifier.VerifiedProof proof) {
        var binding = proof.binding();
        if (!clock.instant().isBefore(proof.expiresAt())) throw ApprovalWorkflowRoleBinding.denied();
        var user = users.findTenantIdentityByUserIdAndTenantId(binding.actorId(), binding.tenantId())
                .orElseThrow(ApprovalWorkflowRoleBinding::denied);
        var tenant = tenants.findById(binding.tenantId()).orElseThrow(ApprovalWorkflowRoleBinding::denied);
        if (!Long.valueOf(binding.actorId()).equals(user.getUserId()) || !Long.valueOf(binding.tenantId()).equals(user.getTenantId())
                || !binding.personPublicId().equals(user.getPersonPublicId()) || !"ACTIVE".equals(user.getStatus())
                || !"TENANT".equals(user.getIdentityPlane()) || !Long.valueOf(binding.tenantId()).equals(tenant.getTenantId())
                || !"ACTIVE".equals(tenant.getStatus())) throw ApprovalWorkflowRoleBinding.denied();
        var identity = identities.load(binding.tenantId(), binding.actorId());
        if (!identity.hasPermission("APP.APPROVALS:VIEW") || !identity.hasPermission("ACTION.APPROVAL_REQUEST:UPDATE")
                || !identity.hasPermission("ACTION.APPROVAL_FORM:VIEW")
                || identity.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))) throw ApprovalWorkflowRoleBinding.denied();
        var mode = ProductSurfaceAuthorityDtos.AccessMode.valueOf(binding.accessMode());
        var result = surfaces.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(binding.tenantId(), binding.actorId(),
                "approvals", "approvals.work", mode, binding.routeContractKey(), binding.contextKey(), binding.contextScopeKey(),
                null, null, List.of()));
        if (result == null || result.decision() == ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE) {
            throw ApprovalWorkflowRoleBinding.unavailable();
        }
        var scopes = result.scopes().stream().filter(scope -> binding.contextScopeKey().equals(scope.key())).toList();
        if (result.decision() != ProductSurfaceAuthorityDtos.Decision.ALLOWED
                || !"approvals".equals(result.productKey()) || !"approvals.work".equals(result.surfaceKey())
                || !"work".equals(result.plane()) || result.accessMode() != mode
                || result.accessSource() == ProductSurfaceAuthorityDtos.AccessSource.SUPPORT || result.effectiveReadOnly()
                || !binding.contextKey().equals(result.contextKey()) || scopes.size() != 1 || scopes.getFirst().readOnly()
                || scopes.getFirst().validUntil() != null && !clock.instant().isBefore(scopes.getFirst().validUntil().toInstant())) {
            throw ApprovalWorkflowRoleBinding.denied();
        }
        if (!identity.revision().equals(result.authRevision()) || result.policyRevision() == null || result.policyRevision().isBlank()
                || result.revalidateAt() == null || !clock.instant().isBefore(result.revalidateAt().toInstant())
                || (result.validUntil() != null && !clock.instant().isBefore(result.validUntil().toInstant()))) throw changed();
        Instant expiry = proof.expiresAt();
        if (result.revalidateAt().toInstant().isBefore(expiry)) expiry = result.revalidateAt().toInstant();
        if (result.validUntil() != null && result.validUntil().toInstant().isBefore(expiry)) expiry = result.validUntil().toInstant();
        if (scopes.getFirst().validUntil() != null && scopes.getFirst().validUntil().toInstant().isBefore(expiry)) {
            expiry = scopes.getFirst().validUntil().toInstant();
        }
        return new Evidence(result.authRevision(), result.policyRevision(), expiry);
    }

    static BaseException changed() { return new BaseException(ErrorCode.DECISION_REVISION_CONFLICT, "Current workflow role authority changed."); }
    public record Evidence(String authRevision, String policyRevision, Instant expiresAt) { }
}
