package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HomeRuntimeMetricsPrivacyTest {

    @Test
    void unknownDimensionsAreCollapsedWithoutRecipientOrPayloadTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HomeRuntimeTelemetry telemetry = new HomeRuntimeTelemetry(registry);
        String sensitiveProvider = "tenant-71:user-82";
        String sensitiveOutcome = "payload-title-secret";
        String sensitiveCacheResult = "person-90000001";

        telemetry.provider(sensitiveProvider, sensitiveOutcome, Duration.ofMillis(3));
        telemetry.cache(sensitiveProvider, sensitiveCacheResult);
        telemetry.state(sensitiveProvider, HomeWidgetProviderContract.State.AVAILABLE);

        List<Meter.Id> ids = registry.getMeters().stream().map(Meter::getId).toList();
        assertThat(ids).hasSize(3);
        assertThat(ids).allSatisfy(id -> {
            assertThat(id.getTags()).extracting(Tag::getKey)
                    .allMatch(key -> key.equals("provider")
                            || key.equals("outcome")
                            || key.equals("result")
                            || key.equals("state"));
            assertThat(id.getTags()).extracting(Tag::getValue)
                    .doesNotContain(sensitiveProvider, sensitiveOutcome, sensitiveCacheResult,
                            "71", "82");
            assertThat(id.getTag("provider")).isEqualTo("unknown");
        });
        assertThat(registry.get("dwp.home.runtime.provider.duration").timer().getId()
                .getTag("outcome")).isEqualTo("unknown");
        assertThat(registry.get("dwp.home.runtime.cache").counter().getId()
                .getTag("result")).isEqualTo("unknown");
    }

    @Test
    void emitsTheWaveSixMetricNamesAndBoundedDimensionContract() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HomeRuntimeTelemetry telemetry = new HomeRuntimeTelemetry(registry);
        HomeRuntimeRolloutDecision decision = new HomeRuntimeRolloutDecision(
                HomeRuntimeRolloutDecision.State.READ_ONLY_ACTIVE,
                "CLASSIC",
                HomeRuntimeRolloutDecision.Ring.INTERNAL,
                "revision-17",
                false,
                Set.of("platform"), Set.of("core.workspace.daily-brief"), Set.of(),
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));

        telemetry.read(decision, "SUCCESS", Duration.ofMillis(21));
        telemetry.provider("INTERNAL", "meeting", "SUCCESS", Duration.ofMillis(8));
        telemetry.state(
                "INTERNAL", "meeting", HomeWidgetProviderContract.State.STALE,
                "PROVIDER_STALE_FALLBACK");
        telemetry.appDock(
                decision,
                HomeWidgetProviderContract.State.AVAILABLE,
                null);
        telemetry.command("INTERNAL", "platform", "ACCEPTED", Duration.ofMillis(13));
        telemetry.readFailure(
                new HomeRuntimeRolloutDecision.TrustedInput(
                        HomeRuntimeRolloutDecision.State.READ_ONLY_ACTIVE,
                        HomeRuntimeRolloutDecision.Ring.INTERNAL,
                        "rollout-17"),
                "CLASSIC",
                Duration.ofMillis(34));

        assertThat(registry.get("dwp.home.runtime.exposure").counter().count()).isEqualTo(2);
        assertThat(registry.find("dwp.home.runtime.read.duration.ms").summaries()
                .stream().mapToLong(summary -> summary.count()).sum()).isEqualTo(2);
        assertThat(registry.get("dwp.home.runtime.read.duration.ms")
                .tag("outcome", "HARD_ERROR").summary().count()).isEqualTo(1);
        assertThat(registry.get("dwp.home.runtime.read.duration.ms")
                .tag("outcome", "SUCCESS").summary().getId()
                .getBaseUnit()).isNull();
        assertThat(registry.get("dwp.home.provider.duration.ms")
                .tag("provider", "MEETINGS").summary().count()).isEqualTo(1);
        assertThat(registry.get("dwp.home.provider.duration.ms").summary().getId()
                .getBaseUnit()).isNull();
        assertThat(registry.get("dwp.home.provider.result")
                .tags("provider", "MEETINGS", "outcome", "STALE",
                        "reason", "STALE_FALLBACK").counter().count()).isEqualTo(1);
        assertThat(registry.get("dwp.home.app.dock.state")
                .tags("release_ring", "INTERNAL", "mode", "CLASSIC",
                        "outcome", "SUCCESS", "reason", "NONE")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("dwp.home.runtime.command")
                .tags("provider", "NATIVE", "action_class", "LOW_RISK_WRITE",
                        "outcome", "ACCEPTED").counter().count()).isEqualTo(1);
        assertThat(registry.get("dwp.home.runtime.command.duration.ms").summary().getId()
                .getBaseUnit()).isNull();
    }

    @Test
    void securityViolationCollapsesUntrustedDimensionsWithoutIdentityLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HomeRuntimeTelemetry telemetry = new HomeRuntimeTelemetry(registry);

        telemetry.securityViolation(
                "tenant-71", "user-82", "decision-17", "payload-title-secret");

        Meter.Id id = registry.get("dwp.home.security.violation").counter().getId();
        assertThat(id.getTags()).extracting(Tag::getKey)
                .containsExactly("mode", "reason", "release_ring", "scope");
        assertThat(id.getTag("release_ring")).isEqualTo("CONTROL");
        assertThat(id.getTag("mode")).isEqualTo("CLASSIC");
        assertThat(id.getTag("scope")).isEqualTo("GLOBAL");
        assertThat(id.getTag("reason")).isEqualTo("UNSAFE_ALLOW");
        assertThat(id.getTags()).extracting(Tag::getValue)
                .doesNotContain("tenant-71", "user-82", "decision-17", "payload-title-secret");
        assertThat(registry.get("dwp.home.security.violation").counter().count()).isEqualTo(1);
    }

    @Test
    void mzIsAStableBoundedTelemetryMode() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HomeRuntimeTelemetry telemetry = new HomeRuntimeTelemetry(registry);

        telemetry.securityViolation("INTERNAL", "MZ_V1", "MODE", "UNSAFE_ALLOW");

        assertThat(registry.get("dwp.home.security.violation").counter().getId()
                .getTag("mode")).isEqualTo("MZ_V1");
    }
}
