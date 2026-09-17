package com.dwp.services.approval.deployment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalPilotPepRegistry;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import com.dwp.services.approval.security.ApprovalStepUpReplayRepository;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentCommandGuard.Profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalDeploymentCommandGuardTest {
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final String REVISION = "psr-" + "a".repeat(64);
    private static final UUID PROMOTION_ID = UUID.randomUUID();

    private final ApprovalStepUpVerifier verifier = mock(ApprovalStepUpVerifier.class);
    private final ApprovalStepUpReplayRepository replay =
            mock(ApprovalStepUpReplayRepository.class);
    private final ApprovalDeploymentCommandGuard guard = new ApprovalDeploymentCommandGuard(
            verifier, replay, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void exactRouteStepUpIsBoundAndConsumed() {
        when(verifier.payloadSha256(any())).thenReturn("b".repeat(64));
        when(replay.reserve(any(), anyString(), anyString())).thenReturn(
                new ApprovalStepUpReplayRepository.Reservation(
                        UUID.randomUUID(), false, null));
        when(verifier.verify(anyString(), any())).thenAnswer(invocation -> {
            ApprovalStepUpVerifier.CommandBinding binding = invocation.getArgument(1);
            return new ApprovalStepUpVerifier.VerifiedChallenge(
                    "challenge-100", "nonce-100", "issuer", binding,
                    NOW.plusSeconds(300));
        });

        try (MockedStatic<ApprovalPilotAuthorizationContext> context =
                     mockStatic(ApprovalPilotAuthorizationContext.class)) {
            context.when(ApprovalPilotAuthorizationContext::highRisk)
                    .thenReturn(Optional.of(route(Profile.APPROVE)));
            var permit = guard.begin(
                    current(Profile.APPROVE), Profile.APPROVE,
                    PROMOTION_ID, 3, "Reviewed and approved",
                    ApprovalStepUpHeaders.of(
                            "signed.challenge", "approve-100", REVISION, 3L));

            assertThat(permit.priorResult()).isFalse();
            assertThat(permit.governed().stepUpEvidenceReference())
                    .matches("verified:[a-f0-9]{64}");
            assertThat(permit.governed().stepUpValidUntil())
                    .isEqualTo(NOW.plusSeconds(300));
            guard.complete(permit);
            verify(replay).consume(permit.challenge());
            verify(replay).commit(permit.reservation().id(), permit.challenge());
        }

        ArgumentCaptor<ApprovalStepUpVerifier.CommandBinding> binding =
                ArgumentCaptor.forClass(ApprovalStepUpVerifier.CommandBinding.class);
        verify(verifier).verify(anyString(), binding.capture());
        assertThat(binding.getValue().commandPath()).isEqualTo(
                "/api/approvals/v1/admin/operations/deployments/promotions/"
                        + PROMOTION_ID + "/approval");
        assertThat(binding.getValue().targetVersion()).isEqualTo(3);
        assertThat(binding.getValue().scopeRef()).isEqualTo("opaque-approvals");
    }

    @Test
    void packageCreateIsBoundToItsExactRouteTargetAndZeroVersion() {
        assertCreateBinding(
                Profile.PACKAGE_CREATE, UUID.randomUUID(),
                "APPROVAL_DEPLOYMENT_PACKAGE",
                "/api/approvals/v1/admin/operations/deployments/packages");
    }

    @Test
    void promotionCreateIsBoundToItsExactRouteTargetAndZeroVersion() {
        assertCreateBinding(
                Profile.PROMOTION_CREATE, UUID.randomUUID(),
                "APPROVAL_DEPLOYMENT_PROMOTION",
                "/api/approvals/v1/admin/operations/deployments/promotions");
    }

    @Test
    void missingExactRouteAuthorityFailsClosedAs503() {
        try (MockedStatic<ApprovalPilotAuthorizationContext> context =
                     mockStatic(ApprovalPilotAuthorizationContext.class)) {
            context.when(ApprovalPilotAuthorizationContext::highRisk)
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> guard.begin(
                    current(Profile.APPROVE), Profile.APPROVE,
                    PROMOTION_ID, 3, "Reviewed and approved",
                    ApprovalStepUpHeaders.of(
                            "signed.challenge", "approve-100", REVISION, 3L)))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(
                                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        }
    }

    @Test
    void anotherHighRiskRouteCannotAuthorizeTheCommand() {
        try (MockedStatic<ApprovalPilotAuthorizationContext> context =
                     mockStatic(ApprovalPilotAuthorizationContext.class)) {
            context.when(ApprovalPilotAuthorizationContext::highRisk)
                    .thenReturn(Optional.of(route(Profile.ROLLBACK)));

            assertThatThrownBy(() -> guard.begin(
                    current(Profile.APPROVE), Profile.APPROVE,
                    PROMOTION_ID, 3, "Reviewed and approved",
                    ApprovalStepUpHeaders.of(
                            "signed.challenge", "approve-100", REVISION, 3L)))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
    }

    private void assertCreateBinding(
            Profile profile,
            UUID targetId,
            String targetType,
            String expectedPath) {
        when(verifier.payloadSha256(any())).thenReturn("c".repeat(64));
        when(replay.reserve(any(), anyString(), anyString())).thenReturn(
                new ApprovalStepUpReplayRepository.Reservation(
                        UUID.randomUUID(), false, null));
        when(verifier.verify(anyString(), any())).thenAnswer(invocation -> {
            ApprovalStepUpVerifier.CommandBinding binding = invocation.getArgument(1);
            return new ApprovalStepUpVerifier.VerifiedChallenge(
                    "challenge-create", "nonce-create", "issuer", binding,
                    NOW.plusSeconds(300));
        });

        try (MockedStatic<ApprovalPilotAuthorizationContext> context =
                     mockStatic(ApprovalPilotAuthorizationContext.class)) {
            context.when(ApprovalPilotAuthorizationContext::highRisk)
                    .thenReturn(Optional.of(route(profile)));
            ApprovalDeploymentCommandGuard.Permit permit = guard.begin(
                    current(profile), profile, targetId, 0,
                    java.util.Map.of("targetId", targetId),
                    ApprovalStepUpHeaders.of(
                            "signed.challenge", "create-100", REVISION, 0L));
            guard.complete(permit);
        }

        ArgumentCaptor<ApprovalStepUpVerifier.CommandBinding> binding =
                ArgumentCaptor.forClass(ApprovalStepUpVerifier.CommandBinding.class);
        verify(verifier).verify(anyString(), binding.capture());
        assertThat(binding.getValue()).satisfies(value -> {
            assertThat(value.commandContractKey()).isEqualTo(profile.routeContractKey());
            assertThat(value.targetType()).isEqualTo(targetType);
            assertThat(value.targetId()).isEqualTo(targetId.toString());
            assertThat(value.targetVersion()).isZero();
            assertThat(value.commandMethod()).isEqualTo("POST");
            assertThat(value.commandPath()).isEqualTo(expectedPath);
            assertThat(value.scopeRef()).isEqualTo("opaque-approvals");
            assertThat(value.decisionRevision()).isEqualTo(REVISION);
        });
    }

    private ApprovalDeploymentHttpAuthority.Current current(Profile profile) {
        var actor = new ApprovalRequestContext.Actor(
                17L, 42L, UUID.randomUUID(), "Checker",
                Set.of("APPROVAL_OPERATOR"),
                Set.of(ApprovalDeploymentHttpAuthority.EXECUTE_PERMISSION));
        var scope = new ApprovalDeploymentModels.Scope(
                42L, "RS_APPROVALS", 17L,
                EnumSet.allOf(ApprovalDeploymentModels.Capability.class));
        var management = new ApprovalManagementScopeContext.Evidence(
                "opaque-approvals", "RS_APPROVALS");
        var decision = new ApprovalDecisionRevisionContext.Evidence(
                REVISION, OffsetDateTime.ofInstant(NOW.plusSeconds(600), ZoneOffset.UTC),
                "context-approvals", "opaque-approvals",
                profile.routeContractKey(), "111");
        return new ApprovalDeploymentHttpAuthority.Current(
                actor, scope, management, decision);
    }

    private ApprovalPilotPepRegistry.RouteAuthority route(Profile profile) {
        return new ApprovalPilotPepRegistry.RouteAuthority(
                profile.routeContractKey(), "ACTION", "full-management", false,
                Set.of(), "approvals.operations.execute", "STEPUP-MGMT-HIGH-V1",
                "sod.approval.operations.v1", true, null, null);
    }
}
