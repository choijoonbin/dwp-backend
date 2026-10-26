package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Three-profile task dispatch client with strict peer and admission-receipt binding. */
public final class Hrm013TaskDispatchClient {
    private static final Map<String, String> SPIFFE = Map.of(
            "SVC-PEP-TIM-004", "spiffe://dwp/service/dwp-time-server",
            "SVC-PEP-PAY-002", "spiffe://dwp/service/dwp-payroll-server",
            "SVC-AUT-HRM-014", "spiffe://dwp/service/dwp-auth-server");
    private final EndpointRegistry endpoints;
    private final Transport transport;
    private final AdmissionProofVerifier receiptProofs;

    public Hrm013TaskDispatchClient(
            EndpointRegistry endpoints, Transport transport,
            AdmissionProofVerifier receiptProofs) {
        this.endpoints = Objects.requireNonNull(endpoints);
        this.transport = Objects.requireNonNull(transport);
        this.receiptProofs = Objects.requireNonNull(receiptProofs);
    }

    public Delivery send(
            Hrm013PersistenceModels.TaskDispatchFence fence,
            List<Hrm013WorkloadProofV1.Header> freshHeaders) {
        Hrm013ApprovalClient.requireFiveHeaders(
                freshHeaders, "dwp-people-server", fence.correlationId());
        Hrm013TaskDispatchV1.Profile profile = Hrm013TaskDispatchOutboxStore.profile(
                fence.eventType());
        URI endpoint = exactEndpoint(endpoints.endpoint(profile), profile);
        Exchange exchange;
        try {
            exchange = transport.exchange(new Request(
                    endpoint, fence.exactRequestBody(), freshHeaders,
                    SPIFFE.get(profile.bindingId()), endpoint.getHost(),
                    Hrm013TaskDispatchV1.MAXIMUM_RESPONSE_BYTES));
        } catch (Exception failure) {
            throw new DeliveryFailure("TRANSPORT_UNAVAILABLE", true, true, failure);
        }
        if (exchange == null || exchange.redirected()
                || !"TLSv1.3".equals(exchange.tlsProtocol())
                || !SPIFFE.get(profile.bindingId()).equals(exchange.peerSpiffeId())
                || !endpoint.getHost().equals(exchange.peerDnsName())
                || exchange.status() != 202 || exchange.exactBody() == null
                || exchange.exactBody().length == 0
                || exchange.exactBody().length > Hrm013TaskDispatchV1.MAXIMUM_RESPONSE_BYTES) {
            throw new DeliveryFailure("ADMISSION_RESPONSE_INVALID", true, true, null);
        }
        Hrm013TaskDispatchV1.DispatchBody request = Hrm013TaskDispatchV1.decode(
                profile, fence.tenantId(), fence.exactRequestBody());
        var receipt = Hrm013TaskDispatchV1.decodeAdmissionReceipt(exchange.exactBody());
        Hrm013TaskDispatchV1.requireAdmissionReceiptBinding(
                profile, fence.tenantId(), request, fence.exactRequestBody(), receipt);
        if (!receiptProofs.verify(profile, fence, receipt, exchange)) {
            throw new DeliveryFailure("ADMISSION_PROOF_INVALID", true, true, null);
        }
        return new Delivery(exchange.exactBody(),
                Hrm013PersistenceModels.sha256(exchange.exactBody()),
                receipt.receiptSha256(), receipt.admittedAt());
    }

    private static URI exactEndpoint(URI value, Hrm013TaskDispatchV1.Profile profile) {
        if (value == null || !"https".equalsIgnoreCase(value.getScheme())
                || value.getHost() == null || value.getUserInfo() != null
                || value.getQuery() != null || value.getFragment() != null
                || !profile.path().equals(value.getPath())) {
            throw new IllegalArgumentException("exact dispatch endpoint is required");
        }
        return value;
    }

    @FunctionalInterface public interface EndpointRegistry {
        URI endpoint(Hrm013TaskDispatchV1.Profile profile);
    }
    @FunctionalInterface public interface Transport {
        Exchange exchange(Request request) throws Exception;
    }
    @FunctionalInterface public interface AdmissionProofVerifier {
        boolean verify(Hrm013TaskDispatchV1.Profile profile,
                Hrm013PersistenceModels.TaskDispatchFence fence,
                Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1 receipt,
                Exchange exchange);
    }
    public record Request(
            URI endpoint, byte[] exactBody, List<Hrm013WorkloadProofV1.Header> headers,
            String expectedPeerSpiffeId, String expectedDnsName, int maximumResponseBytes) {
        public Request { exactBody = exactBody.clone(); headers = List.copyOf(headers); }
        @Override public byte[] exactBody() { return exactBody.clone(); }
    }
    public record Exchange(
            int status, byte[] exactBody, String responseProof, String tlsProtocol,
            String peerSpiffeId, String peerDnsName, boolean redirected, Instant receivedAt) {
        public Exchange { exactBody = exactBody == null ? null : exactBody.clone(); }
        @Override public byte[] exactBody() { return exactBody == null ? null : exactBody.clone(); }
    }
    public record Delivery(
            byte[] exactBody, String responseBodySha256,
            String receiptSha256, Instant admittedAt) {
        public Delivery { exactBody = exactBody.clone(); }
        @Override public byte[] exactBody() { return exactBody.clone(); }
    }
    public static final class DeliveryFailure extends RuntimeException {
        private final String code; private final boolean retryable; private final boolean ambiguous;
        public DeliveryFailure(String code, boolean retryable, boolean ambiguous, Throwable cause) {
            super("HRM-013 task dispatch failed", cause);
            this.code = code; this.retryable = retryable; this.ambiguous = ambiguous;
        }
        public String code() { return code; }
        public boolean retryable() { return retryable; }
        public boolean ambiguous() { return ambiguous; }
    }
}
