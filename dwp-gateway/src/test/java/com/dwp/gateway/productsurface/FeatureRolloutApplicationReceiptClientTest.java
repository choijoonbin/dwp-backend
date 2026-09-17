package com.dwp.gateway.productsurface;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureRolloutApplicationReceiptClientTest {

    @Test
    void sendsOnlyTrustedGatewayEvidenceToTheExactInternalBoundary() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        FeatureRolloutApplicationReceiptClient client =
                new FeatureRolloutApplicationReceiptClient(
                        WebClient.builder().exchangeFunction(request -> {
                            captured.set(request);
                            return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
                        }),
                        "http://provider.test",
                        "trusted-provider-service-token",
                        Duration.ofMillis(500));
        FeatureRolloutDecisionCache.FlagDecision decision =
                new FeatureRolloutDecisionCache.FlagDecision(
                        "ux.product-surfaces.approvals.v1",
                        true,
                        "ROLLOUT_MATCH",
                        "rev-00000000000000000009",
                        "eligible-25",
                        Instant.parse("2026-09-17T06:00:00Z"),
                        true);

        client.applied(
                41,
                decision,
                new FeatureRolloutEvaluationClient.RequestMetadata(
                        "correlation-1", "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01",
                        "vendor=state"))
                .block();

        assertThat(captured.get().url().getPath())
                .isEqualTo(FeatureRolloutApplicationReceiptClient.INTERNAL_PATH);
        assertThat(captured.get().headers().getFirst("X-DWP-Service-Token"))
                .isEqualTo("trusted-provider-service-token");
        assertThat(captured.get().headers().getFirst("X-DWP-Service-Identity"))
                .isEqualTo("dwp-gateway");
        assertThat(captured.get().headers().getFirst("X-Correlation-ID"))
                .isEqualTo("correlation-1");
    }

    @Test
    void receiptTransportFailureNeverChangesTheAppliedDecisionPath() {
        FeatureRolloutApplicationReceiptClient client =
                new FeatureRolloutApplicationReceiptClient(
                        WebClient.builder().exchangeFunction(request -> Mono.just(
                                ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build())),
                        "http://provider.test",
                        "trusted-provider-service-token",
                        Duration.ofMillis(500));
        FeatureRolloutDecisionCache.FlagDecision decision =
                new FeatureRolloutDecisionCache.FlagDecision(
                        "ux.product-surfaces.approvals.v1",
                        true,
                        "ROLLOUT_MATCH",
                        "rev-00000000000000000009",
                        "eligible-25",
                        Instant.parse("2026-09-17T06:00:00Z"),
                        true);

        client.applied(41, decision, null).block();
    }

    @Test
    void repeatedSamplesOfTheSameEvidenceReuseOneImmutableReceiptIdentity() {
        FeatureRolloutDecisionCache.FlagDecision revisionNine = decision(
                "rev-00000000000000000009");

        UUID first = FeatureRolloutApplicationReceiptClient.receiptId(41, revisionNine);
        UUID repeated = FeatureRolloutApplicationReceiptClient.receiptId(41, revisionNine);
        UUID anotherTenant = FeatureRolloutApplicationReceiptClient.receiptId(42, revisionNine);
        UUID anotherRevision = FeatureRolloutApplicationReceiptClient.receiptId(
                41, decision("rev-00000000000000000010"));

        assertThat(repeated).isEqualTo(first);
        assertThat(anotherTenant).isNotEqualTo(first);
        assertThat(anotherRevision).isNotEqualTo(first);
    }

    private FeatureRolloutDecisionCache.FlagDecision decision(String revision) {
        return new FeatureRolloutDecisionCache.FlagDecision(
                "ux.product-surfaces.approvals.v1",
                true,
                "ROLLOUT_MATCH",
                revision,
                "eligible-25",
                Instant.parse("2026-09-17T06:00:00Z"),
                true);
    }
}
