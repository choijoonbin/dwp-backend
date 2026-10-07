package com.dwp.gateway;

import com.dwp.gateway.productsurface.FeatureRolloutDecisionCache;
import com.dwp.gateway.productsurface.FeatureRolloutEvaluationClient;
import com.dwp.gateway.productsurface.FeatureRolloutInvalidationConsumer;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceRolloutSafetyLatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

abstract class ProductSurfaceFeatureRolloutTestSupport {

    protected static final Instant T0 = Instant.parse("2026-08-24T00:00:00Z");

    protected final FeatureRolloutEvaluationClient outageClient(
            ProductSurfaceRolloutSafetyLatch latch) {
        return client(
                new FeatureRolloutDecisionCache(Duration.ofSeconds(60), 100),
                latch,
                request -> Mono.just(
                        ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()));
    }

    protected final FeatureRolloutEvaluationClient client(
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

    protected static ProductSurfaceRolloutSafetyLatch.Snapshot snapshot(
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

    protected static FeatureRolloutDecisionCache.FlagDecision decision(
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

    protected final AnnotationConfigApplicationContext invalidationContext(boolean enabled) {
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

    protected static FeatureRolloutDecisionCache.FlagDecision unavailable(String flag) {
        return new FeatureRolloutDecisionCache.FlagDecision(
                flag, false, "PROVIDER_UNAVAILABLE", "unavailable",
                "baseline", null, false);
    }

    protected static FeatureRolloutInvalidationConsumer.DecisionChangedEvent event(
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

    protected static FeatureRolloutEvaluationClient.RequestMetadata metadata() {
        return new FeatureRolloutEvaluationClient.RequestMetadata(
                "corr-1", "00-trace", "vendor=state");
    }

    protected static String revision(long value) {
        return "rev-" + String.format(java.util.Locale.ROOT, "%020d", value);
    }

    protected final GeneratedProductRouteCatalog productRouteCatalog() {
        return new GeneratedProductRouteCatalog(
                new ObjectMapper(),
                new ClassPathResource(
                        "product-authorization/product-surfaces-v1.generated.json"));
    }

    protected static String readBody(
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
