package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

@Component
public class HomeRuntimeTelemetry {

    private static final Set<String> PROVIDERS = Set.of(
            "platform", "workplace", "dwaion", "approval", "meeting", "notification", "space",
            "messaging", "people");
    private static final Set<String> OUTCOMES = Set.of(
            "SUCCESS", "FORBIDDEN", "TIMEOUT", "UNAVAILABLE", "MALFORMED");
    private static final Set<String> CACHE_RESULTS = Set.of(
            "HIT", "MISS", "STALE_FALLBACK");
    private static final Set<String> RELEASE_RINGS = Set.of(
            "CONTROL", "INTERNAL", "PILOT", "EARLY_ADOPTER", "GA");
    private static final Set<String> MODES = Set.of("CLASSIC", "FLOW_V1");
    private static final Set<String> SECURITY_SCOPES = Set.of(
            "ACTION", "VERSION", "DEFINITION", "PROVIDER", "MODE", "RING", "GLOBAL");
    private static final Set<String> SECURITY_REASONS = Set.of(
            "FORBIDDEN_EXPOSURE", "CROSS_SCOPE", "AUTHORITY_BYPASS", "UNSAFE_ALLOW",
            "RECEIPT_MISMATCH");

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

    public void read(
            HomeRuntimeRolloutDecision decision,
            String outcome,
            Duration duration) {
        String registry = decision.registryAuthoritative()
                ? "AUTHORITATIVE" : "SHADOW_NON_AUTHORITATIVE";
        meters.counter("dwp.home.runtime.exposure",
                "release_ring", decision.ring().name(),
                "mode", decision.mode(),
                "runtime", decision.state().name(),
                "registry", registry).increment();
        DistributionSummary.builder("dwp.home.runtime.read.duration.ms")
                .publishPercentileHistogram()
                .tag("release_ring", decision.ring().name())
                .tag("mode", decision.mode())
                .tag("runtime", decision.state().name())
                .tag("outcome", boundedOutcome(outcome))
                .register(meters)
                .record(Math.max(0, duration.toNanos() / 1_000_000.0));
    }

    public void provider(
            String releaseRing,
            String provider,
            String outcome,
            Duration duration) {
        String boundedOutcome = providerOutcome(outcome);
        DistributionSummary.builder("dwp.home.provider.duration.ms")
                .publishPercentileHistogram()
                .tag("release_ring", releaseRing)
                .tag("provider", metricProvider(provider))
                .tag("outcome", boundedOutcome)
                .register(meters)
                .record(Math.max(0, duration.toNanos() / 1_000_000.0));
    }

    public void command(
            String releaseRing,
            String provider,
            String outcome,
            Duration duration) {
        String bounded = Set.of(
                "ACCEPTED", "DENIED", "CONFLICT", "REPLAYED", "UNKNOWN",
                "CONTROL_BYPASS", "RECEIPT_MISMATCH").contains(outcome)
                ? outcome : "UNKNOWN";
        String boundedProvider = metricProvider(provider);
        meters.counter("dwp.home.runtime.command",
                "release_ring", releaseRing,
                "provider", boundedProvider,
                "action_class", "LOW_RISK_WRITE",
                "outcome", bounded).increment();
        DistributionSummary.builder("dwp.home.runtime.command.duration.ms")
                .publishPercentileHistogram()
                .tag("release_ring", releaseRing)
                .tag("provider", boundedProvider)
                .tag("action_class", "LOW_RISK_WRITE")
                .tag("outcome", bounded)
                .register(meters)
                .record(Math.max(0, duration.toNanos() / 1_000_000.0));
    }

    public void readFailure(
            HomeRuntimeRolloutDecision.TrustedInput trusted,
            String requestedMode,
            Duration duration) {
        HomeRuntimeRolloutDecision.Ring ring = trusted == null
                ? HomeRuntimeRolloutDecision.Ring.CONTROL : trusted.ring();
        HomeRuntimeRolloutDecision.State state = trusted == null
                ? HomeRuntimeRolloutDecision.State.DISABLED : trusted.state();
        String mode = "CLASSIC".equals(requestedMode) || "FLOW_V1".equals(requestedMode)
                ? requestedMode : "CLASSIC";
        meters.counter("dwp.home.runtime.exposure",
                "release_ring", ring.name(),
                "mode", mode,
                "runtime", state.name(),
                "registry", "SHADOW_NON_AUTHORITATIVE").increment();
        DistributionSummary.builder("dwp.home.runtime.read.duration.ms")
                .publishPercentileHistogram()
                .tag("release_ring", ring.name())
                .tag("mode", mode)
                .tag("runtime", state.name())
                .tag("outcome", "HARD_ERROR")
                .register(meters)
                .record(Math.max(0, duration.toNanos() / 1_000_000.0));
    }

