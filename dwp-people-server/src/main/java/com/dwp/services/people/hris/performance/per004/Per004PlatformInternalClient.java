package com.dwp.services.people.hris.performance.per004;

import com.dwp.core.http.OutboundHttpHeaders;
import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationProofIssuerV1;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.*;

/** Exact signed People-to-Platform transport. Deliberately not a production bean. */
final class Per004PlatformInternalClient implements
        Per004WorkbenchController.PlatformOwnerProjectionPort,
        Per004PreparationRecovery.AuthoritativeCreateLookupPort {
    private static final String PROJECTION_PATH =
            "/internal/hris-reference-configuration/v1/owner-projections:query";
    private static final String CREATE_LOOKUP_PATH =
            "/internal/hris-reference-configuration/v1/authoritative-create-lookups:query";
    private static final int MAX_REQUEST_BYTES = 65536;
    private static final int MAX_PROJECTION_BYTES = 262144;
    private static final int MAX_LOOKUP_BYTES = 8192;
    private static final Duration DEADLINE = Duration.ofSeconds(2);
    private static final Set<String> STATES = Set.of("DRAFT", "VALIDATED", "SIMULATED",
            "PENDING_APPROVAL", "APPROVED", "SCHEDULED", "PUBLISHED", "REJECTED", "RETIRED",
            "FAILED_ACTIVATION", "NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE");

    private final ObjectMapper mapper;
    private final HrisReferenceConfigurationProofIssuerV1.SigningKeyProvider keys;
    private final URI projectionEndpoint;
    private final URI lookupEndpoint;
    private final HttpClient client;
    private final Clock clock;

    Per004PlatformInternalClient(ObjectMapper mapper,
            HrisReferenceConfigurationProofIssuerV1.SigningKeyProvider keys,
            URI base, Clock clock) {
        this(mapper, keys, base, HttpClient.newBuilder().connectTimeout(DEADLINE)
                .followRedirects(HttpClient.Redirect.NEVER).build(), clock);
    }

    Per004PlatformInternalClient(ObjectMapper mapper,
            HrisReferenceConfigurationProofIssuerV1.SigningKeyProvider keys,
            URI base, HttpClient client, Clock clock) {
        if (!PROJECTION_PATH.equals(Endpoint.PROJECTION.path(null))
                || !CREATE_LOOKUP_PATH.equals(Endpoint.CREATE_LOOKUP.path(null))) {
            throw unavailable();
        }
        this.projectionEndpoint = endpoint(base, Endpoint.PROJECTION);
        this.lookupEndpoint = endpoint(base, Endpoint.CREATE_LOOKUP);
        if (client == null || client.followRedirects() != HttpClient.Redirect.NEVER
                || mapper == null || clock == null) throw unavailable();
        this.mapper = mapper.copy().findAndRegisterModules();
        this.keys = keys;
        this.client = client;
        this.clock = clock;
    }

    @Override public OwnerProjectionResponse query(long tenantId, OwnerProjectionRequest request) {
        requireProjectionRequest(tenantId, request);
        OwnerProjectionResponse response = post(Endpoint.PROJECTION, projectionEndpoint, tenantId,
                request, OwnerProjectionResponse.class, MAX_PROJECTION_BYTES);
        if (response == null || response.asOf() == null || !response.asOf().isEqual(request.asOf())
                || !"CURRENT".equals(response.freshness()) || response.items() == null
                || response.items().size() > request.limit()
                || response.nextCursor() != null && !response.nextCursor().matches("^[A-Za-z0-9_-]{16,512}$"))
            throw unavailable();
        Set<UUID> seen = new HashSet<>();
        for (OwnerProjection row : response.items()) {
            if (row == null || row.configurationId() == null || !seen.add(row.configurationId())
                    || request.targets().stream().noneMatch(target ->
                        target.configurationId().equals(row.configurationId())
                            && target.scopeDigest().equals(row.scopeDigest()))
                    || !request.schemaKeys().contains(row.schemaKey()) || row.schemaVersion() == null
                    || row.schemaVersion() != 1 || row.version() == null || row.version() < 1
                    || row.aggregateVersion() == null || row.aggregateVersion() < 0
                    || !ownerReference(row.payloadReference()) || !digest(row.payloadDigest())
                    || !STATES.contains(row.lifecycleState())) throw unavailable();
        }
        return response;
    }

    @Override public CreateLookupResponse query(CreateLookupRequest request) {
        CreateIdentity id = request == null ? null : request.identity();
        if (id == null || id.tenantId() < 1 || id.configurationId() == null
                || id.createSubjectPrincipalPublicId() == null || id.ownerPreparationReceiptId() == null
                || !"configuration.version.create".equals(id.originatingAction())
                || id.createIdempotencyKey() == null || id.createIdempotencyKey().length() < 16
                || id.createIdempotencyKey().length() > 128
                || id.createIdempotencyKey().indexOf('\r') >= 0 || id.createIdempotencyKey().indexOf('\n') >= 0
                || !digest(id.createRequestDigest())) throw unavailable();
        CreateLookupResponse response = post(Endpoint.CREATE_LOOKUP, lookupEndpoint, id.tenantId(),
                request, CreateLookupResponse.class, MAX_LOOKUP_BYTES);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (response == null || !id.equals(response.identity()) || response.state() == null
                || response.evaluatedAt() == null || response.evaluatedAt().isAfter(now.plusSeconds(5))
                || response.evaluatedAt().isBefore(now.minusSeconds(30))
                || response.evidenceDigest() != null && !digest(response.evidenceDigest())) throw unavailable();
        switch (response.state()) {
            case BOUND -> {
                if (response.version() == null || response.version() < 1
                        || response.platformCreateReceiptId() == null
                        || response.durableAdmissionFenceId() != null
                        || response.signedBindingEvidence() != null) throw unavailable();
            }
            case ABSENT_FINAL -> {
                if (response.version() != null || response.platformCreateReceiptId() != null
                        || response.durableAdmissionFenceId() == null
                        || !digest(response.evidenceDigest())
                        || response.signedBindingEvidence() == null
                        || response.signedBindingEvidence().isBlank()) throw unavailable();
            }
            case UNKNOWN -> {
                if (response.version() != null || response.platformCreateReceiptId() != null
                        || response.durableAdmissionFenceId() != null
                        || response.signedBindingEvidence() != null) throw unavailable();
            }
        }
        return response;
    }

    private <T> T post(Endpoint endpoint, URI target, long tenantId, Object payload,
            Class<T> responseType, int maxResponseBytes) {
        if (keys == null) throw unavailable();
        try {
            byte[] body = mapper.writeValueAsBytes(payload);
            if (body.length == 0 || body.length > MAX_REQUEST_BYTES) throw unavailable();
            String proof = HrisReferenceConfigurationProofIssuerV1.issue(mapper, clock, endpoint,
                    endpoint.path(null), tenantId, body, keys.current(endpoint));
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target).timeout(DEADLINE)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header(SERVICE_IDENTITY_HEADER, endpoint.callerWorkloadId())
                    .header(WORKLOAD_PROOF_HEADER, proof);
            OutboundHttpHeaders.propagateObservability(requestBuilder);
            HttpRequest request = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var pending = client.sendAsync(request, ignored -> new BoundedBody(maxResponseBytes));
            HttpResponse<byte[]> wire;
            try { wire = pending.get(2, TimeUnit.SECONDS); }
            catch (Exception failure) { pending.cancel(true); throw failure; }
            if (wire.statusCode() != 200 || wire.headers().allValues("Content-Type").size() != 1
                    || !wire.headers().firstValue("Content-Type").orElse("")
                        .matches("(?i)application/json(?:;\\s*charset=utf-8)?")
                    || wire.body() == null || wire.body().length > maxResponseBytes) throw unavailable();
            return mapper.readerFor(responseType)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(wire.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (Exception failure) { throw unavailable(); }
    }

    private void requireProjectionRequest(long tenantId, OwnerProjectionRequest request) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (tenantId < 1 || request == null || request.asOf() == null
                || request.asOf().isAfter(now) || request.asOf().isBefore(now.minusSeconds(30))
                || request.targets() == null || request.targets().isEmpty() || request.targets().size() > 100
                || request.limit() == null || request.limit() < 1 || request.limit() > 100
                || request.schemaKeys() == null || request.schemaKeys().isEmpty()
                || !SCHEMAS.containsAll(request.schemaKeys()) || request.filter() == null
                || request.filter().lifecycleStates() == null || !request.filter().lifecycleStates().isEmpty()
                || request.filter().minimumVersion() != null
                || !List.of("configurationId:ASC").equals(request.sort())
                || request.cursor() != null && !request.cursor().matches("^[A-Za-z0-9_-]{16,512}$"))
            throw unavailable();
        Set<UUID> seen = new HashSet<>();
        for (ProjectionTarget target : request.targets()) {
            if (target == null || target.configurationId() == null || !seen.add(target.configurationId())
                    || !digest(target.scopeDigest())) throw unavailable();
        }
    }

    static URI endpoint(URI base, Endpoint endpoint) {
        if (base == null || endpoint == null || endpoint != Endpoint.PROJECTION
                && endpoint != Endpoint.CREATE_LOOKUP || !"https".equals(base.getScheme())
                || base.getHost() == null || base.getUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || !(base.getRawPath().isEmpty()
                    || "/".equals(base.getRawPath()))) throw unavailable();
        URI exact = base.resolve(endpoint.path(null));
        if (!endpoint.path(null).equals(exact.getRawPath()) || exact.getRawQuery() != null
                || exact.getRawFragment() != null || exact.getUserInfo() != null) throw unavailable();
        return exact;
    }

    private static boolean digest(String value) { return value != null && value.matches("^[0-9a-f]{64}$"); }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("PER004_PLATFORM_INTERNAL_UNAVAILABLE");
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(unavailable()); return;
                }
                byte[] value = new byte[buffer.remaining()]; buffer.get(value); bytes.writeBytes(value);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(unavailable()); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
