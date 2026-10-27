package com.dwp.services.auth.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLSession;
import org.springframework.scheduling.annotation.Scheduled;

/** One bounded, exact-byte HRM-008 identity callback attempt. */
public final class Hrm013IdentityTaskReceiptRelay {
    private static final Hrm013SeparationBridgeV1.BindingProfile PROFILE =
            Hrm013SeparationBridgeV1.HRM_008_IDENTITY;
    private static final String PEOPLE_SPIFFE =
            "spiffe://dwp/service/dwp-people-server";
    private final Hrm013IdentityTaskDispatchService service;
    private final EndpointRegistry endpoints;
    private final Transport transport;
    private final RetryJitter retryJitter;
    private final Hrm013IdentityTaskDispatchService.TenantWorkSource tenants;
    private final Clock clock;
    private long tenantCursor;

    public Hrm013IdentityTaskReceiptRelay(
            Hrm013IdentityTaskDispatchService service,
            EndpointRegistry endpoints, Transport transport,
            RetryJitter retryJitter,
            Hrm013IdentityTaskDispatchService.TenantWorkSource tenants,
            Clock clock) {
        this.service = Objects.requireNonNull(service);
        this.endpoints = Objects.requireNonNull(endpoints);
        this.transport = Objects.requireNonNull(transport);
        this.retryJitter = Objects.requireNonNull(retryJitter);
        this.tenants = Objects.requireNonNull(tenants);
        this.clock = Objects.requireNonNull(clock);
    }

    public Optional<Disposition> relayOne(long tenantId) {
        Hrm013IdentityTaskDispatchStore.ReceiptClaim claim = null;
        try {
            Optional<Hrm013IdentityTaskDispatchStore.ReceiptClaim> candidate =
                    service.claimReceipt(tenantId, clock.instant());
            if (candidate.isEmpty()) return Optional.empty();
            claim = candidate.orElseThrow();
            var body = Hrm013SeparationBridgeV1.decodeTaskReceipt(
                    claim.exactCallbackBody());
            requireClaimBinding(claim, body);
            var attempt = service.authorizeReceiptAttempt(claim, body);
            String path = PROFILE.path(body.casePublicId(), body.taskPublicId());
            List<Hrm013WorkloadProofV1.Header> headers = attempt.headers();
            requireExactHeaders(headers);
            URI endpoint = endpoint(endpoints.peopleBaseUri(claim.tenantId()), path);

            // Recheck both durable grant/fence and lease immediately before I/O.
            attempt = service.recheckReceiptAttempt(attempt);
            OutboundResponse response = transport.send(new OutboundRequest(
                    endpoint, path, claim.exactCallbackBody(), headers,
                    attempt.credential(),
                    Duration.ofSeconds(5), PEOPLE_SPIFFE));
            requireTrustedPeerAfterSend(response, endpoint, PEOPLE_SPIFFE);
            if (response.statusCode() != 200
                    || !response.headerValues("content-type")
                            .equals(List.of("application/json"))) {
                boolean retry = response.statusCode() >= 500
                        || Set.of(408, 425, 429).contains(response.statusCode());
                throw new DeliveryFailure("HTTP_" + response.statusCode(),
                        retry, retry);
            }
            if (response.exactBody().length == 0
                    || response.exactBody().length
                    > Hrm013SeparationBridgeV1.MAXIMUM_RESPONSE_BYTES) {
                throw new DeliveryFailure("RESPONSE_SIZE_INVALID", true, true);
            }
            var receipt = Hrm013SeparationBridgeV1.decodeCallbackReceipt(
                    response.exactBody());
            String commandDigest = Hrm013SeparationBridgeV1.commandDigest(
                    PROFILE, path, claim.tenantId(), claim.exactCallbackBody());
            if (!body.callbackEventId().equals(receipt.callbackEventId())
                    || !"TASK_RECEIPT".equals(receipt.callbackType())
                    || !body.casePublicId().equals(receipt.casePublicId())
                    || !body.taskPublicId().equals(receipt.taskPublicId())
                    || receipt.ownerRevision() != body.ownerRevision()
                    || !"ACCEPTED".equals(receipt.outcome())
                    || !body.correlationId().equals(receipt.correlationId())
                    || !commandDigest.equals(receipt.commandDigest())) {
                throw new DeliveryFailure(
                        "RECEIPT_BINDING_MISMATCH", true, true);
            }
            service.receiptDelivered(claim, clock.instant());
            return Optional.of(Disposition.DELIVERED);
        } catch (DeliveryFailure failure) {
            if (claim == null) return Optional.empty();
            return Optional.of(fail(claim, failure.code(), failure.retryable(),
                    failure.ambiguous()));
        } catch (Exception failure) {
            if (claim == null) return Optional.empty();
            return Optional.of(fail(claim, "RESULT_UNKNOWN", true, true));
        }
    }

