package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived process cache whose key is inseparable from recipient and authority evidence. */
@Component
public class RecipientBoundWidgetCache {

    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final HomeRuntimeProperties properties;

    public RecipientBoundWidgetCache(HomeRuntimeProperties properties) {
        this.properties = properties;
    }

    public Optional<HomeWidgetProviderContract.BatchResponse> fresh(Key key) {
        Entry entry = entries.get(key);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (entry == null || !now.isBefore(entry.freshUntil())
                || !now.isBefore(entry.authorityValidUntil())) {
            return Optional.empty();
        }
        return Optional.of(entry.response());
    }

    public Optional<HomeWidgetProviderContract.BatchResponse> stale(Key key) {
        Entry entry = entries.get(key);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (entry == null || !now.isBefore(entry.staleUntil())
                || !now.isBefore(entry.authorityValidUntil())) {
            return Optional.empty();
        }
        return Optional.of(entry.response());
    }

    public void put(
            Key key,
            HomeWidgetProviderContract.BatchResponse response,
            OffsetDateTime providerExpiry,
            OffsetDateTime authorityValidUntil) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime configuredExpiry = now.plus(properties.cacheTtl());
        OffsetDateTime freshUntil = earliest(configuredExpiry, providerExpiry, authorityValidUntil);
        OffsetDateTime staleUntil = earliest(
                freshUntil.plus(properties.staleIfError()), authorityValidUntil);
        entries.put(key, new Entry(response, freshUntil, staleUntil, authorityValidUntil, now));
        trim(now);
    }

    public void invalidateRecipient(long tenantId, long userId) {
        entries.keySet().removeIf(key -> key.tenantId() == tenantId && key.userId() == userId);
    }

    public void invalidateTenant(long tenantId) {
        entries.keySet().removeIf(key -> key.tenantId() == tenantId);
    }

    public void invalidateDefinition(String definitionKey) {
        entries.keySet().removeIf(key -> key.definitionKeys().contains(definitionKey));
    }

    /** Deployment rollback hook: removes every process-local Home provider projection. */
    public void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    private void trim(OffsetDateTime now) {
        entries.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().staleUntil())
                || !now.isBefore(entry.getValue().authorityValidUntil()));
        int overflow = entries.size() - properties.maximumCacheEntries();
        if (overflow <= 0) return;
        entries.entrySet().stream()
                .sorted(Comparator.comparing(value -> value.getValue().storedAt()))
                .limit(overflow)
                .map(java.util.Map.Entry::getKey)
                .toList()
                .forEach(entries::remove);
    }

    private OffsetDateTime earliest(OffsetDateTime... values) {
        OffsetDateTime result = values[0];
        for (OffsetDateTime value : values) {
            if (value != null && value.isBefore(result)) result = value;
        }
        return result;
    }

    public record Key(
            long tenantId,
            long userId,
            String authorityFingerprint,
            String authorityDecisionRevision,
            String locale,
            String timeZone,
            String mode,
            String deviceClass,
            String viewRevision,
            String catalogRevision,
            String policyRevision,
            String safetyRevision,
            String providerKey,
            Set<String> definitionKeys,
            String requestFingerprint) {

        public Key {
            definitionKeys = definitionKeys == null ? Set.of() : Set.copyOf(definitionKeys);
        }
    }

    private record Entry(
            HomeWidgetProviderContract.BatchResponse response,
            OffsetDateTime freshUntil,
            OffsetDateTime staleUntil,
            OffsetDateTime authorityValidUntil,
            OffsetDateTime storedAt) {
    }
}
