package com.dwp.services.payroll.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class Hrm013PayrollControlPlaneAuthorityClientTest {
    @Test
    void neverFallsBackWhenExternalCredentialsAreUnavailable() {
        var credentials = mock(Hrm013PayrollWorkloadCredentialProvider.class);
        when(credentials.current(anyLong(), anyString(), any(), any()))
                .thenThrow(Hrm013SafeErrorV1.authorityUnavailable(1));
        var client = new AuthHrm013ServiceAuthorityClient(
                new ObjectMapper(), credentials, "https://auth.internal");
        var selector = new Hrm013WorkloadProofV1.UntrustedSelector(41L,
                new Hrm013WorkloadProofV1.BusinessAuthority(
                        Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, 7L, 9L), null);

        assertThatThrownBy(() -> client.resolve(
                Hrm013WorkloadProofV1.taskDispatch(
                        Hrm013WorkloadProofV1.TaskOwner.PAYROLL),
                selector, "people-kid-001", UUID.randomUUID()))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(failure -> ((Hrm013SafeErrorV1.Failure) failure)
                        .errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.AUTHORITY_UNAVAILABLE);
    }

    @Test
    void quiescedNewWorkCanContinueOnlyThroughAuthAdmittedTerminalDrain() {
        var client = spy(new AuthHrm013ServiceAuthorityClient(
                new ObjectMapper(), mock(Hrm013PayrollWorkloadCredentialProvider.class),
                "https://auth.internal"));
        UUID caseId = UUID.fromString("837c3b63-b54b-49f0-9422-f6e3820502bb");
        UUID taskId = UUID.fromString("1af26e53-b11a-4dda-ac1c-6f9a8d39356c");
        UUID correlation = UUID.fromString("d6990436-76cf-4eb7-b97d-a98da96979fa");
        UUID root = UUID.fromString("1c36c59e-b557-4f39-9f13-eb8be42063e2");
        UUID child = Hrm013WorkloadProofV1.terminalChildEventId(
                41L, root, 1, "SVC-PEP-HRM-008");
        var drain = new Hrm013WorkloadProofV1.DrainClaims(root,
                Instant.parse("2026-10-27T00:00:00Z"), "a".repeat(64),
                Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD,
                child, 1, "b".repeat(64));
        var profile = Hrm013WorkloadProofV1.hrm008(
                Hrm013WorkloadProofV1.TaskOwner.PAYROLL, caseId, taskId);
        var drainedAuthority = mock(AuthHrm013ServiceAuthorityClient.Authority.class);
        doThrow(Hrm013SafeErrorV1.authorityDenied()).when(client)
                .resolveCallbackProfile(profile, 41L,
                        Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null, correlation);
        doReturn(drainedAuthority).when(client)
                .resolveCallbackProfile(profile, 41L,
                        Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                        drain, correlation);

        var admitted = client.authorizeCallback(profile, 41L,
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, drain, correlation);

        assertThat(admitted.authority()).isSameAs(drainedAuthority);
        assertThat(admitted.mode())
                .isEqualTo(Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING);
        assertThat(admitted.drain()).isEqualTo(drain);
    }
}