    /** Bounded default-OFF scheduler; at most one receipt attempt per tenant. */
    public int relayScheduledBatch() {
        int attempted = 0;
        for (long tenantId : nextTenantPage()) {
            try {
                if (relayOne(tenantId).isPresent()) attempted++;
            } catch (RuntimeException ignored) {
                // Isolate one tenant's claim/storage failure from the remaining page.
            }
        }
        return attempted;
    }

    private synchronized List<Long> nextTenantPage() {
        List<Long> page = Hrm013IdentityTaskDispatchService.boundedTenants(
                tenants.pageAfter(tenantCursor, 100));
        if (page.isEmpty() && tenantCursor != 0L) {
            tenantCursor = 0L;
            page = Hrm013IdentityTaskDispatchService.boundedTenants(
                    tenants.pageAfter(0L, 100));
        }
        if (!page.isEmpty()) tenantCursor = page.getLast();
        return page;
    }

    @Scheduled(fixedDelayString =
            "${dwp.auth.hrm013-identity-task-dispatch.receipt-poll-delay-ms:5000}")
    void relayScheduled() {
        relayScheduledBatch();
    }

    private Disposition fail(Hrm013IdentityTaskDispatchStore.ReceiptClaim claim,
            String code, boolean retryable, boolean ambiguous) {
        boolean retry = claim.attemptCount() < 10;
        Instant now = clock.instant();
        service.receiptFailed(claim, now,
                !retry ? "RESULT_UNKNOWN"
                        : "RESULT_UNKNOWN".equals(code) ? "TRANSPORT_RESULT_UNKNOWN" : code,
                retry ? now.plus(retryJitter.delay(
                        claim.callbackEventId(), claim.attemptCount())) : null);
        return retry ? Disposition.RETRY
                : ambiguous ? Disposition.RESULT_UNKNOWN : Disposition.DEAD;
    }

    private static void requireClaimBinding(
            Hrm013IdentityTaskDispatchStore.ReceiptClaim claim,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 body) {
        Hrm013SeparationBridgeV1.requireBodyBinding(PROFILE, body);
        if (!claim.callbackEventId().equals(body.callbackEventId())
                || !claim.casePublicId().equals(body.casePublicId())
                || !claim.taskPublicId().equals(body.taskPublicId())
                || !claim.correlationId().equals(body.correlationId())
                || !claim.callbackPayloadSha256().equals(body.payloadSha256())
                || !claim.callbackPayloadSha256().equals(
                        Hrm013SeparationBridgeV1.payloadSha256(body))) {
            throw new DeliveryFailure("IMMUTABLE_CALLBACK_MISMATCH", false, false);
        }
    }

    static URI endpoint(URI base, String exactPath) {
        if (base == null || !"https".equalsIgnoreCase(base.getScheme())
                || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null
                || base.getPort() == 0 || !PROFILE.matchesPath(exactPath)) {
            throw new DeliveryFailure("ENDPOINT_INVALID", false, false);
        }
        URI normalized = URI.create(base.toString().endsWith("/")
                ? base.toString() : base + "/");
        URI endpoint = normalized.resolve(exactPath.substring(1));
        if (!endpoint.getScheme().equalsIgnoreCase(base.getScheme())
                || !Objects.equals(endpoint.getHost(), base.getHost())
                || endpoint.getPort() != base.getPort()
                || !endpoint.getPath().equals(exactPath)
                || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new DeliveryFailure("ENDPOINT_INVALID", false, false);
        }
        return endpoint;
    }

    static void requireTrustedPeerAfterSend(
            OutboundResponse response, URI endpoint, String expectedSpiffe) {
        TlsEvidence tls = response.tlsEvidence();
        if (tls == null || !"TLSv1.3".equals(tls.protocol())
                || !tls.trustChainValidated() || !tls.revocationValidated()
                || !expectedSpiffe.equals(tls.singleSpiffeUri())
                || !endpoint.getHost().equalsIgnoreCase(tls.peerHost())) {
            throw new DeliveryFailure("PEER_IDENTITY_UNTRUSTED", true, true);
        }
    }

    static void requireTrustedPeerBeforeBody(
            Hrm013IdentityWorkloadCredentialProvider.VerifiedTlsEvidence tls,
            URI endpoint, String expectedSpiffe, String expectedCertificateSha256) {
        if (tls == null || !"TLSv1.3".equals(tls.protocol())
                || !tls.trustChainValidated() || !tls.revocationValidated()
                || !expectedSpiffe.equals(tls.singleSpiffeUri())
                || !endpoint.getHost().equalsIgnoreCase(tls.peerHost())
                || !expectedCertificateSha256.equals(
                        tls.clientCertificateSha256())) {
            throw new DeliveryFailure("PEER_IDENTITY_UNTRUSTED", false, false);
        }
    }

    private static void requireExactHeaders(
            List<Hrm013WorkloadProofV1.Header> headers) {
        if (headers == null || headers.size() != 5) {
            throw new DeliveryFailure("PROOF_ENVELOPE_INVALID", false, false);
        }
        Set<String> expected = Set.of("content-type", "accept",
                "x-dwp-service-identity", "x-dwp-workload-proof",
                "x-correlation-id");
        Set<String> actual = headers.stream()
                .map(header -> header.name().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!actual.equals(expected)) {
            throw new DeliveryFailure("PROOF_ENVELOPE_INVALID", false, false);
        }
    }

