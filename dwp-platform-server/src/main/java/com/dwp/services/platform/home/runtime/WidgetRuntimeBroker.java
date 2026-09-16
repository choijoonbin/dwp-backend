package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WidgetRuntimeBroker {

    private final Map<String, WidgetProviderPort> providers;
    private final RecipientBoundWidgetCache cache;
    private final ProviderResultValidator validator;
    private final HomeRuntimeProperties properties;
    private final HomeRuntimeTelemetry telemetry;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public WidgetRuntimeBroker(
            List<WidgetProviderPort> providers,
            RecipientBoundWidgetCache cache,
            ProviderResultValidator validator,
            HomeRuntimeProperties properties,
            HomeRuntimeTelemetry telemetry,
            ObjectMapper objectMapper,
            @Qualifier("homeRuntimeExecutor") Executor executor) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                WidgetProviderPort::providerKey, Function.identity()));
        this.cache = cache;
        this.validator = validator;
        this.properties = properties;
        this.telemetry = telemetry;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public List<HomeWidgetProviderContract.WidgetResult> read(
            HomeRuntimeContext context,
            Revisions revisions,
            List<WidgetProviderPort.Request> requested) {
        if (requested.size() > HomeWidgetProviderContract.MAX_WIDGETS_PER_BATCH) {
            throw new IllegalArgumentException("Home widget instance budget exceeded.");
        }
        OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC)
                .plus(properties.overallDeadline());
        Map<String, List<WidgetProviderPort.Request>> grouped = requested.stream()
                .collect(Collectors.groupingBy(
                        request -> providerKey(request.definition().sourceAppResourceKey()),
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<CompletableFuture<ProviderOutcome>> futures = new ArrayList<>();
        for (Map.Entry<String, List<WidgetProviderPort.Request>> entry : grouped.entrySet()) {
            String providerKey = entry.getKey();
            List<WidgetProviderPort.Request> requests = List.copyOf(entry.getValue());
            RecipientBoundWidgetCache.Key key = key(context, revisions, providerKey, requests);
            HomeWidgetProviderContract.BatchResponse fresh = cache.fresh(key).orElse(null);
            if (fresh != null) {
                telemetry.cache(providerKey, "HIT");
                futures.add(CompletableFuture.completedFuture(
                        ProviderOutcome.success(providerKey, requests, fresh)));
                continue;
            }
            telemetry.cache(providerKey, "MISS");
            WidgetProviderPort provider = providers.get(providerKey);
            if (provider == null) {
                futures.add(CompletableFuture.completedFuture(ProviderOutcome.failure(
                        providerKey,
                        requests,
                        key,
                        new WidgetProviderException(
                                WidgetProviderException.Kind.UNAVAILABLE,
                                "PROVIDER_NOT_REGISTERED",
                                "No Home provider is registered."))));
                continue;
            }
            long started = System.nanoTime();
            OffsetDateTime providerDeadline = earliest(
                    deadline,
                    OffsetDateTime.now(ZoneOffset.UTC).plus(properties.providerTimeout()));
            CompletableFuture<ProviderOutcome> future = CompletableFuture.supplyAsync(() -> {
                try {
                    HomeWidgetProviderContract.BatchResponse raw = provider.readBatch(
                            context, requests, providerDeadline);
                    if (!OffsetDateTime.now(ZoneOffset.UTC).isBefore(providerDeadline)) {
                        throw new WidgetProviderException(
                                WidgetProviderException.Kind.TIMEOUT,
                                "PROVIDER_TIMEOUT",
                                "Home provider completed after its deadline.");
                    }
                    HomeWidgetProviderContract.BatchResponse response = validator.validate(
                            raw, context, requests);
                    cacheIfEligible(key, response, context.authorityRevalidateAt());
                    telemetry.provider(providerKey, "SUCCESS",
                            Duration.ofNanos(System.nanoTime() - started));
                    return ProviderOutcome.success(providerKey, requests, response);
                } catch (WidgetProviderException exception) {
                    telemetry.provider(providerKey, exception.kind().name(),
                            Duration.ofNanos(System.nanoTime() - started));
                    return ProviderOutcome.failure(providerKey, requests, key, exception);
                } catch (RuntimeException exception) {
                    telemetry.provider(providerKey, "UNAVAILABLE",
                            Duration.ofNanos(System.nanoTime() - started));
                    return ProviderOutcome.failure(providerKey, requests, key,
                            new WidgetProviderException(
                                    WidgetProviderException.Kind.UNAVAILABLE,
                                    "PROVIDER_FAILURE",
                                    "Home provider failed.", exception));
                }
            }, executor).completeOnTimeout(
                    ProviderOutcome.failure(providerKey, requests, key,
                            new WidgetProviderException(
                                    WidgetProviderException.Kind.TIMEOUT,
                                    "PROVIDER_TIMEOUT",
                                    "Home provider exceeded its deadline.")),
                    Math.max(1, properties.providerTimeout().toMillis()),
                    TimeUnit.MILLISECONDS);
            futures.add(future);
        }
        long remaining = Math.max(1, Duration.between(
                OffsetDateTime.now(ZoneOffset.UTC), deadline).toMillis());
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(remaining, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            futures.forEach(future -> future.cancel(true));
        }
        List<HomeWidgetProviderContract.WidgetResult> results = new ArrayList<>();
        for (CompletableFuture<ProviderOutcome> future : futures) {
            ProviderOutcome outcome = future.isDone() && !future.isCancelled()
                    ? future.getNow(null) : null;
            if (outcome == null) continue;
            if (outcome.response() != null) {
                results.addAll(outcome.response().results());
            } else {
                results.addAll(degraded(context, outcome));
            }
        }
        Set<java.util.UUID> returned = results.stream()
                .map(HomeWidgetProviderContract.WidgetResult::instanceId)
                .collect(Collectors.toSet());
        requested.stream().filter(request -> !returned.contains(request.instanceId()))
                .map(request -> unavailable(request, "HOME_DEADLINE_EXCEEDED"))
                .forEach(results::add);
        results.forEach(result -> telemetry.state(
                providerKey(requested.stream()
                        .filter(request -> request.instanceId().equals(result.instanceId()))
                        .findFirst().map(request -> request.definition().sourceAppResourceKey())
                        .orElse(null)),
                result.state()));
        return results.stream()
                .sorted(Comparator.comparing(result -> indexOf(requested, result.instanceId())))
                .toList();
    }

    private List<HomeWidgetProviderContract.WidgetResult> degraded(
            HomeRuntimeContext context,
            ProviderOutcome outcome) {
        WidgetProviderException failure = outcome.failure();
        if (failure.kind() == WidgetProviderException.Kind.FORBIDDEN) {
            return outcome.requests().stream()
                    .map(request -> forbidden(request, failure.reasonCode()))
                    .toList();
        }
        if (failure.kind() == WidgetProviderException.Kind.UNAVAILABLE
                || failure.kind() == WidgetProviderException.Kind.TIMEOUT) {
            HomeWidgetProviderContract.BatchResponse stale = cache.stale(outcome.cacheKey())
                    .orElse(null);
            if (stale != null
                    && stale.tenantId() == context.tenantId()
                    && stale.userId() == context.userId()
                    && context.authorityDecisionRevision().equals(stale.authorityDecisionRevision())) {
                telemetry.cache(outcome.providerKey(), "STALE_FALLBACK");
                return stale.results().stream()
                        .map(result -> stale(result, failure.reasonCode()))
                        .toList();
            }
        }
        return outcome.requests().stream()
                .map(request -> unavailable(request, failure.reasonCode()))
                .toList();
    }

    private void cacheIfEligible(
            RecipientBoundWidgetCache.Key key,
            HomeWidgetProviderContract.BatchResponse response,
            OffsetDateTime authorityValidUntil) {
        boolean eligible = response.results().stream().noneMatch(result ->
                result.state() == HomeWidgetProviderContract.State.FORBIDDEN
                        || result.state() == HomeWidgetProviderContract.State.UNAVAILABLE
                        || result.state() == HomeWidgetProviderContract.State.STALE);
        if (!eligible) return;
        OffsetDateTime expiry = response.results().stream()
                .map(result -> result.source().expiresAt())
                .min(Comparator.naturalOrder())
                .orElse(OffsetDateTime.now(ZoneOffset.UTC).plus(properties.cacheTtl()));
        cache.put(key, response, expiry, authorityValidUntil);
    }

    private RecipientBoundWidgetCache.Key key(
            HomeRuntimeContext context,
            Revisions revisions,
            String providerKey,
            List<WidgetProviderPort.Request> requests) {
        Set<String> definitions = requests.stream()
                .map(request -> request.definition().definitionKey())
                .collect(Collectors.toUnmodifiableSet());
        String requestMaterial;
        try {
            requestMaterial = objectMapper.writeValueAsString(requests.stream()
                    .map(WidgetProviderPort.Request::contract).toList());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
        return new RecipientBoundWidgetCache.Key(
                context.tenantId(),
                context.userId(),
                context.fingerprint(),
                context.authorityDecisionRevision(),
                context.locale(),
                context.timeZone(),
                revisions.mode(),
                revisions.deviceClass(),
                revisions.viewRevision(),
                revisions.catalogRevision(),
                revisions.policyRevision(),
                revisions.safetyRevision(),
                providerKey,
                definitions,
                sha256(requestMaterial));
    }

    private OffsetDateTime earliest(OffsetDateTime first, OffsetDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private HomeWidgetProviderContract.WidgetResult stale(
            HomeWidgetProviderContract.WidgetResult result,
            String reasonCode) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new HomeWidgetProviderContract.WidgetResult(
                result.instanceId(), result.definitionKey(), result.definitionManifestHash(),
                result.rendererBindingRevision(), HomeWidgetProviderContract.State.STALE,
                new HomeWidgetProviderContract.SourceState(
                        result.source().sourceKey(), result.source().generatedAt(), now,
                        result.source().lastSuccessAt() == null
                                ? result.source().generatedAt() : result.source().lastSuccessAt(),
                        reasonCode, true, result.source().resultVersion()),
                result.payload(), result.actions(), result.redactions());
    }

    private HomeWidgetProviderContract.WidgetResult forbidden(
            WidgetProviderPort.Request request,
            String reasonCode) {
        return state(request, HomeWidgetProviderContract.State.FORBIDDEN,
                reasonCode.startsWith("AUTHORIZATION_")
                        ? reasonCode : "AUTHORIZATION_PROVIDER_FORBIDDEN", false);
    }

    private HomeWidgetProviderContract.WidgetResult unavailable(
            WidgetProviderPort.Request request,
            String reasonCode) {
        return state(request, HomeWidgetProviderContract.State.UNAVAILABLE, reasonCode, true);
    }

    private HomeWidgetProviderContract.WidgetResult state(
            WidgetProviderPort.Request request,
            HomeWidgetProviderContract.State state,
            String reasonCode,
            boolean retryable) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new HomeWidgetProviderContract.WidgetResult(
                request.instanceId(), request.definition().definitionKey(),
                request.definition().manifestHash(),
                request.definition().rendererBindingRevision(), state,
                new HomeWidgetProviderContract.SourceState(
                        providerKey(request.definition().sourceAppResourceKey()).toUpperCase()
                                + "_HOME",
                        now, now, null, reasonCode, retryable, null),
                Map.of(), List.of(), List.of());
    }

    static String providerKey(String sourceAppResourceKey) {
        if (sourceAppResourceKey == null) return "platform";
        return switch (sourceAppResourceKey) {
            case "APP.APPROVALS" -> "approval";
            case "APP.MEETINGS" -> "meeting";
            case "APP.NOTIFICATIONS" -> "notification";
            case "APP.SPACES" -> "space";
            case "APP.MESSAGING" -> "messaging";
            case "APP.HCM" -> "people";
            default -> "platform";
        };
    }

    private int indexOf(List<WidgetProviderPort.Request> requests, java.util.UUID instanceId) {
        for (int index = 0; index < requests.size(); index++) {
            if (requests.get(index).instanceId().equals(instanceId)) return index;
        }
        return Integer.MAX_VALUE;
    }

    private String sha256(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Revisions(
            String mode,
            String deviceClass,
            String viewRevision,
            String catalogRevision,
            String policyRevision,
            String safetyRevision) {
    }

    private record ProviderOutcome(
            String providerKey,
            List<WidgetProviderPort.Request> requests,
            RecipientBoundWidgetCache.Key cacheKey,
            HomeWidgetProviderContract.BatchResponse response,
            WidgetProviderException failure) {

        static ProviderOutcome success(
                String providerKey,
                List<WidgetProviderPort.Request> requests,
                HomeWidgetProviderContract.BatchResponse response) {
            return new ProviderOutcome(providerKey, requests, null, response, null);
        }

        static ProviderOutcome failure(
                String providerKey,
                List<WidgetProviderPort.Request> requests,
                RecipientBoundWidgetCache.Key cacheKey,
                WidgetProviderException failure) {
            return new ProviderOutcome(providerKey, requests, cacheKey, null, failure);
        }
    }
}
