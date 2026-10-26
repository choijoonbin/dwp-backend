package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exact APR-002 TLS/proof client. Redirects and oversized/ambiguous replies fail closed. */
public final class Hrm013ApprovalClient {
    private final URI endpoint;
    private final Transport transport;
    private final ResponseProofVerifier responseProofs;

    public Hrm013ApprovalClient(
            URI endpoint, Transport transport, ResponseProofVerifier responseProofs) {
        this.endpoint = endpoint(endpoint);
        this.transport = Objects.requireNonNull(transport);
        this.responseProofs = Objects.requireNonNull(responseProofs);
    }

    public Delivery send(
            Hrm013PersistenceModels.WaiverDeliveryClaim claim,
            List<Hrm013WorkloadProofV1.Header> freshHeaders) {
        Objects.requireNonNull(claim);
        requireFiveHeaders(freshHeaders, "dwp-people-server",
                claim.fence().correlationId());
        Exchange exchange;
        try {
            exchange = transport.exchange(new Request(
                    endpoint, claim.fence().exactRequestBody(), freshHeaders,
                    "spiffe://dwp/service/dwp-approval-server", endpoint.getHost(),
                    Hrm013SeparationBridgeV1.MAXIMUM_RESPONSE_BYTES));
        } catch (Exception failure) {
            throw new DeliveryFailure("TRANSPORT_UNAVAILABLE", true, true, failure);
        }
        requirePeer(exchange, endpoint.getHost());
        if (exchange.status() != 202) {
            if (exchange.status() >= 500 && exchange.status() <= 599) {
                throw new DeliveryFailure("UPSTREAM_5XX", true, true, null);
            }
            throw new DeliveryFailure("UPSTREAM_REJECTED", false, false, null);
        }
        if (exchange.exactBody() == null || exchange.exactBody().length == 0
                || exchange.exactBody().length
                > Hrm013SeparationBridgeV1.MAXIMUM_RESPONSE_BYTES) {
            throw new DeliveryFailure("RECEIPT_BODY_INVALID", true, true, null);
        }
        var receipt = Hrm013SeparationBridgeV1.decodeWaiverReceipt(exchange.exactBody());
        var fence = claim.fence();
        if (!receipt.approvalRequestId().equals(fence.approvalRequestId())
                || receipt.requestRevision() != fence.requestRevision()
                || !receipt.requestDigest().equals(fence.commandDigest())
                || !receipt.correlationId().equals(fence.correlationId())) {
            throw new DeliveryFailure("RECEIPT_BINDING_INVALID", true, true, null);
        }
        if (!responseProofs.verify(fence, exchange)) {
            throw new DeliveryFailure("RECEIPT_PROOF_INVALID", true, true, null);
        }
        return new Delivery(exchange.exactBody(),
                Hrm013PersistenceModels.sha256(exchange.exactBody()), receipt.acceptedAt());
    }

    private static void requirePeer(Exchange value, String expectedDns) {
        if (value == null || value.redirected() || !"TLSv1.3".equals(value.tlsProtocol())
                || !"spiffe://dwp/service/dwp-approval-server".equals(value.peerSpiffeId())
                || !expectedDns.equals(value.peerDnsName())
                || value.receivedAt() == null) {
            throw new DeliveryFailure("TLS_PEER_INVALID", false, false, null);
        }
    }

    static void requireFiveHeaders(
            List<Hrm013WorkloadProofV1.Header> headers,
            String expectedServiceIdentity, UUID expectedCorrelationId) {
        java.util.Set<String> expected = java.util.Set.of(
                "content-type", "accept", "x-dwp-service-identity",
                "x-dwp-workload-proof", "x-correlation-id");
        if (headers == null || headers.size() != 5
                || headers.stream().map(value -> value.name().toLowerCase())
                .collect(java.util.stream.Collectors.toSet()).size() != 5
                || !headers.stream().map(value -> value.name().toLowerCase())
                .collect(java.util.stream.Collectors.toSet()).equals(expected)
                || headers.stream().anyMatch(value ->
                "authorization".equalsIgnoreCase(value.name())
                        || "cookie".equalsIgnoreCase(value.name()))) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        boolean json = headers.stream().filter(value ->
                "content-type".equalsIgnoreCase(value.name())
                        || "accept".equalsIgnoreCase(value.name()))
                .allMatch(value -> "application/json".equals(value.value()));
        boolean identity = headers.stream().anyMatch(value ->
                "x-dwp-service-identity".equalsIgnoreCase(value.name())
                        && expectedServiceIdentity.equals(value.value()));
        boolean correlation = headers.stream().anyMatch(value ->
                "x-correlation-id".equalsIgnoreCase(value.name())
                        && expectedCorrelationId.toString().equals(value.value()));
        boolean proof = headers.stream().anyMatch(value ->
                "x-dwp-workload-proof".equalsIgnoreCase(value.name())
                        && !value.value().isBlank()
                        && value.value().length() <= Hrm013WorkloadProofV1.MAXIMUM_JWS_BYTES);
        if (!json || !identity || !correlation || !proof) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static URI endpoint(URI value) {
        if (value == null || !"https".equalsIgnoreCase(value.getScheme())
                || value.getHost() == null || value.getQuery() != null
                || value.getFragment() != null || value.getUserInfo() != null
                || !Hrm013SeparationBridgeV1.APR_002.pathTemplate().equals(value.getPath())) {
            throw new IllegalArgumentException("exact Approval endpoint is required");
        }
        return value;
    }

    @FunctionalInterface public interface Transport {
        Exchange exchange(Request request) throws Exception;
    }
    @FunctionalInterface public interface ResponseProofVerifier {
        boolean verify(Hrm013PersistenceModels.WaiverRequestFence fence, Exchange exchange);
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
    public record Delivery(byte[] exactBody, String responseBodySha256, Instant acceptedAt) {
        public Delivery { exactBody = exactBody.clone(); }
        @Override public byte[] exactBody() { return exactBody.clone(); }
    }
    public static final class DeliveryFailure extends RuntimeException {
        private final String code; private final boolean retryable; private final boolean ambiguous;
        public DeliveryFailure(String code, boolean retryable, boolean ambiguous, Throwable cause) {
            super("HRM-013 Approval delivery failed", cause);
            this.code = code; this.retryable = retryable; this.ambiguous = ambiguous;
        }
        public String code() { return code; }
        public boolean retryable() { return retryable; }
        public boolean ambiguous() { return ambiguous; }
    }
}
