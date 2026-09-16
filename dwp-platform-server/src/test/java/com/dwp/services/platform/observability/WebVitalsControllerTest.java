package com.dwp.services.platform.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebVitalsControllerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final WebVitalsController controller = new WebVitalsController(registry);

    @Test
    void recordsAnAcceptedMetricWithBoundedTags() {
        var response = controller.ingest("SHADOW_COMPARE", "CONTROL",
                new WebVitalsController.WebVitalRequest(
                "LCP", 1250.0, 25.0, "v4-1", "good", "navigate", "hcm.home",
                "CLASSIC", "SHADOW_COMPARE", "CONTROL", "DESKTOP_STANDARD"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(registry.get("dwp.home.web.vital.lcp.ms")
                .tags("release_ring", "CONTROL", "mode", "CLASSIC",
                        "runtime", "SHADOW_COMPARE", "device_class", "DESKTOP")
                .summary().count()).isEqualTo(1);
        assertThat(registry.get("dwp.home.web.vital.sample")
                .tags("release_ring", "CONTROL", "mode", "CLASSIC",
                        "runtime", "SHADOW_COMPARE", "device_class", "DESKTOP",
                        "vital_name", "LCP")
                .counter().count()).isEqualTo(1);
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
        var accepted = controller.ingest("READ_ONLY_ACTIVE", "PILOT",
                new WebVitalsController.WebVitalRequest(
                "CLS", 0.08, 0.01, "v4-3", "good", "navigate", "home",
                "FLOW_V1", "READ_ONLY_ACTIVE", "PILOT", "MOBILE_STANDARD"));
        var rejected = controller.ingest(new WebVitalsController.WebVitalRequest(
                "INP", 175.0, 10.0, "v4-4", "good", "navigate", "home"));

        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        assertThat(rejected.getStatusCode().value()).isEqualTo(422);
        assertThat(registry.get("dwp.home.web.vital.cls.ratio")
                .tags("release_ring", "PILOT", "mode", "FLOW_V1",
                        "runtime", "READ_ONLY_ACTIVE", "device_class", "MOBILE")
                .summary().count()).isEqualTo(1);
        assertThat(registry.find("dwp.frontend.web_vital.duration").summary()).isNull();
    }

    @Test
    void rejectsClientHomeCohortClaimsThatDoNotMatchTrustedGatewayHeaders() {
        var response = controller.ingest("READ_ONLY_ACTIVE", "PILOT",
                new WebVitalsController.WebVitalRequest(
                        "INP", 170.0, 5.0, "v4-spoof", "good", "navigate", "home",
                        "FLOW_V1", "COMMAND_CANARY", "GA", "DESKTOP_WIDE"));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(registry.find("dwp.home.web.vital.inp.ms").summary()).isNull();
        assertThat(registry.find("dwp.home.web.vital.sample").counter()).isNull();
    }
}
