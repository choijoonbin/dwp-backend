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
                "LCP", 1250.0, 25.0, "v4-1", "good", "navigate", "hcm.home"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(registry.get("dwp.frontend.web_vital")
                .tags("metric", "LCP", "rating", "good", "route.group", "hcm.home")
                .summary().count()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownMetricNames() {
        var response = controller.ingest(new WebVitalsController.WebVitalRequest(
                "TTFB", 25.0, 1.0, "v4-2", "good", "navigate", "home"));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(registry.find("dwp.frontend.web_vital").summary()).isNull();
    }
}
