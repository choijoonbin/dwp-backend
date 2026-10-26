package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Hrm013TaskDispatchRelayTest {
    @Test
    void emptyQueuePerformsNoAuthorityProofOrNetworkWork() {
        var store = mock(Hrm013PersistencePorts.TaskDispatchStore.class);
        var client = mock(Hrm013TaskDispatchClient.class);
        var proofs = mock(Hrm013TaskDispatchRelay.ProofFactory.class);
        var authorities = mock(Hrm013TaskDispatchRelay.CurrentAuthorities.class);
        when(store.claimNext(31L, Hrm013RuntimeTestSupport.NOW))
                .thenReturn(Optional.empty());
        var relay = new Hrm013TaskDispatchRelay(
                store, client, proofs, authorities, attempt -> Duration.ofSeconds(1),
                Clock.fixed(Hrm013RuntimeTestSupport.NOW, ZoneOffset.UTC));

        assertThat(relay.relayOne(31L)).isEmpty();
        verifyNoInteractions(client, proofs, authorities);
    }

    @Test
    void acceptanceUsesTheExactClaimAttemptAndLeaseDeadlineCas() {
        long tenantId = 31L;
        var fence = Hrm013RuntimeTestSupport.fence(Hrm013TaskDispatchV1.TIME, tenantId);
        Instant until = Hrm013RuntimeTestSupport.NOW.plusSeconds(30);
        var claim = new Hrm013PersistenceModels.TaskDispatchClaim(
                fence, 4, Hrm013RuntimeTestSupport.NOW, until);
        var store = mock(Hrm013PersistencePorts.TaskDispatchStore.class);
        var client = mock(Hrm013TaskDispatchClient.class);
        var authorities = mock(Hrm013TaskDispatchRelay.CurrentAuthorities.class);
        when(store.claimNext(tenantId, Hrm013RuntimeTestSupport.NOW))
                .thenReturn(Optional.of(claim));
        when(store.requireIntegrity(fence.dispatchEventId(), tenantId)).thenReturn(fence);
        when(authorities.dispatchCurrent(any())).thenReturn(true);
        when(authorities.callbackCurrent(any())).thenReturn(true);
        byte[] receipt = new byte[] {1};
        when(client.send(any(), any())).thenReturn(new Hrm013TaskDispatchClient.Delivery(
                receipt, Hrm013PersistenceModels.sha256(receipt), "b".repeat(64),
                Hrm013RuntimeTestSupport.NOW));
        when(store.accept(any())).thenReturn(
                Hrm013PersistenceModels.TaskDispatchDisposition.ACCEPTED);
        var relay = new Hrm013TaskDispatchRelay(
                store, client, (ignored, now) ->
                Hrm013RuntimeTestSupport.headers(fence.correlationId()), authorities,
                attempt -> Duration.ofSeconds(1),
                Clock.fixed(Hrm013RuntimeTestSupport.NOW, ZoneOffset.UTC));

        assertThat(relay.relayOne(tenantId)).contains(
                Hrm013PersistenceModels.TaskDispatchDisposition.ACCEPTED);
        ArgumentCaptor<Hrm013PersistenceModels.TaskDispatchAcceptance> accepted =
                ArgumentCaptor.forClass(Hrm013PersistenceModels.TaskDispatchAcceptance.class);
        verify(store).accept(accepted.capture());
        assertThat(accepted.getValue().expectedAttemptCount()).isEqualTo(4);
        assertThat(accepted.getValue().expectedClaimUntil()).isEqualTo(until);
    }
}
