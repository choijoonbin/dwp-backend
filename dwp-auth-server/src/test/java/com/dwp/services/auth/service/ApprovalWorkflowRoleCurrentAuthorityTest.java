package com.dwp.services.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.entity.Tenant;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.TenantRepository;
import com.dwp.services.auth.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApprovalWorkflowRoleCurrentAuthorityTest {
    ProductAuthorizationIdentityEvidenceService identities;
    ProductSurfaceAuthorityService surfaces;
    UserRepository users;
    TenantRepository tenants;
    ProductSurfaceAuthorityDtos.AuthorityResult result;
    ApprovalWorkflowRoleCurrentAuthority authority;
    ApprovalWorkflowRoleProofVerifier.VerifiedProof proof;
    User user;

    @BeforeAll static void keys() throws Exception { ApprovalWorkflowRoleProofVerifierTest.keys(); }
    @BeforeEach void initialize() throws Exception {
        identities = mock(ProductAuthorizationIdentityEvidenceService.class); surfaces = mock(ProductSurfaceAuthorityService.class);
        users = mock(UserRepository.class); tenants = mock(TenantRepository.class);
        var binding = ApprovalWorkflowRoleProofVerifierTest.binding();
        var verifier = new ApprovalWorkflowRoleProofVerifier(ApprovalWorkflowRoleProofVerifierTest.JSON,
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.roleKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.userKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.transportKey), ApprovalWorkflowRoleProofVerifierTest.CLOCK);
        String token = ApprovalWorkflowRoleProofVerifierTest.sign(ApprovalWorkflowRoleProofVerifierTest.claims(binding), ApprovalWorkflowRoleProofVerifierTest.roleKey);
        String body = ApprovalWorkflowRoleProofVerifierTest.body(token, binding);
        proof = verifier.verify(ApprovalWorkflowRoleProofVerifierTest.transport(body), body);
        user = mock(User.class); var tenant = mock(Tenant.class);
        when(user.getUserId()).thenReturn(100L); when(user.getTenantId()).thenReturn(42L);
        when(user.getStatus()).thenReturn("ACTIVE"); when(user.getIdentityPlane()).thenReturn("TENANT");
        when(user.getPersonPublicId()).thenReturn(proof.binding().personPublicId());
        when(tenant.getTenantId()).thenReturn(42L); when(tenant.getStatus()).thenReturn("ACTIVE");
        when(users.findTenantIdentityByUserIdAndTenantId(100L, 42L)).thenReturn(Optional.of(user));
        when(tenants.findById(42L)).thenReturn(Optional.of(tenant));
        when(identities.load(42L, 100L)).thenReturn(identity(Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:UPDATE", "ACTION.APPROVAL_FORM:VIEW")));
        result = mock(ProductSurfaceAuthorityDtos.AuthorityResult.class);
        when(result.decision()).thenReturn(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        when(result.productKey()).thenReturn("approvals"); when(result.surfaceKey()).thenReturn("approvals.work");
        when(result.plane()).thenReturn("work"); when(result.accessMode()).thenReturn(ProductSurfaceAuthorityDtos.AccessMode.NORMAL);
        when(result.accessSource()).thenReturn(ProductSurfaceAuthorityDtos.AccessSource.ENTITLEMENT);
        when(result.contextKey()).thenReturn("context"); when(result.authRevision()).thenReturn("auth-1");
        when(result.policyRevision()).thenReturn("policy-1");
        when(result.revalidateAt()).thenReturn(ApprovalWorkflowRoleProofVerifierTest.NOW.plusSeconds(20).atOffset(java.time.ZoneOffset.UTC));
        when(result.scopes()).thenReturn(List.of(new ProductSurfaceAuthorityDtos.EffectiveScope("own", "OWN", "Own", true, false, null)));
        when(surfaces.evaluate(any())).thenReturn(result);
        authority = new ApprovalWorkflowRoleCurrentAuthority(identities, surfaces, users, tenants, ApprovalWorkflowRoleProofVerifierTest.CLOCK);
    }

    ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity(Set<String> permissions) {
        return new ProductAuthorizationIdentityEvidenceService.IdentityEvidence(permissions, Set.of("APPROVAL_OPERATOR"), List.of(), List.of(), "auth-1");
    }

    void fails(ErrorCode code) { assertEquals(code, assertThrows(BaseException.class, () -> authority.require(proof)).getErrorCode()); }

    @Test void currentTenantPersonUpdateAndFormViewAreReevaluatedWithoutUserSourcePermission() {
        var evidence = authority.require(proof);
        assertEquals("auth-1", evidence.authRevision()); assertEquals(ApprovalWorkflowRoleProofVerifierTest.NOW.plusSeconds(20), evidence.expiresAt());
        verify(surfaces).evaluate(argThat(request -> request.actorId() == 100L && request.tenantId() == 42L
                && request.routeContractKey().equals("route.approvals.work.request-submit.action") && request.contextScopeKey().equals("own")));
    }

    @Test void createOrUserSourceViewCannotReplacePlanningUpdateAndFormView() {
        when(identities.load(42L, 100L)).thenReturn(identity(Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:CREATE",
                "ACTION.APPROVAL_FORM_USER_DIRECTORY:VIEW"))); fails(ErrorCode.FORBIDDEN); verifyNoInteractions(surfaces);
    }

    @Test void changedOpaquePersonProviderAndInactiveIdentityNeverBorrowTenantRoleAuthority() {
        when(user.getPersonPublicId()).thenReturn(java.util.UUID.randomUUID()); fails(ErrorCode.FORBIDDEN); verifyNoInteractions(identities, surfaces);
        when(user.getPersonPublicId()).thenReturn(proof.binding().personPublicId()); when(user.getIdentityPlane()).thenReturn("PROVIDER");
        fails(ErrorCode.FORBIDDEN); verifyNoInteractions(identities, surfaces);
    }

    @Test void exactContextScopeAccessModeAndSupportBoundaryAreMandatory() {
        when(result.contextKey()).thenReturn("other"); fails(ErrorCode.FORBIDDEN);
        when(result.contextKey()).thenReturn("context"); when(result.accessMode()).thenReturn(ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT);
        fails(ErrorCode.FORBIDDEN); when(result.accessMode()).thenReturn(ProductSurfaceAuthorityDtos.AccessMode.NORMAL);
        when(result.effectiveReadOnly()).thenReturn(true); fails(ErrorCode.FORBIDDEN);
    }

    @Test void missingCurrentAuthorityIs503AndIdentityRevisionDriftIs409NotGatewayPsrComparison() {
        when(surfaces.evaluate(any())).thenReturn(null); fails(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        when(surfaces.evaluate(any())).thenReturn(result); when(result.authRevision()).thenReturn("auth-changed");
        fails(ErrorCode.DECISION_REVISION_CONFLICT);
    }
}