    public enum Disposition { DELIVERED, RETRY, DEAD, RESULT_UNKNOWN }

    @FunctionalInterface public interface EndpointRegistry {
        URI peopleBaseUri(long tenantId);
    }

    @FunctionalInterface public interface Transport {
        OutboundResponse send(OutboundRequest request) throws Exception;
    }

    @FunctionalInterface public interface RetryJitter {
        Duration delay(java.util.UUID callbackEventId, int attemptCount);
    }

    public record OutboundRequest(
            URI endpoint, String exactPath, byte[] exactBody,
            List<Hrm013WorkloadProofV1.Header> headers,
            Hrm013IdentityWorkloadCredentialProvider.AtomicGrantCredentialLease credential,
            Duration timeout, String expectedPeerSpiffe) {
        public OutboundRequest {
            Objects.requireNonNull(endpoint); Objects.requireNonNull(exactPath);
            exactBody = Objects.requireNonNull(exactBody).clone();
            headers = List.copyOf(headers);
            Objects.requireNonNull(credential); Objects.requireNonNull(timeout);
            Objects.requireNonNull(expectedPeerSpiffe);
            if (exactBody.length == 0
                    || exactBody.length > Hrm013SeparationBridgeV1.MAXIMUM_REQUEST_BYTES
                    || timeout.isZero() || timeout.isNegative()
                    || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
                throw new IllegalArgumentException("outbound request bounds");
            }
        }
        @Override public byte[] exactBody() { return exactBody.clone(); }
    }

    public record OutboundResponse(
            int statusCode, Map<String, List<String>> headers,
            byte[] exactBody, TlsEvidence tlsEvidence) {
        public OutboundResponse {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("statusCode");
            }
            headers = Map.copyOf(headers);
            exactBody = Objects.requireNonNull(exactBody).clone();
        }
        @Override public byte[] exactBody() { return exactBody.clone(); }
        List<String> headerValues(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream()).toList();
        }
    }

    public record TlsEvidence(
            String protocol, boolean trustChainValidated,
            boolean revocationValidated, String singleSpiffeUri,
            String peerHost) { }

    /** Same-channel transport that validates the handshake before publishing a body. */
    public static final class JavaHttpTransport implements Transport {
        @Override
        public OutboundResponse send(OutboundRequest request) throws Exception {
            long deadlineNanos = System.nanoTime() + request.timeout().toNanos();
            try (var channel = request.credential().tlsClientHandle().openVerified(
                    request.endpoint(), request.expectedPeerSpiffe(),
                    request.credential().clientCertificateSha256(),
                    request.timeout())) {
                var evidence = channel.evidence();
                requireTrustedPeerBeforeBody(evidence, request.endpoint(),
                        request.expectedPeerSpiffe(),
                        request.credential().clientCertificateSha256());
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (!channel.totalDeadlineBound() || remainingNanos <= 0L) {
                    throw new DeliveryFailure("TLS_CHANNEL_UNAVAILABLE", false, false);
                }
                var response = channel.post(request.headers(), request.exactBody(),
                        Hrm013SeparationBridgeV1.MAXIMUM_RESPONSE_BYTES,
                        Duration.ofNanos(remainingNanos));
                if (response.redirected()
                        || !request.endpoint().equals(response.responseUri())) {
                    throw new DeliveryFailure("REDIRECT_FORBIDDEN", false, true);
                }
                byte[] body = response.exactBody();
                if (body.length > Hrm013SeparationBridgeV1.MAXIMUM_RESPONSE_BYTES) {
                    throw new DeliveryFailure("RESPONSE_SIZE_INVALID", true, true);
                }
                return new OutboundResponse(response.statusCode(), response.headers(), body,
                        new TlsEvidence(evidence.protocol(),
                                evidence.trustChainValidated(),
                                evidence.revocationValidated(),
                                evidence.singleSpiffeUri(), evidence.peerHost()));
            } catch (DeliveryFailure failure) {
                throw failure;
            } catch (Exception failure) {
                throw new DeliveryFailure("TLS_CHANNEL_UNAVAILABLE", true, true);
            }
        }
    }

    public static final class DeliveryFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final boolean retryable;
        private final boolean ambiguous;
        public DeliveryFailure(String code, boolean retryable, boolean ambiguous) {
            super(Objects.requireNonNull(code), null, false, false);
            if (!code.matches("[A-Z0-9_]{3,40}")) {
                throw new IllegalArgumentException("delivery code");
            }
            this.code = code;
            this.retryable = retryable;
            this.ambiguous = ambiguous;
        }
        public String code() { return code; }
        public boolean retryable() { return retryable; }
        public boolean ambiguous() { return ambiguous; }
    }
}
