package com.dwp.services.approval.auditrecords;

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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.auditrecords.ApprovalAuditCommandGuard.Profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalAuditCommandGuardTest {
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final String REVISION = "psr-" + "a".repeat(64);

    private final ApprovalStepUpVerifier verifier = mock(ApprovalStepUpVerifier.class);
    private final ApprovalStepUpReplayRepository replay =
            mock(ApprovalStepUpReplayRepository.class);
    private final ApprovalAuditCommandGuard guard =
            new ApprovalAuditCommandGuard(verifier, replay);

    @Test
    void exactAuditCommandBindingIsVerifiedConsumedAndCommitted() {
        UUID exportId = UUID.randomUUID();
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
                    .thenReturn(Optional.of(route(Profile.EXPORT_ATTESTATION)));
            ApprovalAuditCommandGuard.Permit permit = guard.begin(
                    current(Profile.EXPORT_ATTESTATION), Profile.EXPORT_ATTESTATION,
                    exportId, 7, "external-evidence",
                    headers("attestation-100", 7));

            assertThat(permit.priorResult()).isFalse();
            guard.complete(permit);
            verify(replay).consume(permit.challenge());
            verify(replay).commit(permit.reservation().id(), permit.challenge());
        }

        ArgumentCaptor<ApprovalStepUpVerifier.CommandBinding> binding =
                ArgumentCaptor.forClass(ApprovalStepUpVerifier.CommandBinding.class);
        verify(verifier).verify(anyString(), binding.capture());
        assertThat(binding.getValue()).satisfies(value -> {
            assertThat(value.commandContractKey())
                    .isEqualTo(Profile.EXPORT_ATTESTATION.routeContractKey());
            assertThat(value.activationPolicy())
                    .isEqualTo(ApprovalAuditCommandGuard.ACTIVATION_POLICY);
            assertThat(value.capabilityContractKey())
                    .isEqualTo(ApprovalAuditCommandGuard.CAPABILITY);
            assertThat(value.scopeRef()).isEqualTo("opaque-approvals");
            assertThat(value.targetType()).isEqualTo("APPROVAL_AUDIT_EXPORT");
            assertThat(value.targetId()).isEqualTo(exportId.toString());
            assertThat(value.targetVersion()).isEqualTo(7);
            assertThat(value.commandMethod()).isEqualTo("POST");
            assertThat(value.commandPath()).isEqualTo(
                    "/api/approvals/v1/admin/operations/audit-records/exports/"
                            + exportId + "/external-attestations");
            assertThat(value.idempotencyKey()).isEqualTo("attestation-100");
            assertThat(value.payloadSha256()).isEqualTo("b".repeat(64));
            assertThat(value.decisionRevision()).isEqualTo(REVISION);
        });
    }

    @Test
    void missingExactRouteAuthorityFailsClosedAs503() {
        try (MockedStatic<ApprovalPilotAuthorizationContext> context =
                     mockStatic(ApprovalPilotAuthorizationContext.class)) {
            context.when(ApprovalPilotAuthorizationContext::highRisk)
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> guard.begin(
                    current(Profile.EXPORT_CREATE), Profile.EXPORT_CREATE,
                    UUID.randomUUID(), 0, "export", headers("export-100", 0)))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(
                                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        }
    }

    @Test
    void anotherHighRiskRouteCannotAuthorizeTheAuditCommand() {
        try (MockedStatic<ApprovalPilotAuthorizationContext> context =
                     mockStatic(ApprovalPilotAuthorizationContext.class)) {
            context.when(ApprovalPilotAuthorizationContext::highRisk)
                    .thenReturn(Optional.of(route(Profile.SAVED_VIEW_CREATE)));

            assertThatThrownBy(() -> guard.begin(
                    current(Profile.EXPORT_CREATE), Profile.EXPORT_CREATE,
                    UUID.randomUUID(), 0, "export", headers("export-100", 0)))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
    }

    @Test
    void staleVersionOrDecisionEvidenceCannotBeRebound() {
        try (MockedStatic<ApprovalPilotAuthorizationContext> context =
                     mockStatic(ApprovalPilotAuthorizationContext.class)) {
            context.when(ApprovalPilotAuthorizationContext::highRisk)
                    .thenReturn(Optional.of(route(Profile.EXPORT_ATTESTATION)));

            assertThatThrownBy(() -> guard.begin(
                    current(Profile.EXPORT_ATTESTATION), Profile.EXPORT_ATTESTATION,
                    UUID.randomUUID(), 7, "external-evidence",
                    ApprovalStepUpHeaders.of(
                            "signed.challenge", "attestation-100",
                            "psr-" + "c".repeat(64), 6L)))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(
                                    ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
        }
    }

    @Test
    void committedIdempotentReplayReturnsPriorResultWithoutReusingChallenge() {
        UUID savedViewId = UUID.randomUUID();
        when(verifier.payloadSha256(any())).thenReturn("d".repeat(64));
        when(replay.reserve(any(), anyString(), anyString())).thenReturn(
                new ApprovalStepUpReplayRepository.Reservation(
                        UUID.randomUUID(), true,
                        new ApprovalStepUpReplayRepository.CommandReceipt(
                                1, "APPROVAL_AUDIT_SAVED_VIEW",
                                savedViewId.toString(), 0, "COMMITTED")));

        try (MockedStatic<ApprovalPilotAuthorizationContext> context =
                     mockStatic(ApprovalPilotAuthorizationContext.class)) {
            context.when(ApprovalPilotAuthorizationContext::highRisk)
                    .thenReturn(Optional.of(route(Profile.SAVED_VIEW_CREATE)));

            ApprovalAuditCommandGuard.Permit permit = guard.begin(
                    current(Profile.SAVED_VIEW_CREATE), Profile.SAVED_VIEW_CREATE,
                    savedViewId, 0, "saved-view", headers("saved-view-100", 0));

            assertThat(permit.priorResult()).isTrue();
            guard.complete(permit);
            verify(verifier, never()).verify(anyString(), any());
            verify(replay, never()).consume(any());
        }
    }

    private ApprovalStepUpHeaders headers(String idempotencyKey, long version) {
        return ApprovalStepUpHeaders.of(
                "signed.challenge", idempotencyKey, REVISION, version);
    }

    private ApprovalAuditHttpAuthority.Current current(Profile profile) {
        var actor = new ApprovalRequestContext.Actor(
                17L, 42L, UUID.randomUUID(), "Auditor",
                Set.of("APPROVAL_AUDITOR"),
                Set.of(ApprovalAuditHttpAuthority.EXECUTE_PERMISSION));
        var scope = new ApprovalAuditModels.Scope(
                42L, "RS_APPROVALS", 17L,
                EnumSet.allOf(ApprovalAuditModels.Capability.class));
        var management = new ApprovalManagementScopeContext.Evidence(
                "opaque-approvals", "RS_APPROVALS");
        var decision = new ApprovalDecisionRevisionContext.Evidence(
                REVISION, OffsetDateTime.ofInstant(NOW.plusSeconds(600), ZoneOffset.UTC),
                "context-approvals", "opaque-approvals",
                profile.routeContractKey(), "111");
        return new ApprovalAuditHttpAuthority.Current(
                actor, scope, management, decision);
    }

    private ApprovalPilotPepRegistry.RouteAuthority route(Profile profile) {
        return new ApprovalPilotPepRegistry.RouteAuthority(
                profile.routeContractKey(), "ACTION", "full-management", false,
                Set.of(), ApprovalAuditCommandGuard.CAPABILITY,
                ApprovalAuditCommandGuard.ACTIVATION_POLICY,
                "sod.approval.operations.v1", true, null, null);
    }
}
