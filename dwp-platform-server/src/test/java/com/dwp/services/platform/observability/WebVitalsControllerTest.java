package com.dwp.services.platform.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebVitalsControllerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final WebVitalsController controller = new WebVitalsController(registry);

    @Test
    void recordsAnAcceptedMetricWithBoundedTags() {
        var response = controller.ingest(new WebVitalsController.WebVitalRequest(
                "LCP", 1250.0, 25.0, "v4-1", "good", "navigate", "hcm.home",
                "CLASSIC", "SHADOW_COMPARE", "CONTROL", "DESKTOP_STANDARD"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(registry.get("dwp.frontend.web_vital.duration")
                .tags("metric", "LCP", "rating", "good", "route.group", "hcm.home",
                        "home.mode", "CLASSIC", "home.runtime", "SHADOW_COMPARE",
                        "rollout.ring", "CONTROL", "device.class", "DESKTOP_STANDARD")
                .summary().count()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownMetricNames() {
        var response = controller.ingest(new WebVitalsController.WebVitalRequest(
                "TTFB", 25.0, 1.0, "v4-2", "good", "navigate", "home"));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(registry.find("dwp.frontend.web_vital.duration").summary()).isNull();
    }

    @Test
    void recordsClsAsDimensionlessAndRejectsIncompleteHomeDimensions() {
        var accepted = controller.ingest(new WebVitalsController.WebVitalRequest(
                "CLS", 0.08, 0.01, "v4-3", "good", "navigate", "home",
                "FLOW_V1", "READ_ONLY_ACTIVE", "PILOT", "MOBILE_STANDARD"));
        var rejected = controller.ingest(new WebVitalsController.WebVitalRequest(
                "INP", 175.0, 10.0, "v4-4", "good", "navigate", "home"));

        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        assertThat(rejected.getStatusCode().value()).isEqualTo(422);
        assertThat(registry.get("dwp.frontend.web_vital.cls").summary().getId().getBaseUnit())
                .isEqualTo("1");
        assertThat(registry.find("dwp.frontend.web_vital.duration").summary()).isNull();
    }
}
