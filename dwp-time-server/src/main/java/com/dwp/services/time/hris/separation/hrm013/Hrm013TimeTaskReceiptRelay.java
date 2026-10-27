package com.dwp.services.time.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers durable terminal receipts through the current HRM-008 authority. */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "dwp.time.hrm013-task-dispatch", name = "enabled",
        havingValue = "true")
public final class Hrm013TimeTaskReceiptRelay {
    private static final String PEOPLE_SPIFFE = "spiffe://dwp/service/dwp-people-server";
    private final Hrm013TimeTaskDispatchStore store;
    private final AuthHrm013ServiceAuthorityClient authority;
    private final Hrm013TimeWorkloadCredentialProvider credentials;
    private final ObjectMapper mapper;
    private final URI peopleOrigin;
    private final Transport transport;
    private final TenantWorkSource tenants;
    private final String workerId;

    @FunctionalInterface
    public interface TenantWorkSource {
        List<Long> enabledTenantIds();
    }

    public Hrm013TimeTaskReceiptRelay(
            Hrm013TimeTaskDispatchStore store,
            AuthHrm013ServiceAuthorityClient authority,
            Hrm013TimeWorkloadCredentialProvider credentials, ObjectMapper mapper,
            ObjectProvider<Transport> transports,
            ObjectProvider<TenantWorkSource> tenantSources,
            @Value("${dwp.time.hrm013-task-dispatch.people-origin:}") String origin,
            @Value("${dwp.time.hrm013-task-dispatch.relay-worker-id:time-hrm013}")
                    String workerId) {
        this(store, authority, credentials, mapper, origin(origin),
                transports.getIfAvailable(JavaHttpTransport::new),
                onlyTenantSource(tenantSources), workerId);
    }

    Hrm013TimeTaskReceiptRelay(
            Hrm013TimeTaskDispatchStore store,
            AuthHrm013ServiceAuthorityClient authority,
            Hrm013TimeWorkloadCredentialProvider credentials, ObjectMapper mapper,
            URI peopleOrigin, Transport transport, TenantWorkSource tenants,
            String workerId) {
        this.store = Objects.requireNonNull(store);
        this.authority = Objects.requireNonNull(authority);
        this.credentials = Objects.requireNonNull(credentials);
        this.mapper = Objects.requireNonNull(mapper);
        this.peopleOrigin = Objects.requireNonNull(peopleOrigin);
        this.transport = Objects.requireNonNull(transport);
        this.tenants = Objects.requireNonNull(tenants);
        if (workerId == null || workerId.isBlank() || workerId.length() > 120) {
            throw new IllegalArgumentException("bounded relay worker id required");
        }
        this.workerId = workerId;
    }

    @Scheduled(fixedDelayString =
            "${dwp.time.hrm013-task-dispatch.relay-delay-ms:1000}")
    public void relayScheduled() {
        List<Long> enabled = List.copyOf(tenants.enabledTenantIds());
        if (enabled.size() > 100 || enabled.stream().anyMatch(
                value -> value == null || value < 1)
                || enabled.stream().distinct().count() != enabled.size()) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        enabled.forEach(tenantId -> relayBatch(tenantId, 25));
    }

    public int relayBatch(long tenantId, int limit) {
        List<Hrm013TimeTaskDispatchStore.CallbackRecord> records = store.claimCallbacks(
                tenantId, limit,
                new Hrm013TimeTaskDispatchStore.DurationLease(workerId, 30));
        for (var record : records) {
            try {
                deliver(record);
                store.markDelivered(record.tenantId(), record.outboxId(), workerId,
                        record.attemptCount());
            } catch (RuntimeException failure) {
                store.markRetry(record.tenantId(), record.outboxId(), workerId,
                        record.attemptCount(), "HRM008_DELIVERY_UNAVAILABLE",
                        boundedFullJitterSeconds(
                                record.callbackEventId(), record.attemptCount()));
            }
        }
        return records.size();
    }

