package com.dwp.services.auth.service;

import static com.dwp.services.auth.service.ApprovalFormUserProofTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos.*;
import com.dwp.services.auth.entity.Tenant;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.TenantRepository;
import com.dwp.services.auth.repository.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApprovalFormUserCurrentAuthorityAdapterTest {
    private final ProductAuthorizationIdentityEvidenceService identities = mock(ProductAuthorizationIdentityEvidenceService.class);
    private final ProductSurfaceAuthorityService routes = mock(ProductSurfaceAuthorityService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final ApprovalFormUserCurrentAuthorityAdapter adapter =
            new ApprovalFormUserCurrentAuthorityAdapter(identities, routes, users, tenants, CLOCK);
    private final ApprovalFormUserSourceProofVerifier.VerifiedSourceProof proof =
            verifier().verify(sign(claims("SEARCH", "a".repeat(64))), "SEARCH", "a".repeat(64));

    @BeforeEach
    void activeTenantEvidence() {
        when(users.findTenantIdentityByUserIdAndTenantId(20L, 10L)).thenReturn(Optional.of(
                User.builder().userId(20L).tenantId(10L).status("ACTIVE").identityPlane("TENANT").build()));
        when(tenants.findById(10L)).thenReturn(Optional.of(Tenant.builder().tenantId(10L).status("ACTIVE").build()));
        evidence(Set.of(ApprovalFormUserCurrentAuthorityAdapter.SOURCE_PERMISSION, "ACTION.APPROVAL_REQUEST:CREATE"));
        when(routes.evaluate(any())).thenReturn(result(Decision.ALLOWED, "approval-context", "approval-scope", "auth-current", "policy-current"));
    }

    @Test
    void independentlyLoadsActualIdentityRevisionAndExactRouteContextWithoutGatewayCompositeComparison() {
        assertThat(adapter.requireCurrent(proof)).isEqualTo(new ApprovalFormUserAuthorityPort.CurrentAuthority("auth-current", "policy-current"));
        verify(routes).evaluate(argThat(request -> request.routeContractKey().equals(ApprovalFormUserCurrentAuthorityAdapter.WORK_ROUTE)
                && request.contextKey().equals("approval-context") && request.contextScopeKey().equals("approval-scope")
                && request.surfaceKey().equals("approvals.work") && request.supportSessionRef() == null));
    }

    @Test
    void createFormViewManageAndHcmReadNeverImplyExplicitSourceView() {
        for (Set<String> permissions : List.of(Set.of("ACTION.APPROVAL_REQUEST:CREATE"),
                Set.of("ADMIN.APPROVAL_DESIGN:VIEW", "APP.HCM:VIEW"), Set.of("ACTION.APPROVAL_REQUEST:MANAGE"),
                Set.of(ApprovalFormUserCurrentAuthorityAdapter.SOURCE_PERMISSION),
                Set.of("ACTION.APPROVAL_REQUEST:CREATE", "DWP_APPROVAL_FORM_USER_DIRECTORY:VIEW"))) {
            evidence(permissions); reject(() -> adapter.requireCurrent(proof), ErrorCode.FORBIDDEN);
        }
        verifyNoInteractions(routes);
    }

    @Test
    void sourcePermissionRevocationDeniesEvenIfOldRouteWouldAllow() {
        adapter.requireCurrent(proof);
        evidence(Set.of("ACTION.APPROVAL_REQUEST:CREATE"));
        reject(() -> adapter.requireCurrent(proof), ErrorCode.FORBIDDEN);
        verify(routes, times(1)).evaluate(any());
    }

    @Test
    void staleContextCrossScopeAndChangedCurrentIdentityRevisionDeny() {
        when(routes.evaluate(any())).thenReturn(result(Decision.ALLOWED, "another-context", "approval-scope", "auth-current", "policy-current"));
        reject(() -> adapter.requireCurrent(proof), ErrorCode.DECISION_REVISION_CONFLICT);
        when(routes.evaluate(any())).thenReturn(result(Decision.ALLOWED, "approval-context", "another-scope", "auth-current", "policy-current"));
        reject(() -> adapter.requireCurrent(proof), ErrorCode.FORBIDDEN);
        when(routes.evaluate(any())).thenReturn(result(Decision.ALLOWED, "approval-context", "approval-scope", "auth-changed", "policy-current"));
        reject(() -> adapter.requireCurrent(proof), ErrorCode.DECISION_REVISION_CONFLICT);
    }

    @Test
    void inactiveProviderAndUnregisteredV8AuthorityRemainClosed() {
        when(users.findTenantIdentityByUserIdAndTenantId(20L, 10L)).thenReturn(Optional.of(
                User.builder().userId(20L).tenantId(10L).status("ACTIVE").identityPlane("PROVIDER").build()));
        reject(() -> adapter.requireCurrent(proof), ErrorCode.FORBIDDEN);
        verifyNoInteractions(identities, routes);
    }

    @Test
    void unavailableCurrentRegistryNeverBorrowsLegacyCreatePermission() {
        when(routes.evaluate(any())).thenReturn(result(Decision.AUTHORITY_UNAVAILABLE, "approval-context", "approval-scope", "auth-current", "policy-current"));
        reject(() -> adapter.requireCurrent(proof), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    @Test
    void mutationReevaluatesItsActualActionNotTheCandidateDataRouteAndRequiresIndependentUpdatePlusSource() {
        String route = "route.approvals.work.request-draft-update.action";
        var proof = new ApprovalFormReferenceProofVerifier(verifier()).verify(sign(referenceClaims(route, 0, "a".repeat(64))), "a".repeat(64));
        evidence(Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:UPDATE", ApprovalFormUserCurrentAuthorityAdapter.SOURCE_PERMISSION));
        assertThat(adapter.requireCurrent(proof).authRevision()).isEqualTo("auth-current");
        verify(routes).evaluate(argThat(request -> request.routeContractKey().equals(route)
                && !request.routeContractKey().equals(ApprovalFormUserCurrentAuthorityAdapter.WORK_ROUTE)));
        evidence(Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:UPDATE"));
        reject(() -> adapter.requireCurrent(proof), ErrorCode.FORBIDDEN);
        verify(routes, times(1)).evaluate(any());
    }

    @Test
    void mutationCreateAndUpdateDoNotBorrowEachOthersOwnerPermission() {
        var references = new ApprovalFormReferenceProofVerifier(verifier());
        var create = references.verify(sign(referenceClaims("route.approvals.work.request-create.action", 0, "a".repeat(64))), "a".repeat(64));
        evidence(Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:UPDATE", ApprovalFormUserCurrentAuthorityAdapter.SOURCE_PERMISSION));
        reject(() -> adapter.requireCurrent(create), ErrorCode.FORBIDDEN);
        var update = references.verify(sign(referenceClaims("route.approvals.work.request-draft-update.action", 0, "a".repeat(64))), "a".repeat(64));
        evidence(Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:CREATE", ApprovalFormUserCurrentAuthorityAdapter.SOURCE_PERMISSION));
        reject(() -> adapter.requireCurrent(update), ErrorCode.FORBIDDEN);
        verifyNoInteractions(routes);
    }

    private void evidence(Set<String> permissions) {
        when(identities.load(10L, 20L)).thenReturn(new ProductAuthorizationIdentityEvidenceService.IdentityEvidence(
                permissions, Set.of(), List.of(), List.of(), "auth-current"));
    }

    private AuthorityResult result(Decision decision, String context, String scope, String auth, String policy) {
        return new AuthorityResult(decision, null, auth, policy, context, "approvals", "approvals.work", "work",
                AccessMode.NORMAL, AccessSource.ENTITLEMENT, "APP.APPROVALS", List.of(),
                List.of(new EffectiveScope(scope, "SELF", "Self", true, true, null)), "exact-route", true, false,
                null, null, null, null, OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC), "evidence");
    }

    private void reject(Runnable task, ErrorCode expected) {
        assertThatThrownBy(task::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
