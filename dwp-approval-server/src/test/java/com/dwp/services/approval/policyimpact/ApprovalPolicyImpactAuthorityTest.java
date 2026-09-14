package com.dwp.services.approval.policyimpact;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.exception.BaseException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ApprovalPolicyImpactAuthorityTest {
    static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    static ApprovalPolicyImpactAuthority.Window window(String scope, Map<String, ApprovalPolicyImpactAuthority.Grant> grants) {
        return new ApprovalPolicyImpactAuthority.Window(42, 99, scope, "context", "opaque-scope", "psr-" + "a".repeat(64),
                ApprovalPolicyImpactAuthority.ROUTE, "110", NOW.plusSeconds(60), "NORMAL", false, false, true, grants);
    }
    static Map<String, ApprovalPolicyImpactAuthority.Grant> grants(String scope) {
        var result = new java.util.HashMap<String, ApprovalPolicyImpactAuthority.Grant>();
        ApprovalPolicyImpactAuthority.REQUIRED.forEach((key, permission) -> result.put(key, new ApprovalPolicyImpactAuthority.Grant(scope, permission)));
        return result;
    }
    @Test void allThreeExactViewsOnOneResourceSetAreRequired() {
        var exact = window("RS_TEAM_A", grants("RS_TEAM_A"));
        assertThat(new ApprovalPolicyImpactAuthority(() -> exact, Clock.fixed(NOW, ZoneOffset.UTC)).capture()).isEqualTo(exact);
        for (String capability : ApprovalPolicyImpactAuthority.REQUIRED.keySet()) {
            var missing = new java.util.HashMap<>(exact.grants()); missing.remove(capability);
            denied(window("RS_TEAM_A", missing));
        }
    }
    @Test void otherResourceSetOrUpdateCannotSubstituteForView() {
        var wrong = new java.util.HashMap<>(grants("RS_TEAM_A"));
        wrong.put("approvals.operations.read", new ApprovalPolicyImpactAuthority.Grant("RS_TEAM_B", "ADMIN.APPROVAL_OPERATIONS:VIEW"));
        denied(window("RS_TEAM_A", wrong));
        wrong.put("approvals.operations.read", new ApprovalPolicyImpactAuthority.Grant("RS_TEAM_A", "ADMIN.APPROVAL_OPERATIONS:EXECUTE"));
        denied(window("RS_TEAM_A", wrong));
    }
    @Test void expirationAndChangedScopeOrRevocationFailThePostCheck() {
        var source = new AtomicReference<>(window("RS_TEAM_A", grants("RS_TEAM_A")));
        var guard = new ApprovalPolicyImpactAuthority(source::get, Clock.fixed(NOW, ZoneOffset.UTC));
        var captured = guard.capture(); source.set(window("RS_TEAM_B", grants("RS_TEAM_B")));
        assertThatThrownBy(() -> guard.unchanged(captured)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> new ApprovalPolicyImpactAuthority(() -> captured, Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC)).capture())
                .isInstanceOf(BaseException.class);
        var revoked = new java.util.HashMap<>(captured.grants()); revoked.remove("approvals.policy.read");
        source.set(window("RS_TEAM_A", revoked));
        assertThatThrownBy(() -> guard.unchanged(captured)).isInstanceOf(BaseException.class);
    }
    @Test void providerSupportWrongRouteAndInactiveRolloutCannotBorrowThreeViewGrants() {
        for (var value : java.util.List.of(change("NORMAL", true, false, "110", ApprovalPolicyImpactAuthority.ROUTE),
                change("PROVIDER_SUPPORT", false, true, "111", ApprovalPolicyImpactAuthority.ROUTE),
                change("NORMAL", false, true, "110", ApprovalPolicyImpactAuthority.ROUTE),
                change("NORMAL", false, false, "100", ApprovalPolicyImpactAuthority.ROUTE),
                change("NORMAL", false, false, "000", ApprovalPolicyImpactAuthority.ROUTE),
                change("NORMAL", false, false, "111", "route.approvals.admin.policies.data"))) denied(value);
        var elevated = change("ELEVATED", false, false, "111", ApprovalPolicyImpactAuthority.ROUTE);
        assertThat(new ApprovalPolicyImpactAuthority(() -> elevated, Clock.fixed(NOW, ZoneOffset.UTC)).capture()).isEqualTo(elevated);
    }
    private ApprovalPolicyImpactAuthority.Window change(String mode, boolean provider, boolean support, String rollout, String route) {
        var source = window("RS_TEAM_A", grants("RS_TEAM_A"));
        return new ApprovalPolicyImpactAuthority.Window(source.tenantId(), source.actorId(), source.resourceSetKey(),
                source.contextKey(), source.contextScopeKey(), source.decisionRevision(), route, rollout,
                source.validUntil(), mode, provider, support, true, source.grants());
    }
    private void denied(ApprovalPolicyImpactAuthority.Window window) {
        assertThatThrownBy(() -> new ApprovalPolicyImpactAuthority(() -> window, Clock.fixed(NOW, ZoneOffset.UTC)).capture())
                .isInstanceOf(BaseException.class);
    }
}
