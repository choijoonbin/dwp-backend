package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Hrm013OutboundTlsBoundaryTest {
    @Test
    void approvalEndpointMustBeExactHttpsWithoutAuthorityHeaders() {
        assertThatThrownBy(() -> new Hrm013ApprovalClient(
                URI.create("http://approval.internal/unsafe"), request -> null,
                (fence, exchange) -> true)).isInstanceOf(IllegalArgumentException.class);

        var headers = new ArrayList<>(Hrm013RuntimeTestSupport.headers(
                java.util.UUID.randomUUID()));
        headers.add(new com.dwp.platform.contracts.hris.separation.v1
                .Hrm013WorkloadProofV1.Header("Authorization", "forbidden"));
        assertThatThrownBy(() -> Hrm013ApprovalClient.requireFiveHeaders(
                headers, "dwp-people-server", java.util.UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void taskDispatchRejectsDnsOrSpiffeMismatchBeforeReceiptParsing() {
        var fence = Hrm013RuntimeTestSupport.fence(Hrm013TaskDispatchV1.TIME, 51L);
        var client = new Hrm013TaskDispatchClient(
                profile -> URI.create("https://time.internal" + profile.path()),
                request -> new Hrm013TaskDispatchClient.Exchange(
                        202, new byte[] {1}, "proof", "TLSv1.3",
                        "spiffe://dwp/service/dwp-time-server", "wrong.internal",
                        false, Instant.now()),
                (profile, stored, receipt, exchange) -> true);

        assertThatThrownBy(() -> client.send(
                fence, Hrm013RuntimeTestSupport.headers(fence.correlationId())))
                .isInstanceOf(Hrm013TaskDispatchClient.DeliveryFailure.class);
    }

    @Test
    void approvalFiveHundredsRetryToTheBoundAndOnlyThenBecomeResultUnknown() {
        WaiverDeliveryClaim ninth = waiverClaim(9);
        WaiverDeliveryClaim tenth = new WaiverDeliveryClaim(
                ninth.fence(), UUID.randomUUID(), "worker", 10,
                Hrm013RuntimeTestSupport.NOW.plusSeconds(30));
        Hrm013ApprovalClient client = new Hrm013ApprovalClient(
                URI.create("https://approval.internal/internal/hris-approval/v1/"
                        + "separation-task-waiver-requests"),
                request -> new Hrm013ApprovalClient.Exchange(
                        503, null, null, "TLSv1.3",
                        "spiffe://dwp/service/dwp-approval-server",
                        "approval.internal", false, Hrm013RuntimeTestSupport.NOW),
                (fence, exchange) -> false);

        Hrm013ApprovalClient.DeliveryFailure clientFailure = catchThrowableOfType(
                Hrm013ApprovalClient.DeliveryFailure.class,
                () -> client.send(ninth,
                        Hrm013RuntimeTestSupport.headers(
                                ninth.fence().correlationId())));
        assertThat(clientFailure.code()).isEqualTo("UPSTREAM_5XX");
        assertThat(clientFailure.retryable()).isTrue();
        assertThat(clientFailure.ambiguous()).isTrue();

        Hrm013PersistencePorts.WaiverRequestOutboxStore store =
                mock(Hrm013PersistencePorts.WaiverRequestOutboxStore.class);
        when(store.claimNext(any())).thenReturn(Optional.of(ninth))
                .thenReturn(Optional.of(tenth));
        when(store.retryOrTerminal(any())).thenAnswer(invocation -> {
            WaiverDeliveryFailure failure = invocation.getArgument(0);
            return failure.retryable()
                    ? WaiverDeliveryDisposition.RETRY
                    : WaiverDeliveryDisposition.RESULT_UNKNOWN;
        });
        var relay = new Hrm013WaiverRequestRelay(
                store, client,
                (claim, now) -> Hrm013RuntimeTestSupport.headers(
                        claim.fence().correlationId()), ignored -> true,
                attempt -> Duration.ofSeconds(1),
                Clock.fixed(Hrm013RuntimeTestSupport.NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));

        assertThat(relay.relayOne(51L, "worker"))
                .contains(WaiverDeliveryDisposition.RETRY);
        assertThat(relay.relayOne(51L, "worker"))
                .contains(WaiverDeliveryDisposition.RESULT_UNKNOWN);
        ArgumentCaptor<WaiverDeliveryFailure> failures =
                ArgumentCaptor.forClass(WaiverDeliveryFailure.class);
        verify(store, org.mockito.Mockito.times(2)).retryOrTerminal(failures.capture());
        assertThat(failures.getAllValues())
                .extracting(WaiverDeliveryFailure::retryable)
                .containsExactly(true, false);
        assertThat(failures.getAllValues())
                .allSatisfy(failure -> {
                    assertThat(failure.remoteCommitAmbiguous()).isTrue();
                    assertThat(failure.failureCode()).isEqualTo("UPSTREAM_5XX");
                });
    }

    private static WaiverDeliveryClaim waiverClaim(int attempt) {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        UUID correlation = UUID.randomUUID();
        WaiverRequestFence fence = new WaiverRequestFence(
                UUID.randomUUID(), 51L, 1L, 2L, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1L, UUID.randomUUID(),
                UUID.randomUUID(), "POLICY_EXCEPTION", 1L, 1L, body,
                Hrm013PersistenceModels.sha256(body), "a".repeat(64),
                "b".repeat(64), correlation, Hrm013RuntimeTestSupport.NOW,
                Hrm013RuntimeTestSupport.NOW);
        return new WaiverDeliveryClaim(
                fence, UUID.randomUUID(), "worker", attempt,
                Hrm013RuntimeTestSupport.NOW.plusSeconds(30));
    }
}
