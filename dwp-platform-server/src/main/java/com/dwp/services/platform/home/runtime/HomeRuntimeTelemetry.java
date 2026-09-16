package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

@Component
public class HomeRuntimeTelemetry {

    private static final Set<String> PROVIDERS = Set.of(
            "platform", "approval", "meeting", "notification", "space", "messaging", "people");
    private static final Set<String> OUTCOMES = Set.of(
            "SUCCESS", "FORBIDDEN", "TIMEOUT", "UNAVAILABLE", "MALFORMED");
    private static final Set<String> CACHE_RESULTS = Set.of(
            "HIT", "MISS", "STALE_FALLBACK");

    private final MeterRegistry meters;

    public HomeRuntimeTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    public void provider(String provider, String outcome, Duration duration) {
        Timer.builder("dwp.home.runtime.provider.duration")
                .tag("provider", provider(provider))
                .tag("outcome", OUTCOMES.contains(outcome) ? outcome : "unknown")
                .register(meters)
                .record(duration);
    }

    public void state(String provider, HomeWidgetProviderContract.State state) {
        meters.counter("dwp.home.runtime.widget.state",
                "provider", provider(provider),
                "state", state.name()).increment();
    }

    public void cache(String provider, String result) {
        meters.counter("dwp.home.runtime.cache",
                "provider", provider(provider),
                "result", CACHE_RESULTS.contains(result) ? result : "unknown").increment();
    }

    private String provider(String value) {
        return PROVIDERS.contains(value) ? value : "unknown";
    }
}
