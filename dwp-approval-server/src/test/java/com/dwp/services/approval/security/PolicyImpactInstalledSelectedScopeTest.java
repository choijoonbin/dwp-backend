package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.policyimpactsource.*;
import com.dwp.core.security.ScopedAuthorityToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;

/** Actual immutable v9 registry contributions; installed context/identity are explicit unit fixtures, not live Gateway evidence. */
class PolicyImpactInstalledSelectedScopeTest {
    final Instant now = Instant.now();
    final UUID policy = UUID.randomUUID(), person = UUID.randomUUID();
    final String scope = "scope-" + "a".repeat(64);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
    final PolicyImpactInstalledContext contexts = new PolicyImpactInstalledContext(identities, new PolicyImpactSourceJson(mapper), Clock.fixed(now, ZoneOffset.UTC));
    final Set<String> permissions = new HashSet<>(PolicyImpactSourceProtocol.REQUIRED.values());
    @BeforeEach void install() {
        String roles = "APP_CONFIG_ADMIN@RS_APPROVALS";
        for (var entry : PolicyImpactSourceProtocol.REQUIRED.entrySet()) roles += "," + ScopedAuthorityToken.wireToken(entry.getKey(), entry.getValue(), "RS_APPROVALS");
        var installed = new ApprovalPilotPepRegistry(mapper).authorize(new ApprovalPilotPepRegistry.RequestEvidence("GET", path(), permissions,
                roles, Set.of("APPROVAL_DESIGNER", "APPROVAL_OPERATOR"), PolicyImpactSourceProtocol.ROUTE, "expectedVersion=7", ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL));
        assertThat(installed.allowed()).isTrue(); assertThat(installed.authorities()).hasSize(3);
        ApprovalPilotAuthorizationContext.set(installed.authorities());
        ApprovalManagementScopeContext.set(scope, "RS_APPROVALS");
        ApprovalDecisionRevisionContext.set("psr-" + "b".repeat(64), now.plusSeconds(30).atOffset(ZoneOffset.UTC), "ctx-unit", scope, PolicyImpactSourceProtocol.ROUTE, "110");
        ApprovalRequestContext.set(99L, 42L, person, Set.of("APPROVAL_DESIGNER", "APPROVAL_OPERATOR"), permissions);
        when(identities.require(42, 99)).thenReturn(new ApprovalIdentityDirectory.Subject(42L, 99L, UUID.randomUUID(), person, "Unit subject", null, null, "ACTIVE",
                List.of("APPROVAL_DESIGNER", "APPROVAL_OPERATOR"), List.copyOf(permissions)));
    }
    @AfterEach void clear() { ApprovalRequestContext.clear(); ApprovalPilotAuthorizationContext.clear(); ApprovalManagementScopeContext.clear(); ApprovalDecisionRevisionContext.clear(); }
    @Test void actualCaptureAndPostIoRecheckAcceptOnlyOneInstalledSelectedScope() {
        var request = request("expectedVersion=7&contextScopeKey=" + scope); var captured = contexts.capture(request, policy, 7);
        assertThat(captured.contextScopeKey()).isEqualTo(scope); assertThat(captured.resourceSetKey()).isEqualTo("RS_APPROVALS");
        contexts.unchanged(captured, request);
        assertThat(contexts.capture(request("expectedVersion=7"), policy, 7).decisionRevision()).isEqualTo(captured.decisionRevision());
        ApprovalManagementScopeContext.set("scope-" + "c".repeat(64), "RS_APPROVALS");
        assertThatThrownBy(() -> contexts.unchanged(captured, request)).isInstanceOf(BaseException.class);
    }
    @Test void correctQueryAloneCannotReplaceMissingAllThreeInstalledContributionsOrRevisedAuthority() {
        var request = request("expectedVersion=7&contextScopeKey=" + scope); var original = contexts.capture(request, policy, 7);
        var authorities = ApprovalPilotAuthorizationContext.current().orElseThrow(); ApprovalPilotAuthorizationContext.set(authorities.subList(0, 1));
        assertThatThrownBy(() -> contexts.capture(request, policy, 7)).isInstanceOf(BaseException.class);
        ApprovalPilotAuthorizationContext.set(authorities);
        ApprovalDecisionRevisionContext.set("psr-" + "d".repeat(64), now.plusSeconds(30).atOffset(ZoneOffset.UTC), "ctx-unit", scope, PolicyImpactSourceProtocol.ROUTE, "110");
        assertThatThrownBy(() -> contexts.unchanged(original, request)).isInstanceOf(BaseException.class);
    }
    String path() { return "/v1/admin/policies/" + policy + "/impact"; }
    MockHttpServletRequest request(String query) { var request = new MockHttpServletRequest("GET", path()); request.setQueryString(query); request.addHeader("X-DWP-Active-Access-Mode", "NORMAL"); return request; }
}
