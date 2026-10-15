package com.dwp.services.auth.controller;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.auth.config.ProductSurfaceInternalSecurityConfig;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.service.ProductSurfaceAuthorityService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ProductSurfaceAuthorityControllerTest {

    private static final long TENANT_ID = 17L;
    private static final long ACTOR_ID = 29L;
    private static final String PRODUCT = "hcm";
    private static final String SURFACE = "hcm.operations";
    private static final String WORKFORCE = "WORKFORCE_TARGET_POPULATION";
    private static final String TEAM =
            "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION";
    private static final List<RouteSelector> SEALED_SELECTORS = List.of(
            new RouteSelector(
                    "route.hcm.operations.assignment-event-create.action",
                    WORKFORCE,
                    "TARGET_POPULATION"),
            new RouteSelector(
                    "route.hcm.operations.assignment-event-validate.action",
                    WORKFORCE,
                    "TARGET_POPULATION"),
            new RouteSelector(
                    "route.hcm.operations.assignment-event-submit.action",
                    WORKFORCE,
                    "TARGET_POPULATION"),
            new RouteSelector(
                    "route.hcm.operations.assignment-event-publish.action",
                    WORKFORCE,
                    "TARGET_POPULATION"),
            new RouteSelector(
                    "route.hcm.operations.employee-change-request-create.action",
                    WORKFORCE,
                    "TARGET_POPULATION"),
            new RouteSelector(
                    "route.hcm.operations.employee-change-request-create.action",
                    TEAM,
                    "TARGET_POPULATION"),
            new RouteSelector(
                    "route.hcm.operations.employee-change-request-create.action",
                    "SELF",
                    "SELF"),
            new RouteSelector(
                    "route.hcm.operations.employee-change-request-submit.action",
                    WORKFORCE,
                    "TARGET_POPULATION"),
            new RouteSelector(
                    "route.hcm.operations.employee-change-request-submit.action",
                    TEAM,
                    "TARGET_POPULATION"),
            new RouteSelector(
                    "route.hcm.operations.employee-change-request-submit.action",
                    "SELF",
                    "SELF"),
            new RouteSelector(
                    "route.hcm.operations.employee-change-request-apply.action",
                    WORKFORCE,
                    "TARGET_POPULATION"));

    @Test
    void peopleCallerCanEvaluateOnlyEverySealedRouteSelectorInNormalOrElevatedMode() {
        ProductSurfaceAuthorityService service = mock(ProductSurfaceAuthorityService.class);
        ProductSurfaceAuthorityController controller =
                new ProductSurfaceAuthorityController(service);

        for (RouteSelector selector : SEALED_SELECTORS) {
            for (ProductSurfaceAuthorityDtos.AccessMode mode : List.of(
                    ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                    ProductSurfaceAuthorityDtos.AccessMode.ELEVATED)) {
                ProductSurfaceAuthorityDtos.EvaluateRequest request = originRequest(
                        TENANT_ID,
                        ACTOR_ID,
                        PRODUCT,
                        SURFACE,
                        mode,
                        selector.route(),
                        null,
                        scope(TENANT_ID, ACTOR_ID, selector.source(), selector.kind()),
                        null,
                        null,
                        List.of());

                assertThatCode(() -> controller.evaluate(
                        ProductSurfaceInternalSecurityConfig.PEOPLE_SERVICE_IDENTITY,
                        request)).doesNotThrowAnyException();
            }
        }

        verify(service, times(SEALED_SELECTORS.size() * 2))
                .evaluate(any(ProductSurfaceAuthorityDtos.EvaluateRequest.class));
    }

    @Test
    void peopleCallerCannotEscapeTheSealedBodyBoundary() {
        ProductSurfaceAuthorityService service = mock(ProductSurfaceAuthorityService.class);
        ProductSurfaceAuthorityController controller =
                new ProductSurfaceAuthorityController(service);
        String assignmentRoute =
                "route.hcm.operations.assignment-event-create.action";
        String workforceScope = scope(
                TENANT_ID, ACTOR_ID, WORKFORCE, "TARGET_POPULATION");

        List<ProductSurfaceAuthorityDtos.EvaluateRequest> denied = List.of(
                originRequest(TENANT_ID, ACTOR_ID, "approvals", SURFACE,
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL, assignmentRoute,
                        null, workforceScope, null, null, List.of()),
                originRequest(TENANT_ID, ACTOR_ID, PRODUCT, "hcm.personal",
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL, assignmentRoute,
                        null, workforceScope, null, null, List.of()),
                originRequest(TENANT_ID, ACTOR_ID, PRODUCT, SURFACE,
                        ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT,
                        assignmentRoute, null, workforceScope, null, null, List.of()),
                originRequest(TENANT_ID, ACTOR_ID, PRODUCT, SURFACE,
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                        null, null, workforceScope, null, null, List.of()),
                originRequest(TENANT_ID, ACTOR_ID, PRODUCT, SURFACE,
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                        "route.hcm.operations.assignment-command-receipt.data",
                        null, workforceScope, null, null, List.of()),
                originRequest(TENANT_ID, ACTOR_ID, PRODUCT, SURFACE,
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL, assignmentRoute,
                        "psc-not-allowed", workforceScope, null, null, List.of()),
                originRequest(TENANT_ID, ACTOR_ID, PRODUCT, SURFACE,
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL, assignmentRoute,
                        null, workforceScope, "support-session", null, List.of()),
                originRequest(TENANT_ID, ACTOR_ID, PRODUCT, SURFACE,
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL, assignmentRoute,
                        null, workforceScope, null, "support-revision", List.of()),
                originRequest(TENANT_ID, ACTOR_ID, PRODUCT, SURFACE,
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL, assignmentRoute,
                        null, workforceScope, null, null, List.of("support.scope")),
                originRequest(TENANT_ID, ACTOR_ID, PRODUCT, SURFACE,
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL, assignmentRoute,
                        null, scope(TENANT_ID + 1, ACTOR_ID, WORKFORCE,
                                "TARGET_POPULATION"), null, null, List.of()),
                originRequest(TENANT_ID, ACTOR_ID, PRODUCT, SURFACE,
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL, assignmentRoute,
                        null, scope(TENANT_ID, ACTOR_ID + 1, WORKFORCE,
                                "TARGET_POPULATION"), null, null, List.of()),
                originRequest(TENANT_ID, ACTOR_ID, PRODUCT, SURFACE,
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL, assignmentRoute,
                        null, scope(TENANT_ID, ACTOR_ID, "SELF", "SELF"),
                        null, null, List.of()),
                originRequest(TENANT_ID, ACTOR_ID, PRODUCT, SURFACE,
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL, assignmentRoute,
                        null, "scope-arbitrary", null, null, List.of()));

        for (ProductSurfaceAuthorityDtos.EvaluateRequest request : denied) {
            assertDenied(
                    controller,
                    ProductSurfaceInternalSecurityConfig.PEOPLE_SERVICE_IDENTITY,
                    request);
        }
        assertDenied(
                controller,
                ProductSurfaceInternalSecurityConfig.PEOPLE_SERVICE_IDENTITY,
                originRequest(TENANT_ID, ACTOR_ID, PRODUCT, SURFACE,
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL, assignmentRoute,
                        null, null, null, null, List.of()));

        verifyNoInteractions(service);
    }

    @Test
    void missingOrUntrustedCallerAttributeCannotReachAuthorityEvaluation() {
        ProductSurfaceAuthorityService service = mock(ProductSurfaceAuthorityService.class);
        ProductSurfaceAuthorityController controller =
                new ProductSurfaceAuthorityController(service);
        ProductSurfaceAuthorityDtos.EvaluateRequest request = originRequest(
                TENANT_ID,
                ACTOR_ID,
                PRODUCT,
                SURFACE,
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "route.hcm.operations.assignment-event-create.action",
                null,
                scope(TENANT_ID, ACTOR_ID, WORKFORCE, "TARGET_POPULATION"),
                null,
                null,
                List.of());

        assertDenied(controller, null, request);
        assertDenied(controller, "unregistered-service", request);
        assertDenied(
                controller,
                ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY,
                request);

        verifyNoInteractions(service);
    }

    @Test
    void gatewayAndPlatformCallerBodiesRemainOutsideThePeopleOnlyGuard() {
        ProductSurfaceAuthorityService service = mock(ProductSurfaceAuthorityService.class);
        ProductSurfaceAuthorityController controller =
                new ProductSurfaceAuthorityController(service);
        ProductSurfaceAuthorityDtos.EvaluateRequest existingRequest = originRequest(
                TENANT_ID,
                ACTOR_ID,
                "approvals",
                "approvals.admin",
                ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT,
                "route.approvals.admin.forms.page",
                "existing-context",
                "existing-scope",
                "support-session",
                "support-revision",
                List.of("support.scope"));

        assertThatCode(() -> controller.evaluate(
                ProductSurfaceInternalSecurityConfig.GATEWAY_SERVICE_IDENTITY,
                existingRequest)).doesNotThrowAnyException();
        assertThatCode(() -> controller.evaluate(
                ProductSurfaceInternalSecurityConfig.PLATFORM_SERVICE_IDENTITY,
                existingRequest)).doesNotThrowAnyException();

        verify(service, times(2)).evaluate(existingRequest);
    }

    private static ProductSurfaceAuthorityDtos.EvaluateRequest originRequest(
            long tenantId,
            long actorId,
            String product,
            String surface,
            ProductSurfaceAuthorityDtos.AccessMode mode,
            String route,
            String contextKey,
            String contextScopeKey,
            String supportSessionRef,
            String supportRevision,
            List<String> supportScopes) {
        return new ProductSurfaceAuthorityDtos.EvaluateRequest(
                tenantId,
                actorId,
                product,
                surface,
                mode,
                route,
                contextKey,
                contextScopeKey,
                supportSessionRef,
                supportRevision,
                supportScopes);
    }

    private static String scope(long tenantId, long actorId, String source, String kind) {
        return ProductSurfaceScopeKey.key(
                tenantId, actorId, PRODUCT, SURFACE, source, kind);
    }

    private static void assertDenied(
            ProductSurfaceAuthorityController controller,
            String caller,
            ProductSurfaceAuthorityDtos.EvaluateRequest request) {
        assertThatThrownBy(() -> controller.evaluate(caller, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private record RouteSelector(String route, String source, String kind) {
    }
}
