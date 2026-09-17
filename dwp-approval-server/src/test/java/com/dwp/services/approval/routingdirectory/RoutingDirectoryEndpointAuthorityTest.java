package com.dwp.services.approval.routingdirectory;

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
import java.time.Instant;
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

class RoutingDirectoryEndpointAuthorityTest {
    private static final String UPDATE_ROUTE =
            "route.approvals.admin.workflow-update.action";
    private static final String PUBLISH_ROUTE =
            "route.approvals.admin.workflow-publish.action";
    private static final String DECISION = "routing-decision-7";
    private static final String SCOPE = "routing-scope";
    private static final UUID PERSON = UUID.randomUUID();
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    private final ApprovalStepUpVerifier verifier = mock(ApprovalStepUpVerifier.class);
    private final ApprovalStepUpReplayRepository replay =
            mock(ApprovalStepUpReplayRepository.class);
    private final ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
    private final RoutingDirectoryEndpointAuthority authority =
            new RoutingDirectoryEndpointAuthority(
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
                UPDATE_ROUTE, RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY, false));
        UUID target = UUID.randomUUID();

        var permit = authority.begin(RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY,
                "ROUTING_GROUP", target, 7, "PUT",
                "/v1/admin/workflows/routing-directory/groups/" + target,
                Map.of("expectedVersion", 7),
                ApprovalStepUpHeaders.of(null, "routing-update-7", DECISION, 7L));
        authority.complete(permit);

        assertThat(permit.challenge()).isNull();
        assertThat(permit.reservation()).isNull();
        verifyNoInteractions(verifier, replay);
    }

    @Test
    void publishRejectsMissingStaleAndVersionMismatchedEvidenceBeforeVerification() {
        UUID target = UUID.randomUUID();
        install(PUBLISH_ROUTE, "PUBLISH", route(
                PUBLISH_ROUTE, RoutingDirectoryEndpointAuthority.PUBLISH_CAPABILITY, true));
        assertError(ErrorCode.STEP_UP_CHALLENGE_MISMATCH, () -> authority.begin(
                RoutingDirectoryEndpointAuthority.PUBLISH_CAPABILITY, "ROUTING_GROUP",
                target, 7, "POST", publishPath(target), Map.of("expectedVersion", 7),
                ApprovalStepUpHeaders.of(null, "routing-publish-7", DECISION, 7L)));

        assertError(ErrorCode.STEP_UP_CHALLENGE_MISMATCH, () -> authority.begin(
                RoutingDirectoryEndpointAuthority.PUBLISH_CAPABILITY, "ROUTING_GROUP",
                target, 7, "POST", publishPath(target), Map.of("expectedVersion", 7),
                ApprovalStepUpHeaders.of("signed", "routing-publish-7", DECISION, 8L)));

        setDecision(PUBLISH_ROUTE, OffsetDateTime.now().minusSeconds(1));
        assertError(ErrorCode.FORBIDDEN, () -> authority.begin(
                RoutingDirectoryEndpointAuthority.PUBLISH_CAPABILITY, "ROUTING_GROUP",
                target, 7, "POST", publishPath(target), Map.of("expectedVersion", 7),
                ApprovalStepUpHeaders.of("signed", "routing-publish-7", DECISION, 7L)));
        verifyNoInteractions(verifier, replay);
    }

    @Test
    void publishDelegatesExactTargetAndVersionBindingAndPropagatesMismatch() {
        UUID target = UUID.randomUUID();
        install(PUBLISH_ROUTE, "PUBLISH", route(
                PUBLISH_ROUTE, RoutingDirectoryEndpointAuthority.PUBLISH_CAPABILITY, true));
        when(verifier.payloadSha256(any())).thenReturn(SHA_A, SHA_B);
        when(replay.reserve(any(), eq(PUBLISH_ROUTE), eq(SHA_B))).thenReturn(
                new ApprovalStepUpReplayRepository.Reservation(UUID.randomUUID(), false, null));
        when(verifier.verify(eq("signed"), any())).thenThrow(new BaseException(
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                "Signed challenge target does not match."));

        assertError(ErrorCode.STEP_UP_CHALLENGE_MISMATCH, () -> authority.begin(
                RoutingDirectoryEndpointAuthority.PUBLISH_CAPABILITY, "ROUTING_GROUP",
                target, 7, "POST", publishPath(target), Map.of("expectedVersion", 7),
                ApprovalStepUpHeaders.of("signed", "routing-publish-7", DECISION, 7L)));

        ArgumentCaptor<ApprovalStepUpVerifier.CommandBinding> binding =
                ArgumentCaptor.forClass(ApprovalStepUpVerifier.CommandBinding.class);
        verify(verifier).verify(eq("signed"), binding.capture());
        assertThat(binding.getValue().targetType()).isEqualTo("ROUTING_GROUP");
        assertThat(binding.getValue().targetId()).isEqualTo(target.toString());
        assertThat(binding.getValue().targetVersion()).isEqualTo(7);
        assertThat(binding.getValue().commandPath())
                .isEqualTo("/api/approvals" + publishPath(target));
    }

    @Test
    void confusedDeputyAndRiskTierSubstitutionFailClosed() {
        install(UPDATE_ROUTE, "UPDATE",
                route(UPDATE_ROUTE, RoutingDirectoryEndpointAuthority.PUBLISH_CAPABILITY, true),
                route("route.other", RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY, false));
        assertUpdateForbidden();

        install(UPDATE_ROUTE, "UPDATE", route(
                UPDATE_ROUTE, RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY, true));
        assertUpdateForbidden();

        var duplicate = route(
                UPDATE_ROUTE, RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY, false);
        install(UPDATE_ROUTE, "UPDATE", duplicate, duplicate);
        assertUpdateForbidden();
        verifyNoInteractions(verifier, replay);
    }

    @Test
    void currentAuthorityFailuresStopBeforeTheRoutingService() {
        RoutingDirectoryService service = mock(RoutingDirectoryService.class);
        RoutingDirectoryEndpointService endpoints =
                new RoutingDirectoryEndpointService(service, authority);
        UUID groupId = UUID.randomUUID();
        RoutingDirectoryModels.GroupDraft draft = new RoutingDirectoryModels.GroupDraft(
                groupId, "GROUP.TEST", "Test group", "", RoutingDirectoryModels.Lifecycle.DRAFT,
                Instant.parse("2026-09-16T00:00:00Z"), null, List.of(), 7);
        ApprovalStepUpHeaders headers = ApprovalStepUpHeaders.of(
                null, "routing-update-7", DECISION, 7L);

        install(UPDATE_ROUTE, "UPDATE", route(
                UPDATE_ROUTE, RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY, false));
        doReturn(subject("ACTIVE", RoutingDirectoryEndpointAuthority.RESOURCE + ":VIEW"))
                .when(identities).require(42L, 17L);
        assertError(ErrorCode.FORBIDDEN, () -> endpoints.saveGroup(draft, headers));

        install(UPDATE_ROUTE, "UPDATE", route(
                UPDATE_ROUTE, RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY, false));
        doReturn(subject("INACTIVE", RoutingDirectoryEndpointAuthority.RESOURCE + ":UPDATE"))
                .when(identities).require(42L, 17L);
        assertError(ErrorCode.FORBIDDEN, () -> endpoints.saveGroup(draft, headers));

        install(UPDATE_ROUTE, "UPDATE", route(
                UPDATE_ROUTE, RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY, false));
        doThrow(new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR))
                .when(identities).require(42L, 17L);
        assertError(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                () -> endpoints.saveGroup(draft, headers));

        install(UPDATE_ROUTE, "UPDATE", route(
                UPDATE_ROUTE, RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY, false));
        invoke(ApprovalManagementScopeContext.class, "set",
                new Class<?>[]{String.class, String.class}, "other-scope", "RS_APPROVALS");
        assertError(ErrorCode.FORBIDDEN, () -> endpoints.saveGroup(draft, headers));

        verifyNoInteractions(service, verifier, replay);
    }

    private void assertUpdateForbidden() {
        UUID target = UUID.randomUUID();
        assertError(ErrorCode.FORBIDDEN, () -> authority.begin(
                RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY, "ROUTING_GROUP",
                target, 7, "PUT", "/v1/admin/workflows/routing-directory/groups/" + target,
                Map.of("expectedVersion", 7),
                ApprovalStepUpHeaders.of(null, "routing-update-7", DECISION, 7L)));
    }

    private void install(
            String route,
            String action,
            ApprovalPilotPepRegistry.RouteAuthority... authorities) {
        clearContexts();
        ApprovalRequestContext.set(17L, 42L, PERSON, Set.of("APPROVAL_ADMIN"),
                Set.of(RoutingDirectoryEndpointAuthority.RESOURCE + ":" + action));
        doReturn(subject("ACTIVE", RoutingDirectoryEndpointAuthority.RESOURCE + ":" + action))
                .when(identities).require(42L, 17L);
        setDecision(route, OffsetDateTime.now().plusMinutes(5));
        invoke(ApprovalManagementScopeContext.class, "set",
                new Class<?>[]{String.class, String.class}, SCOPE, "RS_APPROVALS");
        invoke(ApprovalPilotAuthorizationContext.class, "set",
                new Class<?>[]{List.class}, List.of(authorities));
    }

    private ApprovalIdentityDirectory.Subject subject(
            String status, String permission) {
        return new ApprovalIdentityDirectory.Subject(
                42L, 17L, UUID.randomUUID(), PERSON, "Routing admin",
                "routing@example.test", "Administrator", status,
                List.of("APPROVAL_ADMIN"),
                List.of("APP.APPROVALS:VIEW", permission));
    }

    private void setDecision(String route, OffsetDateTime validUntil) {
        invoke(ApprovalDecisionRevisionContext.class, "set",
                new Class<?>[]{String.class, OffsetDateTime.class, String.class,
                        String.class, String.class, String.class},
                DECISION, validUntil, "routing-context", SCOPE, route, "111");
    }

    private ApprovalPilotPepRegistry.RouteAuthority route(
            String route, String capability, boolean highRisk) {
        return new ApprovalPilotPepRegistry.RouteAuthority(
                route, "ACTION", "full-management", false, Set.of(), capability,
                highRisk ? "STEPUP-MGMT-HIGH-V1" : "ROLLOUT-EXACT-V1",
                "SOD-APPROVAL-DESIGN", highRisk, null, null);
    }

    private String publishPath(UUID target) {
        return "/v1/admin/workflows/routing-directory/groups/" + target + "/publish";
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
