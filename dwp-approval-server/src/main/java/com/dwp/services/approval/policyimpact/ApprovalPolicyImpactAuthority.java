package com.dwp.services.approval.policyimpact;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/** The controller must supply freshly verified Gateway/Auth grants, never client-declared permissions. */
public final class ApprovalPolicyImpactAuthority {
    public static final String ROUTE = "route.approvals.admin.policy-impact.data";
    public static final Map<String, String> REQUIRED = Map.of(
            "approvals.policy.read", "ADMIN.APPROVAL_POLICY:VIEW",
            "approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW",
            "approvals.operations.read", "ADMIN.APPROVAL_OPERATIONS:VIEW");
    @Schema(name="ApprovalPolicyImpactGrant", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Grant(String resourceSetKey, String permission) { }
    @Schema(name="ApprovalPolicyImpactAuthority", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Window(long tenantId, long actorId, String resourceSetKey, String contextKey,
            String contextScopeKey, String decisionRevision, String routeKey, String rolloutState,
            Instant validUntil, String accessMode, boolean providerIdentity, boolean supportSession,
            boolean entitlementSatisfied, Map<String, Grant> grants) {
        public Window { grants = Map.copyOf(grants); }
    }
    @FunctionalInterface public interface Port { Window requireCurrent(); }
    private final Port port;
    private final Clock clock;
    public ApprovalPolicyImpactAuthority(Port port, Clock clock) {
        this.port = java.util.Objects.requireNonNull(port);
        this.clock = java.util.Objects.requireNonNull(clock);
    }
    public Window capture() {
        Window value = port.requireCurrent();
        if (value == null || value.validUntil() == null || !value.validUntil().isAfter(clock.instant())
                || !text(value.contextKey()) || !text(value.contextScopeKey())
                || value.decisionRevision() == null || !value.decisionRevision().matches("psr-[a-f0-9]{64}")) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        }
        if (value.tenantId() <= 0 || value.actorId() <= 0 || value.providerIdentity() || value.supportSession()
                || !value.entitlementSatisfied() || !ROUTE.equals(value.routeKey())
                || !("110".equals(value.rolloutState()) || "111".equals(value.rolloutState()))
                || !("NORMAL".equals(value.accessMode()) || "ELEVATED".equals(value.accessMode()))
                || value.resourceSetKey() == null || !value.resourceSetKey().matches("[A-Z][A-Z0-9_]{2,79}")) forbidden();
        for (var requirement : REQUIRED.entrySet()) {
            Grant grant = value.grants().get(requirement.getKey());
            if (grant == null || !value.resourceSetKey().equals(grant.resourceSetKey())
                    || !requirement.getValue().equals(grant.permission())) forbidden();
        }
        return value;
    }
    public void unchanged(Window original) {
        if (!original.equals(capture())) throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT);
    }
    private boolean text(String value) { return value != null && !value.isBlank() && value.length() <= 512; }
    private void forbidden() { throw new BaseException(ErrorCode.FORBIDDEN); }
}
