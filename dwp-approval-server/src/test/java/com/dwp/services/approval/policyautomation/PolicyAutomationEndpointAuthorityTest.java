package com.dwp.services.approval.policyautomation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalPilotPepRegistry;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import com.dwp.services.approval.security.ApprovalStepUpReplayRepository;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PolicyAutomationEndpointAuthorityTest {
    private static final String UPDATE_ROUTE =
            "route.approvals.admin.policy-update.action";
    private static final String PUBLISH_ROUTE =
            "route.approvals.admin.policy-publish.action";
    private static final String DECISION = "policy-decision-9";
    private static final String SCOPE = "policy-scope";
    private static final UUID PERSON = UUID.randomUUID();
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    private final ApprovalStepUpVerifier verifier = mock(ApprovalStepUpVerifier.class);
    private final ApprovalStepUpReplayRepository replay =
            mock(ApprovalStepUpReplayRepository.class);
    private final ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
    private final PolicyAutomationEndpointAuthority authority =
            new PolicyAutomationEndpointAuthority(
                    verifier, replay, new ApprovalWorkAuthority(identities));

    @AfterEach
    void clearContexts() {
        ApprovalRequestContext.clear();
        invoke(ApprovalDecisionRevisionContext.class, "clear", new Class<?>[0]);
        invoke(ApprovalManagementScopeContext.class, "clear", new Class<?>[0]);
        invoke(ApprovalPilotAuthorizationContext.class, "clear", new Class<?>[0]);
    }

    @Test
    void exactLowRiskUpdateDoesNotRequireOrInvokeStepUp() {
        install(UPDATE_ROUTE, "UPDATE", route(
                UPDATE_ROUTE, PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY, false));
        UUID target = UUID.randomUUID();

        var permit = authority.begin(PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY,
                "DELEGATION_GOVERNANCE", target, 4, "POST",
                "/v1/admin/policies/automation/delegations/" + target + "/reviews",
                Map.of("expectedDelegationVersion", 4),
                ApprovalStepUpHeaders.of(null, "delegation-review-4", DECISION, 4L));
        authority.complete(permit);

        assertThat(permit.challenge()).isNull();
        assertThat(permit.reservation()).isNull();
        verifyNoInteractions(verifier, replay);
    }

    @Test
    void publishRejectsMissingStaleAndVersionMismatchedEvidenceBeforeVerification() {
        UUID target = UUID.randomUUID();
        install(PUBLISH_ROUTE, "PUBLISH", route(
                PUBLISH_ROUTE, PolicyAutomationEndpointAuthority.PUBLISH_CAPABILITY, true));
        assertError(ErrorCode.STEP_UP_CHALLENGE_MISMATCH, () -> authority.begin(
                PolicyAutomationEndpointAuthority.PUBLISH_CAPABILITY, "AUTOMATION_POLICY",
                target, 9, "POST", publishPath(target), Map.of("expectedVersion", 9),
                ApprovalStepUpHeaders.of(null, "policy-publish-9", DECISION, 9L)));

        assertError(ErrorCode.STEP_UP_CHALLENGE_MISMATCH, () -> authority.begin(
                PolicyAutomationEndpointAuthority.PUBLISH_CAPABILITY, "AUTOMATION_POLICY",
                target, 9, "POST", publishPath(target), Map.of("expectedVersion", 9),
                ApprovalStepUpHeaders.of("signed", "policy-publish-9", DECISION, 8L)));

        setDecision(PUBLISH_ROUTE, OffsetDateTime.now().minusSeconds(1));
        assertError(ErrorCode.FORBIDDEN, () -> authority.begin(
                PolicyAutomationEndpointAuthority.PUBLISH_CAPABILITY, "AUTOMATION_POLICY",
                target, 9, "POST", publishPath(target), Map.of("expectedVersion", 9),
                ApprovalStepUpHeaders.of("signed", "policy-publish-9", DECISION, 9L)));
        verifyNoInteractions(verifier, replay);
    }

    @Test
    void publishDelegatesExactTargetAndVersionBindingAndPropagatesMismatch() {
        UUID target = UUID.randomUUID();
        install(PUBLISH_ROUTE, "PUBLISH", route(
                PUBLISH_ROUTE, PolicyAutomationEndpointAuthority.PUBLISH_CAPABILITY, true));
        when(verifier.payloadSha256(any())).thenReturn(SHA_A, SHA_B);
        when(replay.reserve(any(), eq(PUBLISH_ROUTE), eq(SHA_B))).thenReturn(
                new ApprovalStepUpReplayRepository.Reservation(UUID.randomUUID(), false, null));
        when(verifier.verify(eq("signed"), any())).thenThrow(new BaseException(
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                "Signed challenge target does not match."));

        assertError(ErrorCode.STEP_UP_CHALLENGE_MISMATCH, () -> authority.begin(
                PolicyAutomationEndpointAuthority.PUBLISH_CAPABILITY, "AUTOMATION_POLICY",
                target, 9, "POST", publishPath(target), Map.of("expectedVersion", 9),
                ApprovalStepUpHeaders.of("signed", "policy-publish-9", DECISION, 9L)));

        ArgumentCaptor<ApprovalStepUpVerifier.CommandBinding> binding =
                ArgumentCaptor.forClass(ApprovalStepUpVerifier.CommandBinding.class);
        verify(verifier).verify(eq("signed"), binding.capture());
        assertThat(binding.getValue().targetType()).isEqualTo("AUTOMATION_POLICY");
        assertThat(binding.getValue().targetId()).isEqualTo(target.toString());
        assertThat(binding.getValue().targetVersion()).isEqualTo(9);
        assertThat(binding.getValue().commandPath())
                .isEqualTo("/api/approvals" + publishPath(target));
    }

    @Test
    void confusedDeputyAndRiskTierSubstitutionFailClosed() {
        install(UPDATE_ROUTE, "UPDATE",
                route(UPDATE_ROUTE, PolicyAutomationEndpointAuthority.PUBLISH_CAPABILITY, true),
                route("route.other", PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY, false));
        assertUpdateForbidden();

        install(UPDATE_ROUTE, "UPDATE", route(
                UPDATE_ROUTE, PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY, true));
        assertUpdateForbidden();

        var duplicate = route(
                UPDATE_ROUTE, PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY, false);
        install(UPDATE_ROUTE, "UPDATE", duplicate, duplicate);
        assertUpdateForbidden();
        verifyNoInteractions(verifier, replay);
    }

    @Test
    void currentAuthorityFailuresStopBeforeThePolicyService() {
        PolicyAutomationService service = mock(PolicyAutomationService.class);
        DelegationGovernanceService delegations = mock(DelegationGovernanceService.class);
        PolicyAutomationEndpointService endpoints =
                new PolicyAutomationEndpointService(service, delegations, authority);
        UUID calendarId = UUID.randomUUID();
        PolicyAutomationModels.CalendarDraft draft = new PolicyAutomationModels.CalendarDraft(
                calendarId, "CALENDAR.KR", "Korea", "Asia/Seoul",
                Map.of(), List.of(), List.of(), PolicyAutomationModels.Lifecycle.DRAFT, 9);
        ApprovalStepUpHeaders headers = ApprovalStepUpHeaders.of(
                null, "policy-update-9", DECISION, 9L);

        install(UPDATE_ROUTE, "UPDATE", route(
                UPDATE_ROUTE, PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY, false));
        doReturn(subject("ACTIVE", PolicyAutomationEndpointAuthority.RESOURCE + ":VIEW"))
                .when(identities).require(42L, 19L);
        assertError(ErrorCode.FORBIDDEN, () -> endpoints.saveCalendar(draft, headers));

        install(UPDATE_ROUTE, "UPDATE", route(
                UPDATE_ROUTE, PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY, false));
        doReturn(subject("INACTIVE", PolicyAutomationEndpointAuthority.RESOURCE + ":UPDATE"))
                .when(identities).require(42L, 19L);
        assertError(ErrorCode.FORBIDDEN, () -> endpoints.saveCalendar(draft, headers));

        install(UPDATE_ROUTE, "UPDATE", route(
                UPDATE_ROUTE, PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY, false));
        doThrow(new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR))
                .when(identities).require(42L, 19L);
        assertError(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                () -> endpoints.saveCalendar(draft, headers));

        install(UPDATE_ROUTE, "UPDATE", route(
                UPDATE_ROUTE, PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY, false));
        invoke(ApprovalManagementScopeContext.class, "set",
                new Class<?>[]{String.class, String.class}, "other-scope", "RS_APPROVALS");
        assertError(ErrorCode.FORBIDDEN, () -> endpoints.saveCalendar(draft, headers));

        verifyNoInteractions(service, delegations, verifier, replay);
    }

    private void assertUpdateForbidden() {
        UUID target = UUID.randomUUID();
        assertError(ErrorCode.FORBIDDEN, () -> authority.begin(
                PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY, "AUTOMATION_POLICY",
                target, 9, "PUT", "/v1/admin/policies/automation/rules/" + target + "/draft",
                Map.of("expectedVersion", 9),
                ApprovalStepUpHeaders.of(null, "policy-update-9", DECISION, 9L)));
    }

    private void install(
            String route,
            String action,
            ApprovalPilotPepRegistry.RouteAuthority... authorities) {
        clearContexts();
        ApprovalRequestContext.set(19L, 42L, PERSON, Set.of("APPROVAL_ADMIN"),
                Set.of(PolicyAutomationEndpointAuthority.RESOURCE + ":" + action));
        doReturn(subject("ACTIVE", PolicyAutomationEndpointAuthority.RESOURCE + ":" + action))
                .when(identities).require(42L, 19L);
        setDecision(route, OffsetDateTime.now().plusMinutes(5));
        invoke(ApprovalManagementScopeContext.class, "set",
                new Class<?>[]{String.class, String.class}, SCOPE, "RS_APPROVALS");
        invoke(ApprovalPilotAuthorizationContext.class, "set",
                new Class<?>[]{List.class}, List.of(authorities));
    }

    private ApprovalIdentityDirectory.Subject subject(
            String status, String permission) {
        return new ApprovalIdentityDirectory.Subject(
                42L, 19L, UUID.randomUUID(), PERSON, "Policy admin",
                "policy@example.test", "Administrator", status,
                List.of("APPROVAL_ADMIN"),
                List.of("APP.APPROVALS:VIEW", permission));
    }

    private void setDecision(String route, OffsetDateTime validUntil) {
        invoke(ApprovalDecisionRevisionContext.class, "set",
                new Class<?>[]{String.class, OffsetDateTime.class, String.class,
                        String.class, String.class, String.class},
                DECISION, validUntil, "policy-context", SCOPE, route, "111");
    }

    private ApprovalPilotPepRegistry.RouteAuthority route(
            String route, String capability, boolean highRisk) {
        return new ApprovalPilotPepRegistry.RouteAuthority(
                route, "ACTION", "full-management", false, Set.of(), capability,
                highRisk ? "STEPUP-MGMT-HIGH-V1" : "ROLLOUT-EXACT-V1",
                "SOD-APPROVAL-POLICY", highRisk, null, null);
    }

    private String publishPath(UUID target) {
        return "/v1/admin/policies/automation/rules/" + target + "/publish";
    }

    private void assertError(
            ErrorCode expected,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        Throwable thrown = catchThrowable(action);
        assertThat(thrown).isInstanceOf(BaseException.class);
        assertThat(((BaseException) thrown).getErrorCode()).isEqualTo(expected);
    }

    private static void invoke(Class<?> owner, String name, Class<?>[] types, Object... values) {
        try {
            Method method = owner.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(null, values);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
