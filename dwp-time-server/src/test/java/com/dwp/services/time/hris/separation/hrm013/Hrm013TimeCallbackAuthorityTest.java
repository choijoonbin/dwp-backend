package com.dwp.services.time.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Hrm013TimeCallbackAuthorityTest {
    @Test
    void hrm008IsASeparateCurrentAuthorityLaneWithNextOwnerRevision() {
        var bridge = Hrm013SeparationBridgeV1.HRM_008_TIME;
        UUID caseId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        var proof = Hrm013WorkloadProofV1.hrm008(
                Hrm013WorkloadProofV1.TaskOwner.TIME, caseId, taskId);

        assertThat(bridge.bindingId()).isEqualTo("SVC-PEP-HRM-008");
        assertThat(bridge.callerWorkloadId()).isEqualTo("dwp-time-server");
        assertThat(bridge.ownerWorkloadId()).isEqualTo("dwp-people-server");
        assertThat(bridge.purpose()).isEqualTo("HRIS_SEPARATION_TIME_TASK_RECEIPT");
        assertThat(proof.bindingId()).isEqualTo(bridge.bindingId());
        assertThat(proof.exactPath()).isEqualTo(bridge.path(caseId, taskId));
        assertThat(2L).as("dispatch owner revision 1 advances once at terminal callback")
                .isEqualTo(2L);
    }

    @Test
    void newWorkDenialFallsBackOnlyToTheFrozenTerminalChildDrain() {
        var client = mock(AuthHrm013ServiceAuthorityClient.class, CALLS_REAL_METHODS);
        long tenantId = 41;
        UUID root = UUID.randomUUID();
        var terminal = new Hrm013WorkloadProofV1.DrainClaims(
                root, Instant.parse("2026-10-27T00:00:00Z"), "a".repeat(64),
                Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD,
                UUID.randomUUID(), 2, "b".repeat(64));
        var drained = mock(AuthHrm013ServiceAuthorityClient.CurrentAuthority.class);
        doThrow(Hrm013SafeErrorV1.authorityDenied()).when(client).resolveCallback(
                tenantId, Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null);
        doReturn(drained).when(client).resolveCallback(
                tenantId, Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING, terminal);

        var admitted = client.authorizeCallback(tenantId,
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, terminal);

        assertThat(admitted.authority()).isSameAs(drained);
        assertThat(admitted.mode())
                .isEqualTo(Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING);
        assertThat(admitted.drain()).isEqualTo(terminal);
    }

    @Test
    void changedLeaseAndWrongDrainAttestingRoleFailClosed() {
        UUID leaseId = UUID.randomUUID();
        Instant expiry = Instant.parse("2026-10-27T00:05:00Z");
        var first = mock(Hrm013TimeWorkloadCredentialProvider.Lease.class);
        var same = mock(Hrm013TimeWorkloadCredentialProvider.Lease.class);
        var changed = mock(Hrm013TimeWorkloadCredentialProvider.Lease.class);
        when(first.leaseId()).thenReturn(leaseId);
        when(first.leaseVersion()).thenReturn(3L);
        when(first.leaseDigest()).thenReturn("a".repeat(64));
        when(first.expiresAt()).thenReturn(expiry);
        when(same.leaseId()).thenReturn(leaseId);
        when(same.leaseVersion()).thenReturn(3L);
        when(same.leaseDigest()).thenReturn("a".repeat(64));
        when(same.expiresAt()).thenReturn(expiry);
        when(changed.leaseId()).thenReturn(leaseId);
        when(changed.leaseVersion()).thenReturn(4L);
        when(changed.leaseDigest()).thenReturn("b".repeat(64));
        when(changed.expiresAt()).thenReturn(expiry);
        assertThat(AuthHrm013ServiceAuthorityClient.sameLease(first, same)).isTrue();
        assertThat(AuthHrm013ServiceAuthorityClient.sameLease(first, changed)).isFalse();

        Instant activity = Instant.parse("2026-10-27T00:00:00Z");
        var wrong = new HrisServiceGrantAuthorityV1.DrainAdmission(
                UUID.randomUUID(), 1, activity.minusSeconds(1),
                HrisServiceGrantAuthorityV1.ServiceRole.OWNER, 1, activity,
                activity.plusSeconds(30), activity.plusSeconds(65));
        assertThatThrownBy(() -> AuthHrm013ServiceAuthorityClient
                .requireDrainAttestingRole(wrong,
                        Hrm013TimeWorkloadCredentialProvider.ServiceRole.CALLER))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(value -> ((Hrm013SafeErrorV1.Failure) value).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.AUTHORITY_DENIED);
    }
}
