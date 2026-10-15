package com.dwp.services.auth.controller;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.auth.config.ProductSurfaceInternalSecurityConfig;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.service.ProductSurfaceAuthorityService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/internal/auth/v1/product-surface-authority")
public class ProductSurfaceAuthorityController {

    private static final String PRODUCT = "hcm";
    private static final String SURFACE = "hcm.operations";
    private static final Set<ProductSurfaceAuthorityDtos.AccessMode> PEOPLE_ACCESS_MODES =
            Set.of(
                    ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                    ProductSurfaceAuthorityDtos.AccessMode.ELEVATED);
    private static final ScopeSelector WORKFORCE_TARGET_POPULATION =
            new ScopeSelector("WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION");
    private static final ScopeSelector TEAM_TARGET_POPULATION = new ScopeSelector(
            "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
            "TARGET_POPULATION");
    private static final ScopeSelector SELF = new ScopeSelector("SELF", "SELF");
    private static final Map<String, List<ScopeSelector>> PEOPLE_ROUTE_SELECTORS = Map.of(
            "route.hcm.operations.assignment-event-create.action",
            List.of(WORKFORCE_TARGET_POPULATION),
            "route.hcm.operations.assignment-event-validate.action",
            List.of(WORKFORCE_TARGET_POPULATION),
            "route.hcm.operations.assignment-event-submit.action",
            List.of(WORKFORCE_TARGET_POPULATION),
            "route.hcm.operations.assignment-event-publish.action",
            List.of(WORKFORCE_TARGET_POPULATION),
            "route.hcm.operations.employee-change-request-create.action",
            List.of(WORKFORCE_TARGET_POPULATION, TEAM_TARGET_POPULATION, SELF),
            "route.hcm.operations.employee-change-request-submit.action",
            List.of(WORKFORCE_TARGET_POPULATION, TEAM_TARGET_POPULATION, SELF),
            "route.hcm.operations.employee-change-request-apply.action",
            List.of(WORKFORCE_TARGET_POPULATION));

    private final ProductSurfaceAuthorityService service;

    public ProductSurfaceAuthorityController(ProductSurfaceAuthorityService service) {
        this.service = service;
    }

    @PostMapping("/evaluate")
    @Operation(operationId = "evaluateProductSurfaceAuthorityInternal")
    public ProductSurfaceAuthorityDtos.AuthorityResult evaluate(
            @RequestAttribute(
                    name = ProductSurfaceInternalSecurityConfig
                            .AUTHENTICATED_SERVICE_IDENTITY_ATTRIBUTE,
                    required = false)
            String authenticatedServiceIdentity,
            @Valid @RequestBody ProductSurfaceAuthorityDtos.EvaluateRequest request) {
        requireTrustedCaller(authenticatedServiceIdentity, request);
        return service.evaluate(request);
    }

    private void requireTrustedCaller(
            String authenticatedServiceIdentity,
            ProductSurfaceAuthorityDtos.EvaluateRequest request) {
        if (ProductSurfaceInternalSecurityConfig.PEOPLE_SERVICE_IDENTITY.equals(
                authenticatedServiceIdentity)) {
            requireSealedPeopleOriginRequest(request);
            return;
        }
        if (!ProductSurfaceInternalSecurityConfig.GATEWAY_SERVICE_IDENTITY.equals(
                authenticatedServiceIdentity)
                && !ProductSurfaceInternalSecurityConfig.PLATFORM_SERVICE_IDENTITY.equals(
                authenticatedServiceIdentity)) {
            throw denied();
        }
    }

    private void requireSealedPeopleOriginRequest(
            ProductSurfaceAuthorityDtos.EvaluateRequest request) {
        if (request == null
                || request.tenantId() == null
                || request.tenantId() <= 0
                || request.actorId() == null
                || request.actorId() <= 0
                || !PRODUCT.equals(request.productKey())
                || !SURFACE.equals(request.surfaceKey())
                || !PEOPLE_ACCESS_MODES.contains(request.activeAccessMode())
                || request.routeContractKey() == null
                || request.contextKey() != null
                || request.supportSessionRef() != null
                || request.supportRevision() != null
                || !request.supportScopes().isEmpty()) {
            throw denied();
        }
        List<ScopeSelector> selectors = PEOPLE_ROUTE_SELECTORS.get(request.routeContractKey());
        if (selectors == null || request.contextScopeKey() == null
                || selectors.stream().noneMatch(selector -> selector.matches(request))) {
            throw denied();
        }
    }

    private static BaseException denied() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "The internal product authority request is outside the caller boundary.");
    }

    private record ScopeSelector(String source, String kind) {

        boolean matches(ProductSurfaceAuthorityDtos.EvaluateRequest request) {
            return ProductSurfaceScopeKey.key(
                    request.tenantId(),
                    request.actorId(),
                    PRODUCT,
                    SURFACE,
                    source,
                    kind).equals(request.contextScopeKey());
        }
    }
}
