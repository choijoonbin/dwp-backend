package com.dwp.services.approval.signatureproviders;

import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.security.*;
import java.time.OffsetDateTime;
import java.util.List;

final class ApprovalSignatureProviderAuthority implements SignatureProviderCurrentAuthority {
    private final ApprovalWorkAuthority current;

    ApprovalSignatureProviderAuthority(ApprovalWorkAuthority current) { this.current = current; }

    @Override public Current require(SignatureProviderOperation operation) {
        var actor = current.requireCurrent(operation.permission());
        if (actor.tenantId() == null || actor.tenantId() < 1 || actor.userId() == null || actor.userId() < 1
                || actor.personPublicId() == null) throw SignatureProviderErrors.unavailable();
        var decision = ApprovalDecisionRevisionContext.current().orElseThrow(SignatureProviderErrors::unavailable);
        var scope = ApprovalManagementScopeContext.current().orElseThrow(SignatureProviderErrors::unavailable);
        if (!decision.validUntil().isAfter(OffsetDateTime.now())
                || !decision.revision().matches("psr-[a-f0-9]{64}")
                || !decision.routeContractKey().equals(operation.routeContractKey())
                || !decision.contextScopeKey().equals(scope.opaqueScopeKey())
                || !scope.resourceSetKey().matches("RS_[A-Z0-9_]{1,76}")
                || !("110".equals(decision.rolloutState()) || "111".equals(decision.rolloutState())))
            throw SignatureProviderErrors.forbidden();
        List<ApprovalPilotPepRegistry.RouteAuthority> exact = ApprovalPilotAuthorizationContext.current()
                .orElseThrow(SignatureProviderErrors::unavailable).stream()
                .filter(route -> route.routeContractKey().equals(operation.routeContractKey()))
                .filter(route -> route.routeKind().equals(operation.routeKind()))
                .filter(route -> route.readOnly() == operation.readOnly())
                .filter(route -> route.highRisk() == operation.highRisk())
                .filter(route -> operation.permission().equals(route.resolvedCapabilityCode()))
                .toList();
        if (exact.size() != 1 || exact.getFirst().capabilityContractKey() == null
                || operation.highRisk() && !"STEPUP-MGMT-HIGH-V1".equals(exact.getFirst().activationPolicy()))
            throw SignatureProviderErrors.forbidden();
        return new Current(actor, decision, scope, exact.getFirst());
    }

    @Override public void unchanged(Current original) {
        if (!require(operation(original)).equals(original)) throw SignatureProviderErrors.forbidden();
    }

    private SignatureProviderOperation operation(Current current) {
        return java.util.Arrays.stream(SignatureProviderOperation.values())
                .filter(value -> value.routeContractKey().equals(current.route().routeContractKey()))
                .findFirst().orElseThrow(SignatureProviderErrors::forbidden);
    }
}
