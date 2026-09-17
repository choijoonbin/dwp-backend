package com.dwp.services.approval.incidents;

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

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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

class IncidentEndpointAuthorityTest {
    private static final long USER_ID = 17L;
    private static final long TENANT_ID = 42L;
    private static final UUID PERSON_ID = UUID.randomUUID();
    private static final String ROUTE = "route.approvals.admin.incident-create.action";
    private static final String DECISION = "incident-decision-1";
    private static final String SCOPE = "incident-scope";

    private final ApprovalStepUpVerifier verifier = mock(ApprovalStepUpVerifier.class);
    private final ApprovalStepUpReplayRepository replay =
            mock(ApprovalStepUpReplayRepository.class);
    private final ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
    private final IncidentEndpointAuthority authority = new IncidentEndpointAuthority(
            verifier, replay, new ApprovalWorkAuthority(identities));

    @AfterEach
    void clearContexts() {
        ApprovalRequestContext.clear();
        invoke(ApprovalDecisionRevisionContext.class, "clear", new Class<?>[0]);
        invoke(ApprovalManagementScopeContext.class, "clear", new Class<?>[0]);
        invoke(ApprovalPilotAuthorizationContext.class, "clear", new Class<?>[0]);
    }

    @Test
    void currentExecuteAuthorityAllowsAnExactlyBoundCommand() {
        install(subject("ACTIVE", List.of(
                "APP.APPROVALS:VIEW", IncidentEndpointAuthority.RESOURCE + ":EXECUTE")));
        IncidentService service = mock(IncidentService.class);
        IncidentEndpointService endpoints = new IncidentEndpointService(service, authority);
        IncidentModels.OpenIncident input = incident();
        ApprovalStepUpHeaders headers = headers();
        when(verifier.payloadSha256(any())).thenReturn("a".repeat(64), "b".repeat(64));
        when(replay.reserve(any(), eq(ROUTE), eq("b".repeat(64)))).thenReturn(
                new ApprovalStepUpReplayRepository.Reservation(UUID.randomUUID(), false, null));
        when(verifier.verify(eq("signed"), any())).thenAnswer(invocation ->
                new ApprovalStepUpVerifier.VerifiedChallenge(
                        "challenge", "nonce", "issuer", invocation.getArgument(1),
                        Instant.now().plusSeconds(60)));

        endpoints.open(input, headers);

        verify(service).open(headers.idempotencyKey(), input);
        verify(replay).consume(any());
        verify(replay).commit(any(), any());
    }

    @Test
    void revokedInactiveUnavailableAndScopeMismatchStopBeforeTheIncidentService() {
        IncidentService service = mock(IncidentService.class);
        IncidentEndpointService endpoints = new IncidentEndpointService(service, authority);
        IncidentModels.OpenIncident input = incident();
        ApprovalStepUpHeaders headers = headers();

        install(subject("ACTIVE", List.of("APP.APPROVALS:VIEW")));
        assertError(ErrorCode.FORBIDDEN, () -> endpoints.open(input, headers));

        install(subject("INACTIVE", List.of(
                "APP.APPROVALS:VIEW", IncidentEndpointAuthority.RESOURCE + ":EXECUTE")));
        assertError(ErrorCode.FORBIDDEN, () -> endpoints.open(input, headers));

        install(subject("ACTIVE", List.of(
                "APP.APPROVALS:VIEW", IncidentEndpointAuthority.RESOURCE + ":EXECUTE")));
        doThrow(new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR))
                .when(identities).require(TENANT_ID, USER_ID);
        assertError(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                () -> endpoints.open(input, headers));

        install(subject("ACTIVE", List.of(
                "APP.APPROVALS:VIEW", IncidentEndpointAuthority.RESOURCE + ":EXECUTE")));
        invoke(ApprovalManagementScopeContext.class, "set",
                new Class<?>[]{String.class, String.class}, "other-scope", "RS_APPROVALS");
        assertError(ErrorCode.FORBIDDEN, () -> endpoints.open(input, headers));

        verifyNoInteractions(service, verifier, replay);
    }

    private void install(ApprovalIdentityDirectory.Subject subject) {
        clearContexts();
        ApprovalRequestContext.set(USER_ID, TENANT_ID, PERSON_ID,
                Set.of("APPROVAL_ADMIN"),
                Set.of(IncidentEndpointAuthority.RESOURCE + ":EXECUTE"));
        doReturn(subject).when(identities).require(TENANT_ID, USER_ID);
        invoke(ApprovalDecisionRevisionContext.class, "set",
                new Class<?>[]{String.class, OffsetDateTime.class, String.class,
                        String.class, String.class, String.class},
                DECISION, OffsetDateTime.now().plusMinutes(5),
                "incident-context", SCOPE, ROUTE, "111");
        invoke(ApprovalManagementScopeContext.class, "set",
                new Class<?>[]{String.class, String.class}, SCOPE, "RS_APPROVALS");
        invoke(ApprovalPilotAuthorizationContext.class, "set",
                new Class<?>[]{List.class}, List.of(new ApprovalPilotPepRegistry.RouteAuthority(
                        ROUTE, "ACTION", "full-management", false, Set.of(),
                        IncidentEndpointAuthority.EXECUTE_CAPABILITY,
                        "STEPUP-MGMT-HIGH-V1", "SOD-APPROVAL-OPERATIONS",
                        true, null, null)));
    }

    private IncidentModels.OpenIncident incident() {
        return new IncidentModels.OpenIncident(
                UUID.randomUUID(), "INCIDENT.TEST", "Test incident",
                IncidentModels.Severity.HIGH, IncidentModels.SourceKind.MANUAL,
                "manual-test");
    }

    private ApprovalStepUpHeaders headers() {
        return ApprovalStepUpHeaders.of("signed", "incident-command-1", DECISION, 0L);
    }

    private ApprovalIdentityDirectory.Subject subject(
            String status, List<String> permissions) {
        return new ApprovalIdentityDirectory.Subject(
                TENANT_ID, USER_ID, UUID.randomUUID(), PERSON_ID,
                "Incident operator", "incident@example.test", "Administrator",
                status, List.of("APPROVAL_ADMIN"), permissions);
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
