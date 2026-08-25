package com.dwp.gateway.productsurface;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FeatureRolloutDecisionCache {

    private static final Pattern REVISION = Pattern.compile("^rev-(\\d{20})$");

    private final Cache<CacheKey, FlagDecision> decisions;
    private final Cache<TenantFlag, String> currentRevisions;
    private final ConcurrentMap<String, InvalidationWatermark> wildcardInvalidations =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<TenantFlag, InvalidationWatermark> tenantInvalidations =
            new ConcurrentHashMap<>();

    @Autowired
    public FeatureRolloutDecisionCache(
            @Value("${dwp.product-surface.rollout-cache-ttl:60s}") Duration ttl,
            @Value("${dwp.product-surface.rollout-cache-max-entries:10000}") long maximumSize) {
        this(ttl, maximumSize, Caffeine.newBuilder());
    }

    FeatureRolloutDecisionCache(
            Duration ttl,
            long maximumSize,
            Caffeine<Object, Object> builder) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()
                || ttl.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException(
                    "Feature rollout cache TTL must be between 1ms and 60s");
        }
        if (maximumSize < 1 || maximumSize > 100_000) {
            throw new IllegalArgumentException(
                    "Feature rollout cache size must be between 1 and 100000");
        }
        @SuppressWarnings("unchecked")
        Cache<CacheKey, FlagDecision> cache = (Cache<CacheKey, FlagDecision>) (Cache<?, ?>)
                builder.maximumSize(maximumSize).expireAfterWrite(ttl).build();
        this.decisions = cache;
        this.currentRevisions = Caffeine.<TenantFlag, String>newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(ttl)
                .build();
    }

    public Optional<FlagDecision> current(long tenantId, String flagKey) {
        TenantFlag tenantFlag = tenantFlag(tenantId, flagKey);
        String revision = currentRevisions.getIfPresent(tenantFlag);
        if (revision == null) return Optional.empty();
        FlagDecision decision = decisions.getIfPresent(
                new CacheKey(tenantId, flagKey, revision));
        if (decision == null) currentRevisions.asMap().remove(tenantFlag, revision);
        return Optional.ofNullable(decision);
    }

    public synchronized void put(long tenantId, FlagDecision decision) {
        putAndResolve(tenantId, decision);
    }

    /**
     * Stores an authoritative decision and returns the effective monotonic value. An evaluation
     * rejected by an invalidation watermark is never returned to the caller, which prevents a
     * response from an older Provider transaction from being used even when it was not cached.
     */
    public synchronized Optional<FlagDecision> putAndResolve(
            long tenantId,
            FlagDecision decision) {
        if (decision == null || !decision.authoritative()) return Optional.empty();
        TenantFlag tenantFlag = tenantFlag(tenantId, decision.flagKey());
        long revision = revisionNumber(decision.opaqueRevision());
        if (isStaleAgainst(
                wildcardInvalidations.get(decision.flagKey()), revision, decision.evaluatedAt())
                || isStaleAgainst(
                tenantInvalidations.get(tenantFlag), revision, decision.evaluatedAt())) {
            return current(tenantId, decision.flagKey());
        }

        String existingRevision = currentRevisions.getIfPresent(tenantFlag);
        if (existingRevision != null) {
            FlagDecision existing = decisions.getIfPresent(
                    new CacheKey(tenantId, decision.flagKey(), existingRevision));
            if (existing == null) {
                currentRevisions.asMap().remove(tenantFlag, existingRevision);
            } else if (revisionNumber(existingRevision) >= revision) {
                return Optional.of(existing);
            } else {
                decisions.invalidate(new CacheKey(
                        tenantId, decision.flagKey(), existingRevision));
            }
        }
        decisions.put(new CacheKey(
                tenantId, decision.flagKey(), decision.opaqueRevision()), decision);
        currentRevisions.put(tenantFlag, decision.opaqueRevision());
        return Optional.of(decision);
    }

    public synchronized boolean invalidateAll(
            String flagKey,
            long opaqueRevision,
            Instant createdAt) {
        String normalizedFlag = requireFlag(flagKey);
        InvalidationWatermark watermark = watermark(opaqueRevision, createdAt);
        if (!advance(wildcardInvalidations, normalizedFlag, watermark)) return false;
        decisions.asMap().keySet().removeIf(key -> key.flagKey().equals(normalizedFlag)
                && revisionNumber(key.opaqueRevision()) <= opaqueRevision);
        currentRevisions.asMap().entrySet().removeIf(entry ->
                entry.getKey().flagKey().equals(normalizedFlag)
                        && revisionNumber(entry.getValue()) <= opaqueRevision);
        return true;
    }

    public synchronized boolean invalidateTenant(
            long tenantId,
            String flagKey,
            long opaqueRevision,
            Instant createdAt) {
        TenantFlag tenantFlag = tenantFlag(tenantId, flagKey);
        InvalidationWatermark watermark = watermark(opaqueRevision, createdAt);
        if (!advance(tenantInvalidations, tenantFlag, watermark)) return false;
        decisions.asMap().keySet().removeIf(key -> key.tenantId() == tenantId
                && key.flagKey().equals(tenantFlag.flagKey())
                && revisionNumber(key.opaqueRevision()) <= opaqueRevision);
        currentRevisions.asMap().computeIfPresent(tenantFlag, (ignored, revision) ->
                revisionNumber(revision) <= opaqueRevision ? null : revision);
        return true;
    }

    private <K> boolean advance(
            ConcurrentMap<K, InvalidationWatermark> watermarks,
            K key,
            InvalidationWatermark incoming) {
        AtomicBoolean accepted = new AtomicBoolean();
        watermarks.compute(key, (ignored, current) -> {
            if (current != null && current.revision() >= incoming.revision()) return current;
            accepted.set(true);
            return incoming;
        });
        return accepted.get();
    }

    private boolean isStaleAgainst(
            InvalidationWatermark watermark,
            long revision,
            Instant evaluatedAt) {
        if (watermark == null) return false;
        if (revision < watermark.revision()) return true;
        return revision == watermark.revision()
                && (evaluatedAt == null || !evaluatedAt.isAfter(watermark.createdAt()));
    }

    private TenantFlag tenantFlag(long tenantId, String flagKey) {
        if (tenantId <= 0) throw new IllegalArgumentException("A positive tenant is required");
        return new TenantFlag(tenantId, requireFlag(flagKey));
    }

    private String requireFlag(String flagKey) {
        if (flagKey == null || flagKey.isBlank() || flagKey.length() > 160) {
            throw new IllegalArgumentException("A bounded rollout flag key is required");
        }
        return flagKey;
    }

    private InvalidationWatermark watermark(long revision, Instant createdAt) {
        if (revision < 0 || createdAt == null) {
            throw new IllegalArgumentException(
                    "A non-negative revision and event time are required");
        }
        return new InvalidationWatermark(revision, createdAt);
    }

    static long revisionNumber(String opaqueRevision) {
        Matcher matcher = REVISION.matcher(opaqueRevision == null ? "" : opaqueRevision);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid opaque rollout revision");
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid opaque rollout revision", exception);
        }
    }

    public record FlagDecision(
            String flagKey,
            boolean enabled,
            String reasonCode,
            String opaqueRevision,
            String cohort,
            Instant evaluatedAt,
            boolean authoritative) {

        static FlagDecision unavailable(String flagKey) {
            return new FlagDecision(
                    flagKey,
                    false,
                    "PROVIDER_UNAVAILABLE",
                    "unavailable",
                    "baseline",
                    null,
                    false);
        }
    }

    private record CacheKey(long tenantId, String flagKey, String opaqueRevision) {
    }

    private record TenantFlag(long tenantId, String flagKey) {
    }

    private record InvalidationWatermark(long revision, Instant createdAt) {
    }
}
