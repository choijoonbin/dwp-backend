package com.dwp.services.platform.home.runtime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class HomeRuntimeProperties {

    private final boolean enabled;
    private final boolean shadowEnabled;
    private final boolean commandsEnabled;
    private final Duration overallDeadline;
    private final Duration providerTimeout;
    private final Duration cacheTtl;
    private final Duration staleIfError;
    private final int maximumCacheEntries;
    private final int maximumProviderPayloadBytes;

    @Autowired
    public HomeRuntimeProperties(
            @Value("${dwp.platform.home-runtime.enabled:false}") boolean enabled,
            @Value("${dwp.platform.home-runtime.shadow-enabled:true}") boolean shadowEnabled,
            @Value("${dwp.platform.home-runtime.commands-enabled:false}") boolean commandsEnabled,
            @Value("${dwp.platform.home-runtime.overall-deadline:PT0.9S}") Duration overallDeadline,
            @Value("${dwp.platform.home-runtime.provider-timeout:PT0.4S}") Duration providerTimeout,
            @Value("${dwp.platform.home-runtime.cache-ttl:PT30S}") Duration cacheTtl,
            @Value("${dwp.platform.home-runtime.stale-if-error:PT5M}") Duration staleIfError,
            @Value("${dwp.platform.home-runtime.maximum-cache-entries:10000}") int maximumCacheEntries,
            @Value("${dwp.platform.home-runtime.maximum-provider-payload-bytes:262144}")
            int maximumProviderPayloadBytes) {
        this.enabled = enabled;
        this.shadowEnabled = shadowEnabled;
        this.commandsEnabled = commandsEnabled;
        this.overallDeadline = bounded(overallDeadline, Duration.ofMillis(100), Duration.ofSeconds(1));
        this.providerTimeout = bounded(providerTimeout, Duration.ofMillis(50), this.overallDeadline);
        this.cacheTtl = bounded(cacheTtl, Duration.ofSeconds(1), Duration.ofMinutes(2));
        this.staleIfError = bounded(staleIfError, Duration.ZERO, Duration.ofMinutes(30));
        this.maximumCacheEntries = Math.max(100, Math.min(maximumCacheEntries, 100_000));
        this.maximumProviderPayloadBytes = Math.max(
                16_384, Math.min(maximumProviderPayloadBytes, 1_048_576));
    }

    /** Test/source compatibility constructor. Commands remain fail-closed unless explicitly enabled. */
    public HomeRuntimeProperties(
            boolean enabled,
            boolean shadowEnabled,
            Duration overallDeadline,
            Duration providerTimeout,
            Duration cacheTtl,
            Duration staleIfError,
            int maximumCacheEntries,
            int maximumProviderPayloadBytes) {
        this(enabled, shadowEnabled, false, overallDeadline, providerTimeout, cacheTtl,
                staleIfError, maximumCacheEntries, maximumProviderPayloadBytes);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean shadowEnabled() {
        return shadowEnabled;
    }

    public boolean commandsEnabled() {
        return enabled && commandsEnabled;
    }

    public Duration overallDeadline() {
        return overallDeadline;
    }

    public Duration providerTimeout() {
        return providerTimeout;
    }

    public Duration cacheTtl() {
        return cacheTtl;
    }

    public Duration staleIfError() {
        return staleIfError;
    }

    public int maximumCacheEntries() {
        return maximumCacheEntries;
    }

    public int maximumProviderPayloadBytes() {
        return maximumProviderPayloadBytes;
    }

    private Duration bounded(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.isNegative() || value.compareTo(minimum) < 0) return minimum;
        return value.compareTo(maximum) > 0 ? maximum : value;
    }
}