    void deliver(Hrm013TimeTaskDispatchStore.CallbackRecord record) {
        var originalMode = Hrm013WorkloadProofV1.GrantUseMode.valueOf(
                record.grantUseMode());
        var admittedDrain = drain(record.drainClaimsJson());
        var callback = Hrm013SeparationBridgeV1.decodeTaskReceipt(
                record.exactCallbackBody());
        requireStoredCallback(record, callback);
        var terminalDrain = terminalDrain(record, admittedDrain, callback);
        var authorization = authority.authorizeCallback(
                record.tenantId(), originalMode, terminalDrain);
        var current = authorization.authority();
        var bridgeProfile = Hrm013SeparationBridgeV1.HRM_008_TIME;
        Hrm013SeparationBridgeV1.requireBodyBinding(bridgeProfile, callback);
        byte[] body = record.exactCallbackBody();
        var proofProfile = Hrm013WorkloadProofV1.hrm008(
                Hrm013WorkloadProofV1.TaskOwner.TIME,
                record.casePublicId(), record.taskPublicId());
        Map<String, Object> claims = Map.of(
                "callbackEventId", callback.callbackEventId(),
                "casePublicId", callback.casePublicId(),
                "taskPublicId", callback.taskPublicId(),
                "ownerRevision", callback.ownerRevision(),
                "originatingPayloadSha256", callback.originatingPayloadSha256());
        Instant authorityExpiry = current.drainAdmission() == null
                ? current.lease().expiresAt()
                : current.drainAdmission().drainAuthorizationExpiresAt();
        String proof = credentials.businessProof(
                current.lease(), proofProfile, callback.correlationId(), body, claims,
                authorization.mode(), authorization.drain(), authorityExpiry);
        String exactPath = bridgeProfile.path(
                record.casePublicId(), record.taskPublicId());
        URI endpoint = peopleOrigin.resolve(exactPath);
        var rechecked = authority.recheckCallback(current,
                authorization.mode(), authorization.drain());
        Exchange exchange;
        try {
            exchange = transport.exchange(new Request(
                    endpoint, body, Hrm013WorkloadProofV1.headers(
                            proofProfile, proof, callback.correlationId()),
                    rechecked.lease(), Hrm013SeparationBridgeV1.MAXIMUM_RESPONSE_BYTES),
                    credentials);
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
        requireExchange(exchange, endpoint, callback.correlationId());
        var receipt = Hrm013SeparationBridgeV1.decodeCallbackReceipt(exchange.exactBody());
        requireAcceptedReceipt(record.tenantId(), exactPath, body, callback, receipt);
    }

    static void requireStoredCallback(
            Hrm013TimeTaskDispatchStore.CallbackRecord record,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback) {
        if (!record.callbackEventId().equals(callback.callbackEventId())
                || !record.casePublicId().equals(callback.casePublicId())
                || !record.taskPublicId().equals(callback.taskPublicId())
                || !Hrm013WorkloadProofV1.TaskOwner.TIME.taskType()
                        .equals(callback.taskType())
                || !record.status().equals(callback.status())
                || record.ownerRevision() != callback.ownerRevision()
                || !record.externalReceiptPublicId().equals(
                        callback.externalReceiptPublicId())
                || !record.originatingPayloadSha256().equals(
                        callback.originatingPayloadSha256())
                || !record.occurredAt().equals(callback.occurredAt())
                || !record.correlationId().equals(callback.correlationId())
                || !record.callbackPayloadSha256().equals(callback.payloadSha256())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    static long boundedFullJitterSeconds(UUID callbackEventId, int attemptCount) {
        Objects.requireNonNull(callbackEventId);
        if (attemptCount < 1 || attemptCount > 10) {
            throw new IllegalArgumentException("bounded callback attempt required");
        }
        long cap = Math.min(300L, 1L << Math.min(attemptCount, 9));
        long mixed = callbackEventId.getMostSignificantBits()
                ^ Long.rotateLeft(callbackEventId.getLeastSignificantBits(), 17)
                ^ (0x9E3779B97F4A7C15L * attemptCount);
        return Math.floorMod(mixed, cap + 1);
    }

    static void requireAcceptedReceipt(long tenantId, String exactPath, byte[] exactBody,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback,
            Hrm013SeparationBridgeV1.SeparationCallbackReceiptV1 receipt) {
        String commandDigest = Hrm013SeparationBridgeV1.commandDigest(
                Hrm013SeparationBridgeV1.HRM_008_TIME, exactPath, tenantId,
                Hrm013SeparationBridgeV1.requestBodySha256(exactBody));
        if (!receipt.callbackEventId().equals(callback.callbackEventId())
                || !"TASK_RECEIPT".equals(receipt.callbackType())
                || !receipt.casePublicId().equals(callback.casePublicId())
                || !Objects.equals(receipt.taskPublicId(), callback.taskPublicId())
                || receipt.ownerRevision() != callback.ownerRevision()
                || !"ACCEPTED".equals(receipt.outcome())
                || !receipt.correlationId().equals(callback.correlationId())
                || !commandDigest.equals(receipt.commandDigest())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private Hrm013WorkloadProofV1.DrainClaims drain(String json) {
        if (json == null) return null;
        try {
            JsonNode value = mapper.readTree(json);
            return new Hrm013WorkloadProofV1.DrainClaims(
                    UUID.fromString(value.path("drainRootWorkItemId").asText()),
                    Instant.parse(value.path("drainRootCreatedAt").asText()),
                    value.path("drainRootPayloadSha256").asText(),
                    Hrm013WorkloadProofV1.DrainContinuationKind.valueOf(
                            value.path("drainContinuationKind").asText()),
                    UUID.fromString(value.path("drainContinuationEventId").asText()),
                    value.path("drainContinuationRevision").longValue(),
                    value.path("drainContinuationPayloadSha256").asText());
        } catch (RuntimeException | java.io.IOException failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    static Hrm013WorkloadProofV1.DrainClaims terminalDrain(
            Hrm013TimeTaskDispatchStore.CallbackRecord record,
            Hrm013WorkloadProofV1.DrainClaims admitted,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback) {
        Hrm013WorkloadProofV1.GrantUseMode originalMode =
                Hrm013WorkloadProofV1.GrantUseMode.valueOf(record.grantUseMode());
        UUID rootId;
        Instant rootCreatedAt;
        String rootPayload;
        if (originalMode == Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK) {
            if (admitted != null) throw Hrm013SafeErrorV1.authorityDenied();
            rootId = record.dispatchEventId();
            rootCreatedAt = record.terminalParentCreatedAt();
            rootPayload = record.originatingPayloadSha256();
        } else {
            if (admitted == null
                    || admitted.drainContinuationKind()
                    != Hrm013WorkloadProofV1.DrainContinuationKind.ROOT_DELIVERY
                    || !admitted.drainRootWorkItemId().equals(record.dispatchEventId())
                    || !admitted.drainContinuationEventId().equals(record.dispatchEventId())
                    || admitted.drainContinuationRevision() != 0
                    || !admitted.drainRootCreatedAt().equals(
                            record.terminalParentCreatedAt())
                    || !admitted.drainRootPayloadSha256().equals(
                            record.originatingPayloadSha256())
                    || !admitted.drainContinuationPayloadSha256().equals(
                            record.originatingPayloadSha256())) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            rootId = admitted.drainRootWorkItemId();
            rootCreatedAt = admitted.drainRootCreatedAt();
            rootPayload = admitted.drainRootPayloadSha256();
        }
        UUID expectedEvent = Hrm013WorkloadProofV1.terminalChildEventId(
                record.tenantId(), rootId, callback.ownerRevision(),
                Hrm013SeparationBridgeV1.HRM_008_TIME.bindingId());
        if (!expectedEvent.equals(callback.callbackEventId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return new Hrm013WorkloadProofV1.DrainClaims(
                rootId, rootCreatedAt, rootPayload,
                Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD,
                callback.callbackEventId(), callback.ownerRevision(), callback.payloadSha256());
    }

    private static void requireExchange(Exchange value, URI endpoint, UUID correlation) {
        if (value == null || value.redirected() || value.status() != 200
                || !"TLSv1.3".equals(value.tlsProtocol())
                || !PEOPLE_SPIFFE.equals(value.peerSpiffeId())
                || !endpoint.getHost().equals(value.peerDnsName())
                || !correlation.equals(value.correlationId())
                || value.exactBody() == null || value.exactBody().length == 0
                || value.exactBody().length > Hrm013SeparationBridgeV1.MAXIMUM_RESPONSE_BYTES) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
    }

    private static URI origin(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("exact People HTTPS origin required");
        }
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("exact People HTTPS origin required");
        }
        return URI.create("https://" + uri.getAuthority());
    }

    private static TenantWorkSource onlyTenantSource(
            ObjectProvider<TenantWorkSource> sources) {
        List<TenantWorkSource> candidates = sources.orderedStream().toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "exactly one HRM-013 Time tenant work source is required");
        }
        return candidates.getFirst();
    }

    public interface Transport {
        Exchange exchange(Request request,
                Hrm013TimeWorkloadCredentialProvider credentials) throws Exception;
    }

    public record Request(
            URI endpoint, byte[] exactBody, List<Hrm013WorkloadProofV1.Header> headers,
            Hrm013TimeWorkloadCredentialProvider.Lease lease, int maximumResponseBytes) {
        public Request { exactBody = exactBody.clone(); headers = List.copyOf(headers); }
        @Override public byte[] exactBody() { return exactBody.clone(); }
    }

    public record Exchange(
            int status, byte[] exactBody, String tlsProtocol, String peerSpiffeId,
            String peerDnsName, boolean redirected, UUID correlationId) {
        public Exchange { exactBody = exactBody == null ? null : exactBody.clone(); }
        @Override public byte[] exactBody() {
            return exactBody == null ? null : exactBody.clone();
        }
    }

    private static final class JavaHttpTransport implements Transport {
        @Override public Exchange exchange(Request value,
                Hrm013TimeWorkloadCredentialProvider credentials) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(value.endpoint())
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(value.exactBody()));
            value.headers().forEach(header -> builder.header(header.name(), header.value()));
            HttpResponse<java.io.InputStream> response = credentials.client(value.lease()).send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] exactBody;
            try (var input = response.body()) {
                exactBody = input.readNBytes(value.maximumResponseBytes() + 1);
            }
            if (exactBody.length > value.maximumResponseBytes()) {
                throw new IllegalStateException("People response exceeds sealed bound");
            }
            var session = response.sslSession().orElseThrow();
            X509Certificate leaf = (X509Certificate) session.getPeerCertificates()[0];
            UUID correlation = UUID.fromString(
                    response.headers().firstValue("X-Correlation-ID").orElseThrow());
            return new Exchange(response.statusCode(), exactBody,
                    session.getProtocol(), onlySan(leaf, 6), onlySan(leaf, 2),
                    response.previousResponse().isPresent(), correlation);
        }

        private static String onlySan(X509Certificate certificate, int type) throws Exception {
            Collection<List<?>> values = certificate.getSubjectAlternativeNames();
            List<String> result = new ArrayList<>();
            if (values != null) for (List<?> item : values) {
                if (item != null && item.size() == 2 && Integer.valueOf(type).equals(item.get(0))
                        && item.get(1) instanceof String text) result.add(text);
            }
            if (result.size() != 1) throw new IllegalStateException("ambiguous peer SAN");
            return result.getFirst();
        }
    }
}
