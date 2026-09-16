package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
    private final HomeCanonicalJson canonicalJson;
    private final Executor executor;

    public WidgetRuntimeBroker(
            List<WidgetProviderPort> providers,
            RecipientBoundWidgetCache cache,
            ProviderResultValidator validator,
            HomeRuntimeProperties properties,
            HomeRuntimeTelemetry telemetry,
            HomeCanonicalJson canonicalJson,
            @Qualifier("homeRuntimeExecutor") Executor executor) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                WidgetProviderPort::providerKey, Function.identity()));
        this.cache = cache;
        this.validator = validator;
        this.properties = properties;
        this.telemetry = telemetry;
        this.canonicalJson = canonicalJson;
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
                        ProviderOutcome.cacheHit(providerKey, requests, fresh)));
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
                                "No Home provider is registered."),
                        Duration.ZERO)));
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
                    return ProviderOutcome.success(
                            providerKey, requests, key, response,
                            Duration.ofNanos(System.nanoTime() - started));
                } catch (WidgetProviderException exception) {
                    return ProviderOutcome.failure(
                            providerKey, requests, key, exception,
                            Duration.ofNanos(System.nanoTime() - started));
                } catch (RuntimeException exception) {
                    return ProviderOutcome.failure(providerKey, requests, key,
                            new WidgetProviderException(
                                    WidgetProviderException.Kind.UNAVAILABLE,
                                    "PROVIDER_FAILURE",
                                    "Home provider failed.", exception),
                            Duration.ofNanos(System.nanoTime() - started));
                }
            }, executor).completeOnTimeout(
                    ProviderOutcome.failure(providerKey, requests, key,
                            new WidgetProviderException(
                                    WidgetProviderException.Kind.TIMEOUT,
                                    "PROVIDER_TIMEOUT",
                                    "Home provider exceeded its deadline."),
                            properties.providerTimeout()),
                    Math.max(1, properties.providerTimeout().toMillis()),
                    TimeUnit.MILLISECONDS);
            futures.add(future);
        }
        long remaining = Math.max(1, Duration.between(
                OffsetDateTime.now(ZoneOffset.UTC), deadline).toMillis());
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(remaining, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            futures.forEach(future -> future.cancel(true));
        } catch (java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException ignored) {
            futures.forEach(future -> future.cancel(true));
        }
        List<HomeWidgetProviderContract.WidgetResult> results = new ArrayList<>();
        for (CompletableFuture<ProviderOutcome> future : futures) {
            ProviderOutcome outcome = future.isDone() && !future.isCancelled()
                    ? future.getNow(null) : null;
            if (outcome == null) continue;
            if (outcome.providerInvoked()) {
                telemetry.provider(
                        outcome.providerKey(),
                        outcome.failure() == null ? "SUCCESS" : outcome.failure().kind().name(),
                        outcome.duration());
            }
            if (outcome.response() != null) {
                if (outcome.cacheKey() != null) {
                    cacheIfEligible(
                            outcome.cacheKey(), outcome.response(),
                            context.authorityRevalidateAt());
                }
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
        String requestFingerprint = canonicalJson.fingerprint(requests.stream()
                .map(WidgetProviderPort.Request::contract).toList());
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
                requestFingerprint);
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
                        now, now.plusSeconds(1), null, reasonCode, retryable, null),
                Map.of(), List.of(), List.of());
    }

    static String providerKey(String sourceAppResourceKey) {
        if (sourceAppResourceKey == null) return "unknown";
        return switch (sourceAppResourceKey) {
            case "APP.WORK", "APP.CALENDAR", "APP.ACTIVITY" -> "platform";
            case "APP.WORKPLACE" -> "workplace";
            case "APP.ASK" -> "dwaion";
            case "APP.APPROVALS" -> "approval";
            case "APP.MEETINGS" -> "meeting";
            case "APP.NOTIFICATIONS" -> "notification";
            case "APP.SPACES" -> "space";
            case "APP.MESSAGING" -> "messaging";
            case "APP.HCM" -> "people";
            default -> "unknown";
        };
    }

    private int indexOf(List<WidgetProviderPort.Request> requests, java.util.UUID instanceId) {
        for (int index = 0; index < requests.size(); index++) {
            if (requests.get(index).instanceId().equals(instanceId)) return index;
        }
        return Integer.MAX_VALUE;
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
            WidgetProviderException failure,
            Duration duration,
            boolean providerInvoked) {

        static ProviderOutcome cacheHit(
                String providerKey,
                List<WidgetProviderPort.Request> requests,
                HomeWidgetProviderContract.BatchResponse response) {
            return new ProviderOutcome(
                    providerKey, requests, null, response, null, Duration.ZERO, false);
        }

        static ProviderOutcome success(
                String providerKey,
                List<WidgetProviderPort.Request> requests,
                RecipientBoundWidgetCache.Key cacheKey,
                HomeWidgetProviderContract.BatchResponse response,
                Duration duration) {
            return new ProviderOutcome(
                    providerKey, requests, cacheKey, response, null, duration, true);
        }

        static ProviderOutcome failure(
                String providerKey,
                List<WidgetProviderPort.Request> requests,
                RecipientBoundWidgetCache.Key cacheKey,
                WidgetProviderException failure,
                Duration duration) {
            return new ProviderOutcome(
                    providerKey, requests, cacheKey, null, failure, duration, true);
        }
    }
}
