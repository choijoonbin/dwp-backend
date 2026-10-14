package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Modifier;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovedCompensationSnapshotRefetchServiceTest {

    @Test
    void refetchTransactionRemainsWritableForTheProviderAuditOutbox() throws Exception {
        var method = ApprovedCompensationSnapshotRefetchService.class.getDeclaredMethod(
                "get", ApprovedCompensationSnapshotRequestContext.VerifiedRequest.class);
        Transactional transaction = method.getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isFalse();
        assertThat(Modifier.isFinal(
                ApprovedCompensationSnapshotRefetchService.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(method.getModifiers())).isFalse();
    }

    @Test
    void revalidatesOwnerTenantThenUsesOnlyTheExactVerifiedTuple() {
        var authority = mock(ApprovedCompensationSnapshotOwnerAuthority.class);
        var provider = mock(ApprovedCompensationPlanSnapshotProvider.class);
        var service = new ApprovedCompensationSnapshotRefetchService(authority, provider);
        UUID snapshotId = UUID.fromString(
                "10000000-0000-4000-8000-000000000002");
        UUID correlationId = UUID.fromString(
                "10000000-0000-4000-8000-000000000008");
        String digest = "a".repeat(64);
        var request = new ApprovedCompensationSnapshotRequestContext.VerifiedRequest(
                ApprovedCompensationSnapshotIngressFilter.PATH_PREFIX + snapshotId,
                snapshotId, 4L, digest, 71L, 3L, 9L, correlationId,
                ApprovedCompensationPlanSnapshotProvider.CALLER_WORKLOAD,
                ApprovedCompensationPlanSnapshotProvider.AUDIENCE,
                ApprovedCompensationPlanSnapshotProvider.PURPOSE,
                ApprovedCompensationPlanSnapshotProvider.PROJECTION);
        Xcon020ApprovedCompensationPlanSnapshotV1 payload =
                mock(Xcon020ApprovedCompensationPlanSnapshotV1.class);
        when(provider.getExact(
                org.mockito.ArgumentMatchers.eq(snapshotId),
                org.mockito.ArgumentMatchers.eq(4L),
                org.mockito.ArgumentMatchers.eq(digest),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(payload);

        assertThat(service.get(request)).isSameAs(payload);

        var ordered = inOrder(authority, provider);
        ordered.verify(authority).requireActiveTenant(request);
        ArgumentCaptor<ApprovedCompensationPlanSnapshotProvider.ServicePepEvidence> evidence =
                ArgumentCaptor.forClass(
                        ApprovedCompensationPlanSnapshotProvider.ServicePepEvidence.class);
        ordered.verify(provider).getExact(
                org.mockito.ArgumentMatchers.eq(snapshotId),
                org.mockito.ArgumentMatchers.eq(4L),
                org.mockito.ArgumentMatchers.eq(digest),
                evidence.capture());
        assertThat(evidence.getValue()).isEqualTo(
                new ApprovedCompensationPlanSnapshotProvider.ServicePepEvidence(
                        true,
                        ApprovedCompensationPlanSnapshotProvider.CALLER_WORKLOAD,
                        ApprovedCompensationPlanSnapshotProvider.AUDIENCE,
                        71L, 71L, 3L, 9L,
                        ApprovedCompensationPlanSnapshotProvider.PURPOSE,
                        ApprovedCompensationPlanSnapshotProvider.PROJECTION,
                        correlationId));
    }
}
