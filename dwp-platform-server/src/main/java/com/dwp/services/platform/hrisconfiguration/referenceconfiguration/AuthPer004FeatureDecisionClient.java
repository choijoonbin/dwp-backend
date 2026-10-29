package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.http.OutboundHttpHeaders;
import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationProofIssuerV1;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.*;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationProofIssuerV1.SigningKeyProvider;

/** Canonical Auth lookup only: no gateway token, redirect, TLS downgrade or query-grant dependency. */
@Component
class AuthPer004FeatureDecisionClient implements FeatureDecisionPort {
    private static final String FEATURE_PATH =
            "/internal/auth/v1/hris-performance-feature-decisions:query";
    private final ObjectMapper mapper;
    private final SigningKeyProvider keys;
    private final URI endpoint;
    private final HttpClient client;
    private final Clock clock;

    @Autowired AuthPer004FeatureDecisionClient(ObjectMapper mapper, ObjectProvider<SigningKeyProvider> keys,
            @Value("${dwp.hris.reference-configuration.auth.base-url:}") String baseUrl) {
        this(mapper, keys.getIfAvailable(), endpoint(baseUrl),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }
    AuthPer004FeatureDecisionClient(ObjectMapper mapper, SigningKeyProvider keys, URI endpoint, HttpClient client, Clock clock) {
        this.mapper = mapper; this.keys = keys; this.endpoint = endpoint; this.client = client; this.clock = clock;
        if (!FEATURE_PATH.equals(Endpoint.FEATURE.path(null))) throw unavailable();
        if (endpoint != null && (!"https".equals(endpoint.getScheme()) || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !Endpoint.FEATURE.path(null).equals(endpoint.getRawPath()))) throw unavailable();
        if (client.followRedirects() != HttpClient.Redirect.NEVER) throw unavailable();
    }

    @Override public FeatureDecision decide(FeatureRequest binding) {
        if (endpoint == null || keys == null || binding == null || binding.tenantId() < 1
                || binding.subjectPrincipalPublicId() == null || binding.configurationId() == null
                || !"PER".equals(binding.resolvedDomain()) || binding.scopeDigest() == null
                || !binding.scopeDigest().matches("^[a-f0-9]{64}$") || binding.commandAuthorizationRevision() < 1
                || binding.correlationId() == null || binding.exactSharedActionRouteContractKey() == null
                || !binding.exactSharedActionRouteContractKey().matches("^route\\.hcm\\.configuration\\.configuration-version-(create|validate|simulate|submit|approve|publish)\\.action$")) throw unavailable();
        try {
            byte[] body = mapper.writeValueAsBytes(binding);
            String proof = HrisReferenceConfigurationProofIssuerV1.issue(mapper, clock, Endpoint.FEATURE,
                    Endpoint.FEATURE.path(null), binding.tenantId(), body, keys.current(Endpoint.FEATURE));
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header(SERVICE_IDENTITY_HEADER, Endpoint.FEATURE.callerWorkloadId())
                    .header(WORKLOAD_PROOF_HEADER, proof);
            OutboundHttpHeaders.propagateObservability(requestBuilder);
            HttpRequest request = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var pending = client.sendAsync(request, ignored -> new BoundedBody());
            HttpResponse<byte[]> response;
            try { response = pending.get(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception failure) { pending.cancel(true); throw failure; }
            if (response.statusCode() != 200 || response.headers().allValues("Content-Type").size() != 1
                    || !response.headers().firstValue("Content-Type").orElse("").matches("(?i)application/json(?:;\\s*charset=utf-8)?")) throw unavailable();
            byte[] bytes = response.body();
            if (bytes == null || bytes.length > 65536) throw unavailable();
            FeatureDecision decision = mapper.readerFor(FeatureDecision.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(bytes);
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (decision == null || !binding.equals(decision.binding()) || !FEATURE.equals(decision.featureKey())
                    || !FEATURE_ROUTE.equals(decision.decisionRoute()) || !"ACTIVE".equals(decision.featureState())
                    || !"ALLOWED".equals(decision.decision()) || decision.featureDecisionRevision() == null
                    || decision.featureDecisionRevision().isBlank() || decision.evaluatedAt() == null
                    || decision.evaluatedAt().isAfter(now) || decision.revalidateAt() == null
                    || !decision.revalidateAt().isAfter(now)) throw unavailable();
            return decision;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (Exception failure) { throw unavailable(); }
    }

    static URI endpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        try {
            URI base = URI.create(baseUrl);
            if (!"https".equals(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                    || base.getQuery() != null || base.getFragment() != null
                    || !(base.getRawPath().isEmpty() || base.getRawPath().equals("/"))) throw unavailable();
            return base.resolve(Endpoint.FEATURE.path(null));
        } catch (RuntimeException invalid) { throw unavailable(); }
    }

    private static BaseException unavailable() { return new BaseException(ErrorCode.FORBIDDEN, "PER004_FEATURE_DECISION_UNAVAILABLE"); }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 65536 - bytes.size()) {
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
