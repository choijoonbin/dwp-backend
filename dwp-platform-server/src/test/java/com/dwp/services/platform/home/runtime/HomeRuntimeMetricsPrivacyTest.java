package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

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
}
