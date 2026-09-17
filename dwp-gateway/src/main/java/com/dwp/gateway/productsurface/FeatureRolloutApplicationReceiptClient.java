package com.dwp.gateway.productsurface;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@Component
public class FeatureRolloutApplicationReceiptClient {

    static final String INTERNAL_PATH =
            "/internal/provider/v1/feature-rollouts/application-receipts";
    private static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    private static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    private static final String SERVICE_IDENTITY = "dwp-gateway";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final String TRACE_STATE_HEADER = "tracestate";

    private final WebClient providerClient;
    private final String serviceToken;
    private final Duration timeout;

    public FeatureRolloutApplicationReceiptClient(
            WebClient.Builder webClientBuilder,
            @Value("${SERVICE_PROVIDER_URL:http://localhost:8004}") String providerServiceUrl,
            @Value("${dwp.provider.service-token:}") String serviceToken,
            @Value("${dwp.product-surface.rollout-application-receipt-timeout:500ms}")
                    Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(2)) > 0) {
            throw new IllegalArgumentException(
                    "Feature rollout receipt timeout must be between 1ms and 2s");
        }
        this.providerClient = webClientBuilder.baseUrl(providerServiceUrl).build();
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.timeout = timeout;
    }

    /**
     * Best-effort telemetry after the authoritative decision is in the enforcement cache. A
     * receipt outage must never turn an otherwise valid access decision into an outage.
     */
    public Mono<Void> applied(
            long authTenantId,
            FeatureRolloutDecisionCache.FlagDecision decision,
            FeatureRolloutEvaluationClient.RequestMetadata metadata) {
        if (serviceToken.isBlank() || authTenantId <= 0
                || decision == null || !decision.authoritative()) {
            return Mono.empty();
        }
        ApplicationReceiptRequest request = new ApplicationReceiptRequest(
                receiptId(authTenantId, decision), authTenantId, decision.flagKey(),
                decision.opaqueRevision(), "APPLIED", null);
        return providerClient.post()
                .uri(INTERNAL_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> trustedHeaders(headers, metadata))
                .bodyValue(request)
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.releaseBody()
                        : response.createException().flatMap(Mono::error))
                .timeout(timeout)
                .onErrorResume(ignored -> Mono.empty());
    }

    /**
     * One immutable ledger row represents one sampled application fact. Cache refreshes for the
     * same tenant, flag, and revision reuse that identity so Provider can refresh only the latest
     * projection timestamp instead of growing the immutable ledger on every request-path sample.
     */
    static UUID receiptId(
            long authTenantId,
            FeatureRolloutDecisionCache.FlagDecision decision) {
        String evidenceKey = "dwp:feature-rollout:sampled-application:v1\n"
                + authTenantId + "\n"
                + decision.flagKey() + "\n"
                + decision.opaqueRevision() + "\nAPPLIED";
        return UUID.nameUUIDFromBytes(evidenceKey.getBytes(StandardCharsets.UTF_8));
    }

    private void trustedHeaders(
            HttpHeaders headers,
            FeatureRolloutEvaluationClient.RequestMetadata metadata) {
        headers.set(SERVICE_TOKEN_HEADER, serviceToken);
        headers.set(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY);
        if (metadata != null) {
            copy(headers, CORRELATION_HEADER, metadata.correlationId());
            copy(headers, TRACE_PARENT_HEADER, metadata.traceParent());
            copy(headers, TRACE_STATE_HEADER, metadata.traceState());
        }
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
    }

    private void copy(HttpHeaders headers, String name, String value) {
        if (value != null && !value.isBlank()) {
            headers.set(name, value);
        }
    }

    record ApplicationReceiptRequest(
            UUID receiptId,
            long authTenantId,
            String flagKey,
            String opaqueRevision,
            String observationState,
            String errorCode) {
    }
}
