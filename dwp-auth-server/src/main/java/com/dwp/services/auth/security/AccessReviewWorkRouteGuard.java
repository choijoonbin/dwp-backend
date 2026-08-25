package com.dwp.services.auth.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.GovernedRouteAuthorityDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.service.GovernedRouteAuthorityService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Service PEP for the two exact assigned-review route contracts. */
@Component
public class AccessReviewWorkRouteGuard {

    private final GovernedRouteAuthorityService authority;

    public AccessReviewWorkRouteGuard(GovernedRouteAuthorityService authority) {
        this.authority = authority;
    }

    public void requireDetail(Long tenantId, Long actorId, UUID workItemRef) {
        requireAllowed(evaluate(
                tenantId,
                actorId,
                workItemRef,
                IdentityRoutePredicateEvaluator.DETAIL_ROUTE,
                null));
    }

    public void requireDecision(
            Long tenantId,
            Long actorId,
            UUID workItemRef,
            long expectedVersion) {
        requireAllowed(evaluate(
                tenantId,
                actorId,
                workItemRef,
                IdentityRoutePredicateEvaluator.DECISION_ROUTE,
                Long.toString(expectedVersion)));
    }

    private GovernedRouteAuthorityDtos.AuthorityResult evaluate(
            Long tenantId,
            Long actorId,
            UUID workItemRef,
            String routeContractKey,
            String expectedVersion) {
        return authority.evaluate(new GovernedRouteAuthorityDtos.EvaluateRequest(
                tenantId,
                actorId,
                "work.work",
                routeContractKey,
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                workItemRef.toString(),
                expectedVersion,
                null));
    }

    private void requireAllowed(GovernedRouteAuthorityDtos.AuthorityResult result) {
        if (result.decision() == GovernedRouteAuthorityDtos.Decision.ALLOWED) return;
        if (result.decision() == GovernedRouteAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        }
        if ("OBJECT_VERSION_STALE".equals(result.reasonCode())
                || "OBJECT_ALREADY_DECIDED".equals(result.reasonCode())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The access review item changed after it was loaded. Refresh and try again.");
        }
        throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE, "RESOURCE_NOT_AVAILABLE");
    }
}
