package com.dwp.services.auth.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.GovernedRouteAuthorityDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.service.GovernedRouteAuthorityService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessReviewWorkRouteGuardTest {

    @Test
    void decisionUsesTheExactRouteObjectAndVersionContract() {
        GovernedRouteAuthorityService authority = mock(GovernedRouteAuthorityService.class);
        when(authority.evaluate(any())).thenAnswer(invocation -> allowed(invocation.getArgument(0)));
        AccessReviewWorkRouteGuard guard = new AccessReviewWorkRouteGuard(authority);
        UUID ref = UUID.randomUUID();

        guard.requireDecision(1L, 7L, ref, 11L);

        ArgumentCaptor<GovernedRouteAuthorityDtos.EvaluateRequest> request =
                ArgumentCaptor.forClass(GovernedRouteAuthorityDtos.EvaluateRequest.class);
        verify(authority).evaluate(request.capture());
        assertThat(request.getValue().routeContractKey())
                .isEqualTo(IdentityRoutePredicateEvaluator.DECISION_ROUTE);
        assertThat(request.getValue().opaqueTargetRef()).isEqualTo(ref.toString());
        assertThat(request.getValue().expectedObjectVersion()).isEqualTo("11");
    }

    @Test
    void authorityOutageIsNotConvertedIntoLegacyRoleAccess() {
        GovernedRouteAuthorityService authority = mock(GovernedRouteAuthorityService.class);
        when(authority.evaluate(any())).thenAnswer(invocation ->
                GovernedRouteAuthorityDtos.AuthorityResult.unavailable(invocation.getArgument(0)));
        AccessReviewWorkRouteGuard guard = new AccessReviewWorkRouteGuard(authority);

        assertThatThrownBy(() -> guard.requireDetail(1L, 7L, UUID.randomUUID()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    private GovernedRouteAuthorityDtos.AuthorityResult allowed(
            GovernedRouteAuthorityDtos.EvaluateRequest request) {
        return new GovernedRouteAuthorityDtos.AuthorityResult(
                GovernedRouteAuthorityDtos.Decision.ALLOWED,
                "NAMED_REVIEWER_ASSIGNED",
                "auth:1",
                "policy:1",
                "work.review:" + request.opaqueTargetRef(),
                request.navigationContextId(),
                ProductSurfaceAuthorityDtos.AccessSource.RELATIONSHIP,
                request.activeAccessMode(),
                "grant:1",
                false,
                java.time.OffsetDateTime.now().plusMinutes(5),
                null,
                null,
                "identity.named-reviewer-access.v1",
                java.time.OffsetDateTime.now().plusSeconds(60),
                "evidence:1");
    }
}
