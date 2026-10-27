package com.dwp.services.auth.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class Hrm013IdentityTaskDispatchControllerTest {
    @Test
    void missingIngressEvidenceIsDisclosureSafe401() {
        var service = mock(Hrm013IdentityTaskDispatchService.class);
        var response = new Hrm013IdentityTaskDispatchController(service)
                .admit(new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isEmpty();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void controlledDomainFailureUsesClosedSafeErrorBody() {
        var service = mock(Hrm013IdentityTaskDispatchService.class);
        when(service.admit(any())).thenThrow(Hrm013SafeErrorV1.invalidState());
        UUID correlation = UUID.randomUUID();
        var context = new Hrm013IdentityTaskDispatchIngressFilter.VerifiedTransportRequest(
                "{}".getBytes(), Hrm013WorkloadProofV1.headers(
                        Hrm013WorkloadProofV1.taskDispatch(
                                Hrm013WorkloadProofV1.TaskOwner.IDENTITY),
                        "a.b.c", correlation), "a.b.c", correlation,
                new Hrm013WorkloadProofV1.TransportIdentity(
                        Hrm013TaskDispatchV1.IDENTITY.callerWorkloadId(),
                        "A".repeat(43)));
        var request = new MockHttpServletRequest();
        request.setAttribute(Hrm013IdentityTaskDispatchIngressFilter.ATTRIBUTE, context);

        var response = new Hrm013IdentityTaskDispatchController(service).admit(request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(new String(response.getBody())).isEqualTo("{\"code\":\"INVALID_STATE\"}");
    }

    @Test
    void acceptedAdmissionReturnsStoredExactBytesAndOwnerReceiptProof() {
        var service = mock(Hrm013IdentityTaskDispatchService.class);
        UUID correlation = UUID.randomUUID();
        var receipt = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1(
                        UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64),
                        "b".repeat(64), Instant.parse("2026-10-27T03:00:00Z"),
                        "0".repeat(64), correlation));
        byte[] exact = Hrm013TaskDispatchV1.encode(receipt);
        when(service.admit(any())).thenReturn(
                new Hrm013IdentityTaskDispatchStore.Admission(
                        receipt, exact, "owner.receipt.proof", true));
        var context = new Hrm013IdentityTaskDispatchIngressFilter.VerifiedTransportRequest(
                "{}".getBytes(), Hrm013WorkloadProofV1.headers(
                Hrm013WorkloadProofV1.taskDispatch(
                        Hrm013WorkloadProofV1.TaskOwner.IDENTITY),
                "a.b.c", correlation), "a.b.c", correlation,
                new Hrm013WorkloadProofV1.TransportIdentity(
                        Hrm013TaskDispatchV1.IDENTITY.callerWorkloadId(),
                        "A".repeat(43)));
        var request = new MockHttpServletRequest();
        request.setAttribute(Hrm013IdentityTaskDispatchIngressFilter.ATTRIBUTE, context);

        var response = new Hrm013IdentityTaskDispatchController(service).admit(request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).containsExactly(exact);
        assertThat(response.getHeaders().getFirst(
                Hrm013IdentityTaskDispatchController.RECEIPT_PROOF_HEADER))
                .isEqualTo("owner.receipt.proof");
        assertThat(response.getHeaders().getFirst("X-DWP-Idempotent-Replay"))
                .isEqualTo("true");
        assertThat(response.getHeaders().getFirst("X-Correlation-ID"))
                .isEqualTo(correlation.toString());
    }

    @Test
    void exactReplayAfterProofExpiryKeepsBodyAndReturnsFreshOwnerProof() {
        var service = mock(Hrm013IdentityTaskDispatchService.class);
        UUID correlation = UUID.randomUUID();
        Instant admittedAt = Instant.parse("2026-10-27T03:00:00Z");
        var receipt = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1(
                        UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64),
                        "b".repeat(64), admittedAt, "0".repeat(64), correlation));
        byte[] persistedBody = Hrm013TaskDispatchV1.encode(receipt);
        var initial = new Hrm013IdentityTaskDispatchStore.Admission(
                receipt, persistedBody, "owner.proof.issued-at-t0", false);
        var replayAfterThirtyOneSeconds =
                new Hrm013IdentityTaskDispatchStore.Admission(
                        receipt, persistedBody, "owner.proof.issued-at-t31", true);
        when(service.admit(any())).thenReturn(initial, replayAfterThirtyOneSeconds);
        var context = new Hrm013IdentityTaskDispatchIngressFilter
                .VerifiedTransportRequest(
                        "{}".getBytes(), Hrm013WorkloadProofV1.headers(
                                Hrm013WorkloadProofV1.taskDispatch(
                                        Hrm013WorkloadProofV1.TaskOwner.IDENTITY),
                                "a.b.c", correlation), "a.b.c", correlation,
                        new Hrm013WorkloadProofV1.TransportIdentity(
                                Hrm013TaskDispatchV1.IDENTITY.callerWorkloadId(),
                                "A".repeat(43)));
        var request = new MockHttpServletRequest();
        request.setAttribute(Hrm013IdentityTaskDispatchIngressFilter.ATTRIBUTE, context);
        var controller = new Hrm013IdentityTaskDispatchController(service);

        var first = controller.admit(request);
        var replay = controller.admit(request);

        assertThat(first.getBody()).containsExactly(persistedBody);
        assertThat(replay.getBody()).containsExactly(persistedBody);
        assertThat(Hrm013TaskDispatchV1.decodeAdmissionReceipt(first.getBody()))
                .isEqualTo(Hrm013TaskDispatchV1.decodeAdmissionReceipt(replay.getBody()));
        assertThat(replay.getHeaders().getFirst("X-DWP-Idempotent-Replay"))
                .isEqualTo("true");
        assertThat(replay.getHeaders().getFirst(
                Hrm013IdentityTaskDispatchController.RECEIPT_PROOF_HEADER))
                .isNotEqualTo(first.getHeaders().getFirst(
                        Hrm013IdentityTaskDispatchController.RECEIPT_PROOF_HEADER));
        verify(service, times(2)).admit(any());
    }
}
