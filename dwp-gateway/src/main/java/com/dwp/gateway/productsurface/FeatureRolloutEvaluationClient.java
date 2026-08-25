package com.dwp.gateway.productsurface;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class FeatureRolloutEvaluationClient {

    public static final String CONTEXT_SHADOW_FLAG =
            ProductSurfaceRolloutFlagCatalog.CONTEXT_SHADOW_FLAG;
    public static final String CAPABILITY_ENFORCEMENT_FLAG =
            ProductSurfaceRolloutFlagCatalog.LEGACY_GLOBAL_ENFORCEMENT_FLAG;

    private static final String INTERNAL_PATH =
            "/internal/provider/v1/feature-rollouts/evaluate";
    private static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    private static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    private static final String SERVICE_IDENTITY = "dwp-gateway";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final String TRACE_STATE_HEADER = "tracestate";
    private static final Pattern FLAG_KEY = Pattern.compile(
            "^(access|ux)\\.product-surfaces\\.[a-z0-9.-]+\\.v1$");
    private static final Set<String> COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");
    private static final Set<String> REASON_CODES = Set.of(
            "DEFAULT", "TARGET_MISS", "PERCENTAGE_EXCLUDED", "ROLLOUT_MATCH");
    private static final Set<String> VALID_STATES = Set.of("000", "100", "110", "111");

    private final WebClient providerClient;
    private final FeatureRolloutDecisionCache cache;
    private final ProductSurfaceRolloutSafetyLatch safetyLatch;
    private final String serviceToken;
    private final Duration timeout;

    public FeatureRolloutEvaluationClient(
            WebClient.Builder webClientBuilder,
            FeatureRolloutDecisionCache cache,
            ProductSurfaceRolloutSafetyLatch safetyLatch,
            @Value("${SERVICE_PROVIDER_URL:http://localhost:8004}") String providerServiceUrl,
            @Value("${dwp.provider.service-token:}") String serviceToken,
            @Value("${dwp.product-surface.rollout-evaluation-timeout:2s}") Duration timeout) {
        this.providerClient = webClientBuilder.baseUrl(providerServiceUrl).build();
        this.cache = cache;
        this.safetyLatch = Objects.requireNonNull(
                safetyLatch, "A rollout safety latch is required");
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException(
                    "Feature rollout evaluation timeout must be between 1ms and 10s");
        }
        this.timeout = timeout;
    }

    public Mono<FeatureRolloutDecisionCache.FlagDecision> evaluate(
            long authTenantId,
            String flagKey,
            RequestMetadata metadata) {
        if (authTenantId <= 0 || !FLAG_KEY.matcher(flagKey == null ? "" : flagKey).matches()) {
            return Mono.just(FeatureRolloutDecisionCache.FlagDecision.unavailable(flagKey));
        }
        var cached = cache.current(authTenantId, flagKey);
        if (cached.isPresent()) return Mono.just(cached.get());
        if (serviceToken.isBlank()) {
            return Mono.just(FeatureRolloutDecisionCache.FlagDecision.unavailable(flagKey));
        }

        InternalEvaluationRequest body = new InternalEvaluationRequest(authTenantId, flagKey);
        return providerClient.post()
                .uri(INTERNAL_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> trustedHeaders(headers, metadata))
                .bodyValue(body)
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToMono(ProviderEnvelope.class)
                        : response.createException().flatMap(Mono::error))
                .filter(envelope -> Boolean.TRUE.equals(envelope.success())
                        && envelope.data() != null)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Provider returned an empty rollout decision")))
                .map(ProviderEnvelope::data)
                .map(data -> validated(flagKey, data))
                .flatMap(decision -> Mono.justOrEmpty(
                                cache.putAndResolve(authTenantId, decision))
                        .switchIfEmpty(Mono.error(new IllegalStateException(
                                "Provider returned a stale rollout decision"))))
                .timeout(timeout)
                .onErrorResume(ignored -> Mono.just(cache.current(authTenantId, flagKey)
                        .orElseGet(() -> FeatureRolloutDecisionCache.FlagDecision
                                .unavailable(flagKey))));
    }

    public Mono<List<ProductSurfaceContextDtos.ProductRollout>> evaluateProducts(
            long authTenantId,
            List<String> productKeys,
            RequestMetadata metadata) {
        List<String> products = normalizeProducts(productKeys);
        if (products.isEmpty()) return Mono.just(List.of());
        return evaluate(authTenantId, CONTEXT_SHADOW_FLAG, metadata)
                .flatMapMany(shadow -> Flux.fromIterable(products)
                        .concatMap(productKey -> Mono.zip(
                                        evaluate(
                                                authTenantId,
                                                productEnforcementFlag(productKey),
                                                metadata),
                                        evaluate(authTenantId, uiFlag(productKey), metadata))
                                .flatMap(axes -> resolveProductDecision(
                                                authTenantId,
                                                productKey,
                                                shadow,
                                                axes.getT1())
                                        .map(approved -> new ResolvedProductDecision(
                                                productKey,
                                                approved,
                                                axes.getT2())))))
                .collectList()
                .map(this::requireGlobalShadowInvariant)
                .map(values -> values.stream()
                        .map(value -> combine(
                                value.productKey(),
                                value.axes().shadow(),
                                value.axes().enforcement(),
                                value.axes().recovered()
                                        ? unavailableAxis(uiFlag(value.productKey()))
                                        : value.ui()))
                        .toList());
    }

    private List<ResolvedProductDecision> requireGlobalShadowInvariant(
            List<ResolvedProductDecision> values) {
        if (values.isEmpty()) return values;
        FeatureRolloutDecisionCache.FlagDecision expected =
                values.getFirst().axes().shadow();
        boolean inconsistent = values.stream()
                .map(value -> value.axes().shadow())
                .anyMatch(shadow -> shadow.enabled() != expected.enabled()
                        || !Objects.equals(
                                shadow.opaqueRevision(), expected.opaqueRevision()));
        if (inconsistent) throw new RolloutAuthorityUnavailableException();
        return values;
    }

    private Mono<ResolvedProductAxes> resolveProductDecision(
                    long authTenantId,
                    String productKey,
                    FeatureRolloutDecisionCache.FlagDecision shadow,
                    FeatureRolloutDecisionCache.FlagDecision enforcement) {
        if (shadow.authoritative() && enforcement.authoritative()) {
            return safetyLatch.approve(authTenantId, productKey, shadow, enforcement)
                    .flatMap(result -> result.hasStoredSnapshot()
                            ? Mono.just(productAxesFrom(
                                    productKey, result.snapshot(), false))
                            : Mono.error(new RolloutAuthorityUnavailableException()));
        }
        return safetyLatch.load(authTenantId, productKey)
                .flatMap(result -> switch (result.status()) {
                    case FOUND -> Mono.just(productAxesFrom(
                            productKey, result.snapshot(), true));
                    case MISSING -> Mono.just(new ResolvedProductAxes(
                            unavailableAxis(CONTEXT_SHADOW_FLAG),
                            unavailableAxis(productEnforcementFlag(productKey)),
                            true));
                    case MIGRATION_REQUIRED, CORRUPT, UNAVAILABLE ->
                            Mono.error(new RolloutAuthorityUnavailableException());
                });
    }

    private ResolvedProductAxes productAxesFrom(
                    String productKey,
                    ProductSurfaceRolloutSafetyLatch.Snapshot snapshot,
                    boolean recovered) {
        if (snapshot == null) throw new RolloutAuthorityUnavailableException();
        return new ResolvedProductAxes(
                restoredAxis(
                        CONTEXT_SHADOW_FLAG,
                        snapshot.contextShadow(),
                        snapshot.shadowOpaqueRevision()),
                restoredAxis(
                        productEnforcementFlag(productKey),
                        snapshot.capabilityEnforcement(),
                        snapshot.enforcementOpaqueRevision()),
                recovered);
    }

    private FeatureRolloutDecisionCache.FlagDecision restoredAxis(
            String flagKey,
            boolean enabled,
            String revision) {
        try {
            FeatureRolloutDecisionCache.revisionNumber(revision);
        } catch (RuntimeException exception) {
            throw new RolloutAuthorityUnavailableException();
        }
        return new FeatureRolloutDecisionCache.FlagDecision(
                flagKey,
                enabled,
                enabled ? "ROLLOUT_MATCH" : "DEFAULT",
                revision,
                enabled ? "full" : "baseline",
                Instant.EPOCH,
                true);
    }

    private FeatureRolloutDecisionCache.FlagDecision unavailableAxis(String flagKey) {
        return FeatureRolloutDecisionCache.FlagDecision.unavailable(flagKey);
    }

    public static String productEnforcementFlag(String productKey) {
        return ProductSurfaceRolloutFlagCatalog.productEnforcementFlag(productKey);
    }

    public static String uiFlag(String productKey) {
        return ProductSurfaceRolloutFlagCatalog.uiFlag(productKey);
    }

    public static ProductSurfaceContextDtos.ProductRollout combine(
            String productKey,
            FeatureRolloutDecisionCache.FlagDecision shadow,
            FeatureRolloutDecisionCache.FlagDecision enforcement,
            FeatureRolloutDecisionCache.FlagDecision ui) {
        if (!CONTEXT_SHADOW_FLAG.equals(shadow.flagKey())
                || !productEnforcementFlag(productKey).equals(enforcement.flagKey())
                || !uiFlag(productKey).equals(ui.flagKey())) {
            throw new InvalidRolloutStateException();
        }
        if (shadow.authoritative() && enforcement.authoritative()
                && enforcement.enabled() && !shadow.enabled()) {
            throw new InvalidRolloutStateException();
        }
        if (enforcement.authoritative() && ui.authoritative()
                && ui.enabled() && !enforcement.enabled()) {
            throw new InvalidRolloutStateException();
        }

        boolean effectiveShadow = shadow.authoritative() && shadow.enabled();
        boolean effectiveEnforcement = effectiveShadow
                && enforcement.authoritative() && enforcement.enabled();
        boolean effectiveUi = effectiveEnforcement && ui.authoritative() && ui.enabled();
        String state = bits(effectiveShadow, effectiveEnforcement, effectiveUi);
        if (!VALID_STATES.contains(state)) throw new InvalidRolloutStateException();
        String cohort = ui.authoritative() && (!ui.enabled() || effectiveUi)
                ? ui.cohort()
                : "baseline";
        return new ProductSurfaceContextDtos.ProductRollout(
                productKey,
                state,
                new ProductSurfaceContextDtos.RolloutFlags(
                        effectiveShadow, effectiveEnforcement, effectiveUi),
                cohort,
                combinedRevision(productKey, shadow, enforcement, ui),
                ProductSurfaceContextDtos.AuthorityStatus.NOT_EVALUATED);
    }

    private FeatureRolloutDecisionCache.FlagDecision validated(
            String requestedFlag,
            InternalEvaluation data) {
        if (!requestedFlag.equals(data.flagKey())
                || !REASON_CODES.contains(data.reasonCode())
                || !COHORTS.contains(data.cohort())
                || (!data.enabled()
                        && !Set.of("baseline", "holdout").contains(data.cohort()))
                || (data.enabled()
                        && Set.of("baseline", "holdout").contains(data.cohort()))
                || data.evaluatedAt() == null) {
            throw new IllegalStateException("Provider returned an invalid rollout decision");
        }
        FeatureRolloutDecisionCache.revisionNumber(data.opaqueRevision());
        return new FeatureRolloutDecisionCache.FlagDecision(
                data.flagKey(),
                data.enabled(),
                data.reasonCode(),
                data.opaqueRevision(),
                data.cohort(),
                data.evaluatedAt(),
                true);
    }

    private List<String> normalizeProducts(List<String> productKeys) {
        if (productKeys == null) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String productKey : productKeys) {
            if (!ProductSurfaceRolloutFlagCatalog.supportsProduct(productKey)) {
                throw new InvalidRolloutStateException();
            }
            unique.add(productKey);
        }
        return unique.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private void trustedHeaders(HttpHeaders headers, RequestMetadata metadata) {
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
        if (value != null && !value.isBlank()) headers.set(name, value);
    }

    private static String bits(boolean shadow, boolean enforcement, boolean ui) {
        return (shadow ? "1" : "0")
                + (enforcement ? "1" : "0")
                + (ui ? "1" : "0");
    }

    private static String combinedRevision(
            String productKey,
            FeatureRolloutDecisionCache.FlagDecision... decisions) {
        StringBuilder material = new StringBuilder(productKey);
        for (FeatureRolloutDecisionCache.FlagDecision decision : decisions) {
            material.append('\n').append(decision.flagKey())
                    .append('=').append(decision.opaqueRevision())
                    .append(':').append(decision.enabled())
                    .append(':').append(decision.authoritative());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            return "rollout-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record RequestMetadata(
            String correlationId,
            String traceParent,
            String traceState) {
    }

    private record InternalEvaluationRequest(long authTenantId, String flagKey) {
    }

    private record ProviderEnvelope(Boolean success, InternalEvaluation data) {
    }

    private record InternalEvaluation(
            String flagKey,
            boolean enabled,
            String reasonCode,
            String opaqueRevision,
            String cohort,
            Instant evaluatedAt) {
    }

    private record ResolvedProductAxes(
            FeatureRolloutDecisionCache.FlagDecision shadow,
            FeatureRolloutDecisionCache.FlagDecision enforcement,
            boolean recovered) {
    }

    private record ResolvedProductDecision(
            String productKey,
            ResolvedProductAxes axes,
            FeatureRolloutDecisionCache.FlagDecision ui) {
    }

    public static final class InvalidRolloutStateException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public static final class RolloutAuthorityUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
