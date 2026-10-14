package com.dwp.services.platform.hrisconfiguration.explorer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public final class HrisExplorerRuntimeControls {

    private final Duration providerTimeout;
    private final Duration providerFreshness;
    private final int maxProviderConcurrency;

    public HrisExplorerRuntimeControls(
            @Value("${dwp.hris.explorer.provider-timeout:PT0.5S}") Duration providerTimeout,
            @Value("${dwp.hris.explorer.provider-freshness:PT5M}") Duration providerFreshness,
            @Value("${dwp.hris.explorer.max-provider-concurrency:5}") int maxProviderConcurrency) {
        if (providerTimeout == null || providerTimeout.isNegative() || providerTimeout.isZero()
                || providerTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Explorer provider timeout must be in (0,5s]");
        }
        if (providerFreshness == null || providerFreshness.isNegative()
                || providerFreshness.isZero()
                || providerFreshness.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Explorer provider freshness must be in (0,1h]");
        }
        if (maxProviderConcurrency < 1 || maxProviderConcurrency > 5) {
            throw new IllegalArgumentException("Explorer provider concurrency must be in [1,5]");
        }
        this.providerTimeout = providerTimeout;
        this.providerFreshness = providerFreshness;
        this.maxProviderConcurrency = maxProviderConcurrency;
    }

    public Duration providerTimeout() {
        return providerTimeout;
    }

    public Duration providerFreshness() {
        return providerFreshness;
    }

    public int maxProviderConcurrency() {
        return maxProviderConcurrency;
    }
}