    public void securityViolation(
            String releaseRing,
            String mode,
            String scope,
            String reason) {
        meters.counter(
                "dwp.home.security.violation",
                "release_ring", bounded(releaseRing, RELEASE_RINGS, "CONTROL"),
                "mode", bounded(mode, MODES, "CLASSIC"),
                "scope", bounded(scope, SECURITY_SCOPES, "GLOBAL"),
                "reason", bounded(reason, SECURITY_REASONS, "UNSAFE_ALLOW"))
                .increment();
    }

    public void state(String provider, HomeWidgetProviderContract.State state) {
        meters.counter("dwp.home.runtime.widget.state",
                "provider", provider(provider),
                "state", state.name()).increment();
    }

    public void state(
            String releaseRing,
            String provider,
            HomeWidgetProviderContract.State state,
            String rawReason) {
        String outcome = switch (state) {
            case AVAILABLE, EMPTY -> "SUCCESS";
            case PARTIAL -> "PARTIAL";
            case STALE -> "STALE";
            case FORBIDDEN -> "FORBIDDEN";
            case UNAVAILABLE -> reason(rawReason).equals("TIMEOUT") ? "TIMEOUT" : "HARD_ERROR";
        };
        meters.counter("dwp.home.provider.result",
                "release_ring", releaseRing,
                "provider", metricProvider(provider),
                "outcome", outcome,
                "reason", reason(rawReason)).increment();
    }

    public void appDock(
            HomeRuntimeRolloutDecision decision,
            HomeWidgetProviderContract.State state,
            String rawReason) {
        String outcome = switch (state) {
            case AVAILABLE, EMPTY -> "SUCCESS";
            case PARTIAL -> "PARTIAL";
            case STALE -> "STALE";
            case FORBIDDEN -> "FORBIDDEN";
            case UNAVAILABLE -> reason(rawReason).equals("TIMEOUT")
                    ? "TIMEOUT" : "HARD_ERROR";
        };
        meters.counter("dwp.home.app.dock.state",
                "release_ring", decision.ring().name(),
                "mode", decision.mode(),
                "outcome", outcome,
                "reason", reason(rawReason)).increment();
    }

    public void cache(String provider, String result) {
        meters.counter("dwp.home.runtime.cache",
                "provider", provider(provider),
                "result", CACHE_RESULTS.contains(result) ? result : "unknown").increment();
    }

    private String provider(String value) {
        return PROVIDERS.contains(value) ? value : "unknown";
    }

    private String metricProvider(String value) {
        return switch (provider(value)) {
            case "workplace" -> "WORKPLACE";
            case "dwaion" -> "DWAION";
            case "meeting" -> "MEETINGS";
            case "space" -> "SPACE";
            case "people" -> "HR_EDU";
            default -> "NATIVE";
        };
    }

    private String providerOutcome(String value) {
        return switch (value) {
            case "SUCCESS" -> "SUCCESS";
            case "TIMEOUT" -> "TIMEOUT";
            case "FORBIDDEN" -> "FORBIDDEN";
            default -> "HARD_ERROR";
        };
    }

    private String reason(String value) {
        if (value == null || value.isBlank()) return "NONE";
        String normalized = value.toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("TIMEOUT") || normalized.contains("DEADLINE")) return "TIMEOUT";
        if (normalized.contains("CIRCUIT")) return "CIRCUIT_OPEN";
        if (normalized.contains("STALE")) return "STALE_FALLBACK";
        return "NONE";
    }

    private String boundedOutcome(String value) {
        return Set.of("SUCCESS", "PARTIAL", "HARD_ERROR").contains(value)
                ? value : "HARD_ERROR";
    }

    private String bounded(String value, Set<String> allowed, String fallback) {
        return value != null && allowed.contains(value) ? value : fallback;
    }
}
