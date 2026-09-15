package com.dwp.services.approval.signatureproviders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SignatureProviderNativeAuthorityTest {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final UUID PERSON = UUID.nameUUIDFromBytes(
            "signature-person".getBytes(StandardCharsets.UTF_8));
    private final ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
    private final ApprovalSignatureProviderAuthority authority = new ApprovalSignatureProviderAuthority(
            new ApprovalWorkAuthority(identities));

    @AfterEach
    void clearContexts() {
        ApprovalRequestContext.clear();
        invoke(ApprovalDecisionRevisionContext.class, "clear", new Class<?>[0]);
        invoke(ApprovalManagementScopeContext.class, "clear", new Class<?>[0]);
        invoke(ApprovalPilotAuthorizationContext.class, "clear", new Class<?>[0]);
    }

    @Test
    void acceptsAllNineteenOperationsOnlyWithTheirExactCurrentAuthority() {
        for (SignatureProviderOperation operation : SignatureProviderOperation.values()) {
            install(operation, "110", route(operation));
            var current = authority.require(operation);
            assertThat(current.route().routeContractKey()).isEqualTo(operation.routeContractKey());
            assertThat(current.route().routeKind()).isEqualTo(operation.routeKind());
            assertThat(current.route().highRisk()).isEqualTo(operation.highRisk());
            authority.unchanged(current);
        }
    }

    @Test
    void rejectsAliasDuplicateAndChangedRouteSemantics() {
        var operation = SignatureProviderOperation.EXTERNAL_HANDOVER;
        install(operation, "110", route(operation));
        assertError(ErrorCode.FORBIDDEN, () -> {
            setDecision(operation.routeContractKey() + ".alias", "110", SHA_A,
                    OffsetDateTime.now().plusMinutes(5));
            authority.require(operation);
        });

        install(operation, "110", route(operation), route(operation));
        assertError(ErrorCode.FORBIDDEN, () -> authority.require(operation));

        install(operation, "110", changed(operation, operation.routeKind(), operation.readOnly(),
                false, operation.permission(), "STEPUP-MGMT-HIGH-V1"));
        assertError(ErrorCode.FORBIDDEN, () -> authority.require(operation));

        install(operation, "110", changed(operation, "DATA", true,
                operation.highRisk(), operation.permission(), "STEPUP-MGMT-HIGH-V1"));
        assertError(ErrorCode.FORBIDDEN, () -> authority.require(operation));

        install(operation, "110", changed(operation, operation.routeKind(), operation.readOnly(),
                operation.highRisk(), "ACTION.APPROVAL_SIGNATURE:UPDATE", "STEPUP-MGMT-HIGH-V1"));
        assertError(ErrorCode.FORBIDDEN, () -> authority.require(operation));

        install(operation, "100", route(operation));
        assertError(ErrorCode.FORBIDDEN, () -> authority.require(operation));
    }

    @Test
    void rejectsMissingProviderAndChangedAuthorityWithoutTreatingItAsCached() {
        var operation = SignatureProviderOperation.DIAGNOSTICS;
        actor(operation, Set.of("PROVIDER_SUPPORT"));
        setDecision(operation.routeContractKey(), "110", SHA_A,
                OffsetDateTime.now().plusMinutes(5));
        setScope();
        setRoutes(List.of(route(operation)));
        assertError(ErrorCode.FORBIDDEN, () -> authority.require(operation));

        install(operation, "110", route(operation));
        var original = authority.require(operation);
        setDecision(operation.routeContractKey(), "110", SHA_B,
                OffsetDateTime.now().plusMinutes(5));
        assertError(ErrorCode.FORBIDDEN, () -> authority.unchanged(original));

        clearContexts();
        actor(operation, Set.of("APPROVAL_OPERATOR"));
        assertError(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                () -> authority.require(operation));
    }

    @Test
    void highRiskGuardRejectsMissingAndChangedHeadersBeforeVerification() {
        var operation = SignatureProviderOperation.POLICY_PUBLISH;
        install(operation, "110", route(operation));
        var current = authority.require(operation);
        var verifier = mock(ApprovalStepUpVerifier.class);
        var replay = mock(ApprovalStepUpReplayRepository.class);
        var guard = new ApprovalSignatureProviderHighGuard(verifier, replay);
        UUID target = UUID.randomUUID();
        var body = Map.of("expectedVersion", 7);

        assertError(ErrorCode.STEP_UP_REQUIRED, () -> guard.verify(operation, current,
                "APPROVAL_SIGNATURE_POLICY", target, 7, operation.path(target, null),
                "publish-key", body, null));
        assertError(ErrorCode.STEP_UP_CHALLENGE_MISMATCH, () -> guard.verify(operation, current,
                "APPROVAL_SIGNATURE_POLICY", target, 7, operation.path(target, null),
                "publish-key", body, ApprovalStepUpHeaders.of("challenge", "different-key",
                        current.decision().revision(), 7L)));
        verifyNoInteractions(verifier, replay);
    }

    @Test
    void exactHighRiskHeadersRemainFailClosedWhenVerifierIsUnavailable() {
        var operation = SignatureProviderOperation.EXTERNAL_HANDOVER;
        install(operation, "110", route(operation));
        var current = authority.require(operation);
        var verifier = mock(ApprovalStepUpVerifier.class);
        var replay = mock(ApprovalStepUpReplayRepository.class);
        var guard = new ApprovalSignatureProviderHighGuard(verifier, replay);
        UUID target = UUID.randomUUID();
        var body = Map.of("expectedVersion", 4);
        when(verifier.payloadSha256(body)).thenReturn(SHA_A);
        when(verifier.verify(eq("challenge"), any()))
                .thenThrow(new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));

        assertError(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, () -> guard.verify(operation,
                current, "EXTERNAL_SIGNATURE_REQUEST", target, 4,
                operation.path(target, null), "handover-key", body,
                ApprovalStepUpHeaders.of("challenge", "handover-key",
                        current.decision().revision(), 4L)));
        verifyNoInteractions(replay);
    }

    private void install(SignatureProviderOperation operation, String rollout,
                         ApprovalPilotPepRegistry.RouteAuthority... routes) {
        clearContexts();
        actor(operation, Set.of("APPROVAL_OPERATOR"));
        setDecision(operation.routeContractKey(), rollout, SHA_A,
                OffsetDateTime.now().plusMinutes(5));
        setScope();
        setRoutes(List.of(routes));
    }

    private void actor(SignatureProviderOperation operation, Set<String> roles) {
        Set<String> permissions = Set.of("APP.APPROVALS:VIEW", operation.permission());
        ApprovalRequestContext.set(99L, 42L, PERSON, "Signer", roles, permissions);
        when(identities.require(42, 99)).thenReturn(new ApprovalIdentityDirectory.Subject(
                42L, 99L, UUID.randomUUID(), PERSON, "Signer", "signer@example.test",
                "Manager", "ACTIVE", List.copyOf(roles), List.copyOf(permissions)));
    }

    private void setDecision(String route, String rollout, String revisionSha,
                             OffsetDateTime validUntil) {
        invoke(ApprovalDecisionRevisionContext.class, "set",
                new Class<?>[]{String.class, OffsetDateTime.class, String.class,
                        String.class, String.class, String.class},
                "psr-" + revisionSha, validUntil, "signature-context",
                "signature-scope", route, rollout);
    }

    private void setScope() {
        invoke(ApprovalManagementScopeContext.class, "set",
                new Class<?>[]{String.class, String.class},
                "signature-scope", "RS_APPROVALS");
    }

    private void setRoutes(List<ApprovalPilotPepRegistry.RouteAuthority> routes) {
        invoke(ApprovalPilotAuthorizationContext.class, "set",
                new Class<?>[]{List.class}, routes);
    }

    private ApprovalPilotPepRegistry.RouteAuthority route(SignatureProviderOperation operation) {
        return changed(operation, operation.routeKind(), operation.readOnly(), operation.highRisk(),
                operation.permission(), operation.highRisk()
                        ? "STEPUP-MGMT-HIGH-V1" : "ROLLOUT-EXACT-V1");
    }

    private ApprovalPilotPepRegistry.RouteAuthority changed(SignatureProviderOperation operation,
            String kind, boolean readOnly, boolean highRisk, String permission, String activation) {
        return new ApprovalPilotPepRegistry.RouteAuthority(operation.routeContractKey(), kind,
                "APPROVAL_SIGNATURE_NATIVE", readOnly,
                Set.of("predicate.approval.signature-native.v1"),
                "capability.approval.signature-native." + operation.name().toLowerCase(),
                activation, "SOD-SIGNATURE", highRisk, null, null,
                null, null, null, permission, null);
    }

    private void assertError(ErrorCode expected,
                             org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        Throwable thrown = catchThrowable(work);
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
