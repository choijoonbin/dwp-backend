package com.dwp.services.auth.controller;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AccessReviewDtos;
import com.dwp.services.auth.security.AccessReviewWorkRouteGuard;
import com.dwp.services.auth.security.DurableIdentityPlaneGuard;
import com.dwp.services.auth.service.AccessReviewService;
import com.dwp.services.auth.service.AccessReviewWorkService;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccessReviewWorkControllerTest {

    @Test
    void namedReviewerUsesOnlyTheOpaqueWorkApi() {
        AccessReviewWorkService service = mock(AccessReviewWorkService.class);
        AccessReviewWorkRouteGuard guard = mock(AccessReviewWorkRouteGuard.class);
        DurableIdentityPlaneGuard planeGuard = mock(DurableIdentityPlaneGuard.class);
        AccessReviewWorkController controller =
                new AccessReviewWorkController(service, guard, planeGuard);
        UUID ref = UUID.randomUUID();
        Authentication reviewer = authentication(7L, List.of("EMPLOYEE"));

        controller.detail(reviewer, "1", ref);

        verify(service).detail(1L, 7L, ref);
        verify(guard).requireDetail(1L, 7L, ref);
        verify(planeGuard).requireTenant(reviewer);
    }

    @Test
    void decisionForwardsExpectedVersionToTheOwnerService() {
        AccessReviewWorkService service = mock(AccessReviewWorkService.class);
        AccessReviewWorkRouteGuard guard = mock(AccessReviewWorkRouteGuard.class);
        DurableIdentityPlaneGuard planeGuard = mock(DurableIdentityPlaneGuard.class);
        AccessReviewWorkController controller =
                new AccessReviewWorkController(service, guard, planeGuard);
        UUID ref = UUID.randomUUID();
        var request = new AccessReviewDtos.DecisionRequest(
                "APPROVE", "Access remains required for assigned duties.", 11L);

        controller.decide(authentication(7L, List.of("EMPLOYEE")), "1", "corr-1", ref, request);

        verify(service).decide(1L, 7L, "corr-1", ref, request);
        verify(guard).requireDecision(1L, 7L, ref, 11L);
        verify(planeGuard).requireTenant(any(Authentication.class));
    }

    @Test
    void providerIdentityIsRejectedBeforeWorkItemResolution() {
        AccessReviewWorkService service = mock(AccessReviewWorkService.class);
        AccessReviewWorkRouteGuard routeGuard = mock(AccessReviewWorkRouteGuard.class);
        DurableIdentityPlaneGuard planeGuard = mock(DurableIdentityPlaneGuard.class);
        AccessReviewWorkController controller =
                new AccessReviewWorkController(service, routeGuard, planeGuard);
        Authentication provider = authentication(900001L, List.of("PROVIDER_ADMIN"));
        UUID ref = UUID.randomUUID();
        doThrow(new BaseException(ErrorCode.FORBIDDEN))
                .when(planeGuard).requireTenant(provider);

        assertThatThrownBy(() -> controller.detail(provider, "1", ref))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verifyNoInteractions(service, routeGuard);
    }

    @Test
    void namedReviewerCannotEnterTheAdminCampaignApi() {
        AccessReviewService service = mock(AccessReviewService.class);
        AccessReviewController controller = new AccessReviewController(service);

        assertThatThrownBy(() -> controller.campaigns(
                authentication(7L, List.of("APP_ACCESS_REVIEWER")), "1"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(service);
    }

    @Test
    void workEndpointsExposeStableOpenApiOperationIds() throws Exception {
        Operation detail = AccessReviewWorkController.class.getMethod(
                        "detail", Authentication.class, String.class, UUID.class)
                .getAnnotation(Operation.class);
        Operation decision = AccessReviewWorkController.class.getMethod(
                        "decide",
                        Authentication.class,
                        String.class,
                        String.class,
                        UUID.class,
                        AccessReviewDtos.DecisionRequest.class)
                .getAnnotation(Operation.class);

        assertThat(detail).isNotNull();
        assertThat(detail.operationId()).isEqualTo("getAssignedAccessReviewWorkItem");
        assertThat(decision).isNotNull();
        assertThat(decision.operationId()).isEqualTo("decideAssignedAccessReviewWorkItem");
    }

    private Authentication authentication(Long userId, List<String> roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .issuedAt(Instant.parse("2026-08-24T03:00:00Z"))
                .expiresAt(Instant.parse("2026-08-24T04:00:00Z"))
                .claim("tenant_id", 1L)
                .claim("roles", roles)
                .build();
        return new TestingAuthenticationToken(jwt, null);
    }
}
