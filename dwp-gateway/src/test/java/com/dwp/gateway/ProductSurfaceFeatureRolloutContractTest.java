package com.dwp.gateway;

import com.dwp.gateway.filter.ProductSurfaceRolloutHeaderFilter;
import com.dwp.gateway.filter.ProviderServiceIdentityFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.productsurface.FeatureRolloutDecisionCache;
import com.dwp.gateway.productsurface.FeatureRolloutEvaluationClient;
import com.dwp.gateway.productsurface.FeatureRolloutInvalidationConsumer;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import com.dwp.gateway.productsurface.ProductSurfaceRolloutSafetyLatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductSurfaceFeatureRolloutContractTest {

    private static final Instant T0 = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void acceptsOnlyTheFourApprovedRolloutCombinations() {
        for (String state : List.of("000", "100", "110", "111")) {
            ProductSurfaceContextDtos.ProductRollout rollout =
                    FeatureRolloutEvaluationClient.combine(
                            "approvals",
                            decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                                    state.charAt(0) == '1', 1, "baseline"),
                            decision(FeatureRolloutEvaluationClient.productEnforcementFlag(
                                            "approvals"),
                                    state.charAt(1) == '1', 1, "baseline"),
                            decision(FeatureRolloutEvaluationClient.uiFlag("approvals"),
                                    state.charAt(2) == '1', 1, "eligible-25"));

            assertThat(rollout.state()).isEqualTo(state);
        }

        for (String state : List.of("001", "010", "011", "101")) {
            assertThatThrownBy(() -> FeatureRolloutEvaluationClient.combine(
                    "approvals",
                    decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                            state.charAt(0) == '1', 1, "baseline"),
                    decision(FeatureRolloutEvaluationClient.productEnforcementFlag(
                                    "approvals"),
                            state.charAt(1) == '1', 1, "baseline"),
                    decision(FeatureRolloutEvaluationClient.uiFlag("approvals"),
                            state.charAt(2) == '1', 1, "eligible-25")))
                    .isInstanceOf(FeatureRolloutEvaluationClient
                            .InvalidRolloutStateException.class);
        }
    }

    @Test
    void uiEvaluationFailureFallsBackToCompatibilityShellWithoutDisablingEnforcement() {
        ProductSurfaceContextDtos.ProductRollout rollout =
                FeatureRolloutEvaluationClient.combine(
                        "hcm",
                        decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                                true, 4, "baseline"),
                        decision(FeatureRolloutEvaluationClient.productEnforcementFlag("hcm"),
                                true, 6, "baseline"),
                        unavailable(FeatureRolloutEvaluationClient.uiFlag("hcm")));

        assertThat(rollout.state()).isEqualTo("110");
        assertThat(rollout.flags().capabilityEnforcement()).isTrue();
        assertThat(rollout.flags().surfaceUi()).isFalse();
        assertThat(rollout.cohort()).isEqualTo("baseline");
    }

    @Test
    void aNewTenantProviderOutageIsOffAndDoesNotCreateACachedAllow() {
        ProductSurfaceContextDtos.ProductRollout rollout =
                FeatureRolloutEvaluationClient.combine(
                        "services",
                        unavailable(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG),
                        unavailable(FeatureRolloutEvaluationClient.productEnforcementFlag(
                                "services")),
                        unavailable(FeatureRolloutEvaluationClient.uiFlag("services")));

        assertThat(rollout.state()).isEqualTo("000");
        assertThat(rollout.flags()).isEqualTo(
                new ProductSurfaceContextDtos.RolloutFlags(false, false, false));
    }

    @Test
    void cacheIsRevisionBoundAndWildcardInvalidationIgnoresDuplicateOrOlderEvents() {
        FeatureRolloutDecisionCache cache =
                new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100);
        String flag = FeatureRolloutEvaluationClient.uiFlag("approvals");
        cache.put(7L, decision(flag, true, 5, "eligible-25"));
        cache.put(8L, decision(flag, false, 5, "holdout"));
        FeatureRolloutInvalidationConsumer consumer =
                new FeatureRolloutInvalidationConsumer(cache, new ObjectMapper());
        var event = event(flag, 6, T0.plusSeconds(1));

        assertThat(consumer.consume(event)).isTrue();
        assertThat(cache.current(7L, flag)).isEmpty();
        assertThat(cache.current(8L, flag)).isEmpty();
        assertThat(consumer.consume(event)).isFalse();
        assertThat(consumer.consume(event(flag, 4, T0.minusSeconds(1)))).isFalse();

        cache.put(7L, new FeatureRolloutDecisionCache.FlagDecision(
                flag, true, "ROLLOUT_MATCH", revision(5), "eligible-25",
                T0.plusSeconds(2), true));
        assertThat(cache.current(7L, flag)).isEmpty();

        cache.put(7L, new FeatureRolloutDecisionCache.FlagDecision(
                flag, true, "ROLLOUT_MATCH", revision(6), "eligible-25",
                T0.plusSeconds(2), true));
        assertThat(cache.current(7L, flag)).isPresent();
    }

    @Test
    void providerResponseRejectedByAnInvalidationWatermarkIsNeverUsedDirectly() {
        String flag = FeatureRolloutEvaluationClient.uiFlag("approvals");
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("""
                        {"success":true,"data":{
                          "flagKey":"ux.product-surfaces.approvals.v1",
                          "enabled":true,
                          "reasonCode":"ROLLOUT_MATCH",
                          "opaqueRevision":"rev-00000000000000000005",
                          "cohort":"eligible-25",
                          "evaluatedAt":"2026-08-24T00:00:02Z"
                        }}
                        """)
                .build());
        FeatureRolloutDecisionCache cache =
                new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100);
        cache.invalidateAll(flag, 6, T0.plusSeconds(1));
        FeatureRolloutEvaluationClient client = client(
                cache, mock(ProductSurfaceRolloutSafetyLatch.class), exchange);

        var result = client.evaluate(7L, flag, metadata()).block();

        assertThat(result.authoritative()).isFalse();
        assertThat(result.enabled()).isFalse();
        assertThat(cache.current(7L, flag)).isEmpty();
    }

    @Test
    void cacheConfigurationRejectsTtlBeyondSixtySeconds() {
        assertThatThrownBy(() ->
                new FeatureRolloutDecisionCache(Duration.ofSeconds(61), 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void kafkaInvalidationListenerIsDisabledByDefaultAndRequiresTheExactOptIn() {
        try (AnnotationConfigApplicationContext disabled = invalidationContext(false)) {
            assertThat(disabled.getBeansOfType(FeatureRolloutInvalidationConsumer.class))
                    .isEmpty();
        }
        try (AnnotationConfigApplicationContext enabled = invalidationContext(true)) {
            assertThat(enabled.getBeansOfType(FeatureRolloutInvalidationConsumer.class))
                    .hasSize(1);
        }
    }

    @Test
    void kafkaConsumerIgnoresUnknownPayloadsAndAppliesTheExactEventSchema() {
        FeatureRolloutDecisionCache cache =
                new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100);
        String flag = FeatureRolloutEvaluationClient.uiFlag("hcm");
        cache.put(7L, decision(flag, true, 3, "eligible-10"));
        FeatureRolloutInvalidationConsumer consumer =
                new FeatureRolloutInvalidationConsumer(cache, new ObjectMapper());

        consumer.onMessage("""
                {"eventId":"00000000-0000-0000-0000-000000000001",
                 "tenantScope":"ALL","tenantId":null,
                 "flagKey":"ux.product-surfaces.hcm.v1",
                 "opaqueRevision":"rev-00000000000000000004",
                 "state":"PAUSED","occurredAt":"2026-08-24T00:00:01Z",
                 "unexpected":"must-not-be-accepted"}
                """);
        assertThat(cache.current(7L, flag)).isPresent();

        consumer.onMessage("""
                {"eventId":"00000000-0000-0000-0000-000000000003",
                 "tenantScope":"ALL","tenantId":null,
                 "flagKey":"ux.product-surfaces.hcm.v1",
                 "opaqueRevision":"rev-00000000000000000004",
                 "state":"UNKNOWN_FUTURE_STATE","occurredAt":"2026-08-24T00:00:01Z"}
                """);
        assertThat(cache.current(7L, flag)).isPresent();

        consumer.onMessage("""
                {"eventId":"00000000-0000-0000-0000-000000000004",
                 "eventId":"00000000-0000-0000-0000-000000000005",
                 "tenantScope":"ALL","tenantId":null,
                 "flagKey":"ux.product-surfaces.hcm.v1",
                 "opaqueRevision":"rev-00000000000000000004",
                 "state":"PAUSED","occurredAt":"2026-08-24T00:00:01Z"}
                """);
        assertThat(cache.current(7L, flag)).isPresent();

        consumer.onMessage("""
                {"eventId":"00000000-0000-0000-0000-000000000002",
                 "tenantScope":"ALL","tenantId":null,
                 "flagKey":"ux.product-surfaces.hcm.v1",
                 "opaqueRevision":"rev-00000000000000000004",
                 "state":"PAUSED","occurredAt":"2026-08-24T00:00:01Z"}
                """);
        assertThat(cache.current(7L, flag)).isEmpty();
    }

    @Test
    void exactInvalidationUsesTheVerifiedAuthTenantLongAndIgnoresOlderRevisions() {
        FeatureRolloutDecisionCache cache =
                new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100);
        String flag = FeatureRolloutEvaluationClient.uiFlag("services");
        cache.put(7L, decision(flag, true, 3, "eligible-10"));
        cache.put(8L, decision(flag, true, 3, "eligible-10"));
        FeatureRolloutInvalidationConsumer consumer =
                new FeatureRolloutInvalidationConsumer(cache, new ObjectMapper());

        consumer.onMessage("""
                {"eventId":"00000000-0000-0000-0000-000000000011",
                 "tenantScope":"EXACT","tenantId":7,
                 "flagKey":"ux.product-surfaces.services.v1",
                 "opaqueRevision":"rev-00000000000000000004",
                 "state":"ROLLED_BACK","occurredAt":"2026-08-24T00:00:01Z"}
                """);

        assertThat(cache.current(7L, flag)).isEmpty();
        assertThat(cache.current(8L, flag)).isPresent();

        cache.put(7L, new FeatureRolloutDecisionCache.FlagDecision(
                flag, true, "ROLLOUT_MATCH", revision(4), "eligible-10",
                T0.plusSeconds(2), true));
        consumer.onMessage("""
                {"eventId":"00000000-0000-0000-0000-000000000012",
                 "tenantScope":"EXACT","tenantId":7,
                 "flagKey":"ux.product-surfaces.services.v1",
                 "opaqueRevision":"rev-00000000000000000003",
                 "state":"PAUSED","occurredAt":"2026-08-24T00:00:03Z"}
                """);
        consumer.onMessage("""
                {"eventId":"00000000-0000-0000-0000-000000000013",
                 "tenantScope":"EXACT",
                 "tenantId":"00000000-0000-0000-0000-000000000007",
                 "flagKey":"ux.product-surfaces.services.v1",
                 "opaqueRevision":"rev-00000000000000000005",
                 "state":"PAUSED","occurredAt":"2026-08-24T00:00:04Z"}
                """);

        assertThat(cache.current(7L, flag)).isPresent();
    }

    @Test
    void providerClientUsesOnlyTheExactInternalServiceContractAndCachesItsRevision() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<org.springframework.web.reactive.function.client.ClientRequest> captured =
                new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            calls.incrementAndGet();
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("""
                            {"success":true,"data":{
                              "flagKey":"ux.product-surfaces.approvals.v1",
                              "enabled":true,
                              "reasonCode":"ROLLOUT_MATCH",
                              "opaqueRevision":"rev-00000000000000000009",
                              "cohort":"eligible-25",
                              "evaluatedAt":"2026-08-24T00:00:00Z"
                            }}
                            """)
                    .build());
        };
        FeatureRolloutDecisionCache cache =
                new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100);
        FeatureRolloutEvaluationClient client = new FeatureRolloutEvaluationClient(
                WebClient.builder().exchangeFunction(exchange),
                cache,
                mock(ProductSurfaceRolloutSafetyLatch.class),
                "http://provider.test",
                "trusted-provider-service-token",
                Duration.ofSeconds(2));
        String flag = FeatureRolloutEvaluationClient.uiFlag("approvals");

        var first = client.evaluate(7L, flag, metadata()).block();
        var second = client.evaluate(7L, flag, metadata()).block();

        assertThat(first).isNotNull();
        assertThat(first.authoritative()).isTrue();
        assertThat(second).isEqualTo(first);
        assertThat(calls).hasValue(1);
        assertThat(captured.get().url().getPath())
                .isEqualTo("/internal/provider/v1/feature-rollouts/evaluate");
        assertThat(captured.get().headers().getFirst("X-DWP-Service-Token"))
                .isEqualTo("trusted-provider-service-token");
        assertThat(captured.get().headers().getFirst("X-DWP-Service-Identity"))
                .isEqualTo("dwp-gateway");
    }

    @Test
    void providerOutageRestoresDurableEnforcementAndOnlyDisablesTheUxAxis() {
        ProductSurfaceRolloutSafetyLatch latch = mock(ProductSurfaceRolloutSafetyLatch.class);
        when(latch.load(71L, "hcm")).thenReturn(Mono.just(
                new ProductSurfaceRolloutSafetyLatch.LoadResult(
                        ProductSurfaceRolloutSafetyLatch.LoadStatus.FOUND,
                        snapshot(true, 10, true, 11))));
        FeatureRolloutDecisionCache cache =
                new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100);
        cache.put(71L, decision(
                FeatureRolloutEvaluationClient.uiFlag("hcm"),
                true, 12, "eligible-25"));
        FeatureRolloutEvaluationClient client = client(cache, latch, request -> Mono.just(
                ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()));

        var rollout = client.evaluateProducts(71L, List.of("hcm"), metadata())
                .block().getFirst();

        assertThat(rollout.state()).isEqualTo("110");
        assertThat(rollout.flags().capabilityEnforcement()).isTrue();
        assertThat(rollout.flags().surfaceUi()).isFalse();
        verify(latch).load(71L, "hcm");
    }

    @Test
    void providerOutageForANewTenantIsOffOnlyAfterAConfirmedMissingLatch() {
        ProductSurfaceRolloutSafetyLatch latch = mock(ProductSurfaceRolloutSafetyLatch.class);
        when(latch.load(72L, "approvals")).thenReturn(Mono.just(
                new ProductSurfaceRolloutSafetyLatch.LoadResult(
                        ProductSurfaceRolloutSafetyLatch.LoadStatus.MISSING, null)));
        FeatureRolloutEvaluationClient client = outageClient(latch);

        var rollout = client.evaluateProducts(72L, List.of("approvals"), metadata())
                .block().getFirst();

        assertThat(rollout.state()).isEqualTo("000");
        assertThat(rollout.flags()).isEqualTo(
                new ProductSurfaceContextDtos.RolloutFlags(false, false, false));
    }

    @Test
    void providerOutageWithUnavailableOrCorruptLatchFailsClosed() {
        for (ProductSurfaceRolloutSafetyLatch.LoadStatus status : List.of(
                ProductSurfaceRolloutSafetyLatch.LoadStatus.MIGRATION_REQUIRED,
                ProductSurfaceRolloutSafetyLatch.LoadStatus.UNAVAILABLE,
                ProductSurfaceRolloutSafetyLatch.LoadStatus.CORRUPT)) {
            ProductSurfaceRolloutSafetyLatch latch =
                    mock(ProductSurfaceRolloutSafetyLatch.class);
            when(latch.load(73L, "services")).thenReturn(Mono.just(
                    new ProductSurfaceRolloutSafetyLatch.LoadResult(status, null)));
            FeatureRolloutEvaluationClient client = outageClient(latch);

            assertThatThrownBy(() -> client.evaluateProducts(
                            73L, List.of("services"), metadata()).block())
                    .isInstanceOf(FeatureRolloutEvaluationClient
                            .RolloutAuthorityUnavailableException.class);
        }
    }

    @Test
    void outOfOrderProviderEvaluationUsesTheAtomicStoredLatchSnapshot() {
        ProductSurfaceRolloutSafetyLatch latch = mock(ProductSurfaceRolloutSafetyLatch.class);
        FeatureRolloutDecisionCache cache =
                new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100);
        var incomingShadow = decision(
                FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                true, 21, "full");
        var incomingEnforcement = decision(
                FeatureRolloutEvaluationClient.productEnforcementFlag("hcm"),
                false, 29, "baseline");
        cache.put(74L, incomingShadow);
        cache.put(74L, incomingEnforcement);
        cache.put(74L, decision(
                FeatureRolloutEvaluationClient.uiFlag("hcm"),
                true, 40, "eligible-25"));
        when(latch.approve(74L, "hcm", incomingShadow, incomingEnforcement))
                .thenReturn(Mono.just(new ProductSurfaceRolloutSafetyLatch.ApprovalResult(
                        ProductSurfaceRolloutSafetyLatch.ApprovalStatus.OUT_OF_ORDER,
                        snapshot(true, 20, true, 30))));
        FeatureRolloutEvaluationClient client = client(cache, latch, request ->
                Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()));

        var rollout = client.evaluateProducts(74L, List.of("hcm"), metadata())
                .block().getFirst();

        assertThat(rollout.state()).isEqualTo("111");
        verify(latch).approve(74L, "hcm", incomingShadow, incomingEnforcement);
    }

    @Test
    void authoritativeEvaluationFailsClosedWhenItCannotBeDurablyApproved() {
        ProductSurfaceRolloutSafetyLatch latch = mock(ProductSurfaceRolloutSafetyLatch.class);
        FeatureRolloutDecisionCache cache =
                new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100);
        var shadow = decision(
                FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                true, 31, "full");
        var enforcement = decision(
                FeatureRolloutEvaluationClient.productEnforcementFlag("hcm"),
                true, 32, "full");
        cache.put(75L, shadow);
        cache.put(75L, enforcement);
        when(latch.approve(75L, "hcm", shadow, enforcement))
                .thenReturn(Mono.just(new ProductSurfaceRolloutSafetyLatch.ApprovalResult(
                        ProductSurfaceRolloutSafetyLatch.ApprovalStatus.UNAVAILABLE, null)));
        FeatureRolloutEvaluationClient client = client(cache, latch, request ->
                Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()));

        assertThatThrownBy(() -> client.evaluateProducts(
                        75L, List.of("hcm"), metadata()).block())
                .isInstanceOf(FeatureRolloutEvaluationClient
                        .RolloutAuthorityUnavailableException.class);
    }

    @Test
    void productScopedEnforcementAllowsPilotAndDraftProductsToCoexist() {
        ProductSurfaceRolloutSafetyLatch latch = mock(ProductSurfaceRolloutSafetyLatch.class);
        FeatureRolloutDecisionCache cache =
                new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100);
        var shadow = decision(
                FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG, true, 41, "full");
        var approvalsEnforcement = decision(
                FeatureRolloutEvaluationClient.productEnforcementFlag("approvals"),
                true, 42, "full");
        var calendarEnforcement = decision(
                FeatureRolloutEvaluationClient.productEnforcementFlag("calendar"),
                false, 43, "baseline");
        cache.put(76L, shadow);
        cache.put(76L, approvalsEnforcement);
        cache.put(76L, calendarEnforcement);
        cache.put(76L, decision(
                FeatureRolloutEvaluationClient.uiFlag("approvals"),
                true, 44, "eligible-25"));
        cache.put(76L, decision(
                FeatureRolloutEvaluationClient.uiFlag("calendar"),
                false, 45, "baseline"));
        when(latch.approve(76L, "approvals", shadow, approvalsEnforcement))
                .thenReturn(Mono.just(new ProductSurfaceRolloutSafetyLatch.ApprovalResult(
                        ProductSurfaceRolloutSafetyLatch.ApprovalStatus.CREATED,
                        snapshot(true, 41, true, 42))));
        when(latch.approve(76L, "calendar", shadow, calendarEnforcement))
                .thenReturn(Mono.just(new ProductSurfaceRolloutSafetyLatch.ApprovalResult(
                        ProductSurfaceRolloutSafetyLatch.ApprovalStatus.CREATED,
                        snapshot(true, 41, false, 43))));
        FeatureRolloutEvaluationClient client = client(cache, latch, request ->
                Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()));

        var rollouts = client.evaluateProducts(
                76L, List.of("calendar", "approvals"), metadata()).block();

        assertThat(rollouts).extracting(
                        ProductSurfaceContextDtos.ProductRollout::productKey,
                        ProductSurfaceContextDtos.ProductRollout::state)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("approvals", "111"),
                        org.assertj.core.groups.Tuple.tuple("calendar", "100"));
        verify(latch).approve(76L, "approvals", shadow, approvalsEnforcement);
        verify(latch).approve(76L, "calendar", shadow, calendarEnforcement);
    }

    @Test
    void differentResolvedGlobalShadowBitsFailClosedAcrossProducts() {
        ProductSurfaceRolloutSafetyLatch latch = mock(ProductSurfaceRolloutSafetyLatch.class);
        when(latch.load(77L, "approvals")).thenReturn(Mono.just(
                new ProductSurfaceRolloutSafetyLatch.LoadResult(
                        ProductSurfaceRolloutSafetyLatch.LoadStatus.FOUND,
                        snapshot(true, 60, true, 61))));
        when(latch.load(77L, "hcm")).thenReturn(Mono.just(
                new ProductSurfaceRolloutSafetyLatch.LoadResult(
                        ProductSurfaceRolloutSafetyLatch.LoadStatus.FOUND,
                        snapshot(false, 60, false, 62))));
        FeatureRolloutEvaluationClient client = outageClient(latch);

        assertThatThrownBy(() -> client.evaluateProducts(
                        77L, List.of("approvals", "hcm"), metadata()).block())
                .isInstanceOf(FeatureRolloutEvaluationClient
                        .RolloutAuthorityUnavailableException.class);
    }

    @Test
    void sameResolvedGlobalShadowBitWithDifferentRevisionFailsClosed() {
        ProductSurfaceRolloutSafetyLatch latch = mock(ProductSurfaceRolloutSafetyLatch.class);
        when(latch.load(78L, "approvals")).thenReturn(Mono.just(
                new ProductSurfaceRolloutSafetyLatch.LoadResult(
                        ProductSurfaceRolloutSafetyLatch.LoadStatus.FOUND,
                        snapshot(true, 70, true, 71))));
        when(latch.load(78L, "hcm")).thenReturn(Mono.just(
                new ProductSurfaceRolloutSafetyLatch.LoadResult(
                        ProductSurfaceRolloutSafetyLatch.LoadStatus.FOUND,
                        snapshot(true, 72, false, 73))));
        FeatureRolloutEvaluationClient client = outageClient(latch);

        assertThatThrownBy(() -> client.evaluateProducts(
                        78L, List.of("approvals", "hcm"), metadata()).block())
                .isInstanceOf(FeatureRolloutEvaluationClient
                        .RolloutAuthorityUnavailableException.class);
    }

    @Test
    void legacyGlobalEnforcementFlagCannotDriveAProductDecision() {
        assertThatThrownBy(() -> FeatureRolloutEvaluationClient.combine(
                "approvals",
                decision(FeatureRolloutEvaluationClient.CONTEXT_SHADOW_FLAG,
                        true, 50, "full"),
                decision(FeatureRolloutEvaluationClient.CAPABILITY_ENFORCEMENT_FLAG,
                        true, 51, "full"),
                decision(FeatureRolloutEvaluationClient.uiFlag("approvals"),
                        true, 52, "eligible-25")))
                .isInstanceOf(FeatureRolloutEvaluationClient
                        .InvalidRolloutStateException.class);
    }

    @Test
    void publicProviderCatchAllCannotReachTheInternalEvaluator() {
        ProviderServiceIdentityFilter filter =
                new ProviderServiceIdentityFilter("trusted-provider-service-token");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(
                                "/api/provider/internal/provider/v1/feature-rollouts/evaluate")
                        .header("X-DWP-Service-Token", "spoofed")
                        .header("X-DWP-Service-Identity", "dwp-gateway")
                        .build());
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(forwarded).isFalse();
    }

    @Test
    void telemetryReplacesSpoofedRolloutHeadersWithServerEvaluation() {
        FeatureRolloutEvaluationClient client = mock(FeatureRolloutEvaluationClient.class);
        when(client.evaluateProducts(eq(7L), eq(List.of("hcm")), any()))
                .thenReturn(Mono.just(List.of(
                        new ProductSurfaceContextDtos.ProductRollout(
                                "hcm",
                                "111",
                                new ProductSurfaceContextDtos.RolloutFlags(true, true, true),
                                "eligible-10",
                                "rollout-combined-8",
                                ProductSurfaceContextDtos.AuthorityStatus.NOT_EVALUATED))));
        ProductSurfaceRolloutHeaderFilter filter =
                new ProductSurfaceRolloutHeaderFilter(
                        client, productRouteCatalog(), new ObjectMapper());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(
                                "/api/platform/v1/observability/product-surface-events")
                        .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                        .header(ProductSurfaceRolloutHeaderFilter.COHORT_HEADER, "full")
                        .header(ProductSurfaceRolloutHeaderFilter.REVISION_HEADER, "attacker")
                        .body("{\"schemaVersion\":1,\"eventName\":\"surface.exposed\","
                                + "\"productKey\":\"hcm\"}"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceRolloutHeaderFilter.COHORT_HEADER)).isEqualTo("eligible-10");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceRolloutHeaderFilter.REVISION_HEADER))
                .isEqualTo("rollout-combined-8");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceRolloutHeaderFilter.STATE_HEADER)).isEqualTo("111");
        assertThat(readBody(forwarded.get())).contains("\"productKey\":\"hcm\"");
        verify(client).evaluateProducts(eq(7L), eq(List.of("hcm")), any());
    }

    @Test
    void approvalRequestsReceiveOnlyTenantAuthoritativeRolloutEvidence() {
        FeatureRolloutEvaluationClient client = mock(FeatureRolloutEvaluationClient.class);
        when(client.evaluateProducts(eq(7L), eq(List.of("approvals")), any()))
                .thenReturn(Mono.just(List.of(
                        new ProductSurfaceContextDtos.ProductRollout(
                                "approvals",
                                "110",
                                new ProductSurfaceContextDtos.RolloutFlags(true, true, false),
                                "eligible-25",
                                "rollout-authoritative-revision",
                                ProductSurfaceContextDtos.AuthorityStatus.NOT_EVALUATED))));
        ProductSurfaceRolloutHeaderFilter filter =
                new ProductSurfaceRolloutHeaderFilter(
                        client, productRouteCatalog(), new ObjectMapper());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/approvals/v1/admin/workflows")
                        .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                        .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                        .header(ProductSurfaceRolloutHeaderFilter.COHORT_HEADER, "attacker")
                        .header(ProductSurfaceRolloutHeaderFilter.REVISION_HEADER, "attacker")
                        .build());
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceRolloutHeaderFilter.STATE_HEADER)).isEqualTo("110");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceRolloutHeaderFilter.COHORT_HEADER)).isEqualTo("eligible-25");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceRolloutHeaderFilter.REVISION_HEADER))
                .isEqualTo("rollout-authoritative-revision");
        verify(client).evaluateProducts(eq(7L), eq(List.of("approvals")), any());
    }

    @Test
    void exactLegacyWorkforceAccessRequestsBypassRolloutAndStripSpoofedEvidence() {
        FeatureRolloutEvaluationClient client = mock(FeatureRolloutEvaluationClient.class);
        ProductSurfaceRolloutHeaderFilter filter =
                new ProductSurfaceRolloutHeaderFilter(
                        client, productRouteCatalog(), new ObjectMapper());
        List<MockServerHttpRequest.BaseBuilder<?>> requests = List.of(
                MockServerHttpRequest.get(
                        "/api/people/v1/admin/workforce/access-policies"),
                MockServerHttpRequest.get(
                        "/api/people/v1/admin/workforce/access-policies/organizations"),
                MockServerHttpRequest.post(
                        "/api/people/v1/admin/workforce/access-policies"),
                MockServerHttpRequest.patch(
                        "/api/people/v1/admin/workforce/access-policies/policy-7/revoke"));

        for (MockServerHttpRequest.BaseBuilder<?> request : requests) {
            MockServerWebExchange exchange = MockServerWebExchange.from(request
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "111")
                    .header(ProductSurfaceRolloutHeaderFilter.COHORT_HEADER, "attacker")
                    .header(ProductSurfaceRolloutHeaderFilter.REVISION_HEADER, "attacker"));
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange, filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).isNotNull();
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceRolloutHeaderFilter.STATE_HEADER)).isFalse();
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceRolloutHeaderFilter.COHORT_HEADER)).isFalse();
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceRolloutHeaderFilter.REVISION_HEADER)).isFalse();
        }
        verify(client, org.mockito.Mockito.never())
                .evaluateProducts(anyLong(), any(), any());
    }

    @Test
    void legacyWorkforceAccessPathDriftStillFailsClosedBeforeRolloutEvaluation() {
        FeatureRolloutEvaluationClient client = mock(FeatureRolloutEvaluationClient.class);
        ProductSurfaceRolloutHeaderFilter filter =
                new ProductSurfaceRolloutHeaderFilter(
                        client, productRouteCatalog(), new ObjectMapper());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(
                                "/api/people/v1/admin/workforce/access-policies/policy-7/revoke/")
                        .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                        .build());
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(client, org.mockito.Mockito.never())
                .evaluateProducts(anyLong(), any(), any());
    }

    @Test
    void approvalRequestsFailClosedWithoutVerifiedTenantOrRolloutAuthority() {
        FeatureRolloutEvaluationClient client = mock(FeatureRolloutEvaluationClient.class);
        ProductSurfaceRolloutHeaderFilter filter =
                new ProductSurfaceRolloutHeaderFilter(
                        client, productRouteCatalog(), new ObjectMapper());
        MockServerWebExchange missingTenant = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/approvals/v1/home")
                        .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "111")
                        .build());
        AtomicBoolean missingTenantForwarded = new AtomicBoolean();

        filter.filter(missingTenant, ignored -> {
            missingTenantForwarded.set(true);
            return Mono.empty();
        }).block();

        when(client.evaluateProducts(eq(7L), eq(List.of("approvals")), any()))
                .thenReturn(Mono.error(new FeatureRolloutEvaluationClient
                        .RolloutAuthorityUnavailableException()));
        MockServerWebExchange unavailable = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/approvals/v1/home")
                        .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                        .build());
        AtomicBoolean unavailableForwarded = new AtomicBoolean();

        filter.filter(unavailable, ignored -> {
            unavailableForwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(missingTenant.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missingTenantForwarded).isFalse();
        assertThat(unavailable.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailableForwarded).isFalse();
    }

    @Test
    void telemetryRejectsDuplicateProductKeysBeforeRolloutEvaluation() {
        FeatureRolloutEvaluationClient client = mock(FeatureRolloutEvaluationClient.class);
        ProductSurfaceRolloutHeaderFilter filter =
                new ProductSurfaceRolloutHeaderFilter(
                        client, productRouteCatalog(), new ObjectMapper());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(
                                "/api/platform/v1/observability/product-surface-events")
                        .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                        .body("{\"schemaVersion\":1,\"productKey\":\"hcm\","
                                + "\"productKey\":\"approvals\"}"));

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verify(client, org.mockito.Mockito.never())
                .evaluateProducts(anyLong(), any(), any());
    }

    @Test
    void telemetryReturnsServiceUnavailableWhenTheDurableLatchCannotBeRead() {
        FeatureRolloutEvaluationClient client = mock(FeatureRolloutEvaluationClient.class);
        when(client.evaluateProducts(eq(7L), eq(List.of("hcm")), any()))
                .thenReturn(Mono.error(new FeatureRolloutEvaluationClient
                        .RolloutAuthorityUnavailableException()));
        ProductSurfaceRolloutHeaderFilter filter =
                new ProductSurfaceRolloutHeaderFilter(
                        client, productRouteCatalog(), new ObjectMapper());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(
                                "/api/platform/v1/observability/product-surface-events")
                        .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                        .body("{\"schemaVersion\":1,\"eventName\":\"surface.exposed\","
                                + "\"productKey\":\"hcm\"}"));
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(forwarded).isFalse();
    }

    private FeatureRolloutEvaluationClient outageClient(
            ProductSurfaceRolloutSafetyLatch latch) {
        return client(
                new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100),
                latch,
                request -> Mono.just(
                        ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()));
    }

    private FeatureRolloutEvaluationClient client(
            FeatureRolloutDecisionCache cache,
            ProductSurfaceRolloutSafetyLatch latch,
            ExchangeFunction exchange) {
        return new FeatureRolloutEvaluationClient(
                WebClient.builder().exchangeFunction(exchange),
                cache,
                latch,
                "http://provider.test",
                "trusted-provider-service-token",
                Duration.ofSeconds(2));
    }

    private static ProductSurfaceRolloutSafetyLatch.Snapshot snapshot(
            boolean shadow,
            long shadowRevision,
            boolean enforcement,
            long enforcementRevision) {
        return new ProductSurfaceRolloutSafetyLatch.Snapshot(
                shadow,
                revision(shadowRevision),
                enforcement,
                revision(enforcementRevision));
    }

    private static FeatureRolloutDecisionCache.FlagDecision decision(
            String flag,
            boolean enabled,
            long revision,
            String cohort) {
        return new FeatureRolloutDecisionCache.FlagDecision(
                flag,
                enabled,
                enabled ? "ROLLOUT_MATCH" : "PERCENTAGE_EXCLUDED",
                revision(revision),
                cohort,
                T0,
                true);
    }

    private AnnotationConfigApplicationContext invalidationContext(boolean enabled) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (enabled) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "rollout-invalidation-test",
                    Map.of("dwp.gateway.product-surface-rollout.invalidation-enabled", "true")));
        }
        context.registerBean(
                FeatureRolloutDecisionCache.class,
                () -> new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.register(FeatureRolloutInvalidationConsumer.class);
        context.refresh();
        return context;
    }

    private static FeatureRolloutDecisionCache.FlagDecision unavailable(String flag) {
        return new FeatureRolloutDecisionCache.FlagDecision(
                flag, false, "PROVIDER_UNAVAILABLE", "unavailable",
                "baseline", null, false);
    }

    private static FeatureRolloutInvalidationConsumer.DecisionChangedEvent event(
            String flag,
            long revision,
            Instant createdAt) {
        return new FeatureRolloutInvalidationConsumer.DecisionChangedEvent(
                UUID.randomUUID(),
                "ALL",
                null,
                flag,
                revision(revision),
                "PAUSED",
                createdAt);
    }

    private static FeatureRolloutEvaluationClient.RequestMetadata metadata() {
        return new FeatureRolloutEvaluationClient.RequestMetadata(
                "corr-1", "00-trace", "vendor=state");
    }

    private static String revision(long value) {
        return "rev-" + String.format(java.util.Locale.ROOT, "%020d", value);
    }

    private GeneratedProductRouteCatalog productRouteCatalog() {
        return new GeneratedProductRouteCatalog(
                new ObjectMapper(),
                new ClassPathResource(
                        "product-authorization/product-surfaces-v1.generated.json"));
    }

    private static String readBody(
            org.springframework.http.server.reactive.ServerHttpRequest request) {
        DataBuffer buffer = DataBufferUtils.join(request.getBody()).block();
        if (buffer == null) return "";
        byte[] bytes = new byte[buffer.readableByteCount()];
        try {
            buffer.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
