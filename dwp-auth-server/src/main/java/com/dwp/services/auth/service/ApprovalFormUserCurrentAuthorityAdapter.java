package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.TenantRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.service.ApprovalFormUserSourceProofVerifier.VerifiedSourceProof;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Re-evaluates Auth evidence, never the Gateway's composite decision hash or caller grants. */
@Component
public class ApprovalFormUserCurrentAuthorityAdapter implements ApprovalFormUserAuthorityPort {
    public static final String SOURCE_PERMISSION = "ACTION.APPROVAL_FORM_USER_DIRECTORY:VIEW";
    public static final String WORK_ROUTE = "route.approvals.work.form-field-candidates.data";
    public static final String ADMIN_ROUTE = "route.approvals.admin.form-field-candidates.data";
    private final ProductAuthorizationIdentityEvidenceService identities;
    private final ProductSurfaceAuthorityService authority;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final Clock clock;

    @Autowired
    public ApprovalFormUserCurrentAuthorityAdapter(ProductAuthorizationIdentityEvidenceService identities,
            ProductSurfaceAuthorityService authority, UserRepository users, TenantRepository tenants) {
        this(identities, authority, users, tenants, Clock.systemUTC());
    }

    ApprovalFormUserCurrentAuthorityAdapter(ProductAuthorizationIdentityEvidenceService identities,
            ProductSurfaceAuthorityService authority, UserRepository users, TenantRepository tenants, Clock clock) {
        this.identities = identities;
        this.authority = authority;
        this.users = users;
        this.tenants = tenants;
        this.clock = clock;
    }

    @Override
    public CurrentAuthority requireCurrent(VerifiedSourceProof proof) {
        if (!clock.instant().isBefore(proof.expiresAt())) throw denied();
        var actor = users.findTenantIdentityByUserIdAndTenantId(proof.actorId(), proof.tenantId()).orElseThrow(this::denied);
        var tenant = tenants.findById(proof.tenantId()).orElseThrow(this::denied);
        if (!Long.valueOf(proof.actorId()).equals(actor.getUserId())
                || !Long.valueOf(proof.tenantId()).equals(actor.getTenantId())
                || !Long.valueOf(proof.tenantId()).equals(tenant.getTenantId())
                || !"ACTIVE".equals(actor.getStatus()) || !"TENANT".equals(actor.getIdentityPlane())
                || !"ACTIVE".equals(tenant.getStatus())) throw denied();
        var identity = identities.load(proof.tenantId(), proof.actorId());
        boolean reference = proof.mutation() != null;
        boolean work = reference || "CREATE_REFERENCE".equals(proof.referencePurpose());
        String route = reference ? proof.routeContractKey() : work ? WORK_ROUTE : ADMIN_ROUTE;
        String ownerPermission = reference ? proof.mutation().requiredPermission()
                : work ? "ACTION.APPROVAL_REQUEST:CREATE" : "ADMIN.APPROVAL_DESIGN:VIEW";
        if (!route.equals(proof.routeContractKey()) || !identity.hasPermission(SOURCE_PERMISSION)
                || !identity.hasPermission(ownerPermission)
                || (reference && !identity.hasPermission("APP.APPROVALS:VIEW"))
                || identity.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))) throw denied();
        var result = authority.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(proof.tenantId(),
                proof.actorId(), "approvals", work ? "approvals.work" : "approvals.admin",
                ProductSurfaceAuthorityDtos.AccessMode.valueOf(proof.accessMode()), route, proof.contextKey(),
                proof.contextScopeKey(), null, null, List.of()));
        if (result == null || result.decision() == ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Current approval person source route authority is unavailable.");
        }
        if (result.decision() != ProductSurfaceAuthorityDtos.Decision.ALLOWED
                || result.accessSource() == ProductSurfaceAuthorityDtos.AccessSource.SUPPORT
                || !"approvals".equals(result.productKey())
                || !(work ? "approvals.work" : "approvals.admin").equals(result.surfaceKey())
                || !(work ? "work" : "management").equals(result.plane())
                || result.accessMode() != ProductSurfaceAuthorityDtos.AccessMode.valueOf(proof.accessMode())
                || !proof.contextScopeKey().equals(result.scopes().stream()
                        .filter(scope -> proof.contextScopeKey().equals(scope.key())).map(scope -> scope.key()).findFirst().orElse(null))) {
            throw denied();
        }
        if (!proof.contextKey().equals(result.contextKey()) || !identity.revision().equals(result.authRevision())
                || result.policyRevision() == null || result.policyRevision().isBlank() || result.revalidateAt() == null
                || !clock.instant().isBefore(result.revalidateAt().toInstant())
                || (result.validUntil() != null && !clock.instant().isBefore(result.validUntil().toInstant()))) throw changed();
        return new CurrentAuthority(result.authRevision(), result.policyRevision());
    }

    private BaseException denied() {
        return new BaseException(ErrorCode.FORBIDDEN, "Current explicit approval person source authority is required.");
    }

    private BaseException changed() {
        return new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                "Approval person source authority changed; revalidate before issuing a new proof.");
    }
}
