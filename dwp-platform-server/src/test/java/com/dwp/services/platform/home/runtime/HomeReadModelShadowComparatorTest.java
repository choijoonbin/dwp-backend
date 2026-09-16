package com.dwp.services.platform.home.runtime;

import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeReadModelShadowComparatorTest {

    private final HomeReadModelShadowComparator comparator =
            new HomeReadModelShadowComparator(
                    new HomeCanonicalJson(new ObjectMapper().findAndRegisterModules()));

    @Test
    void semanticComparatorSeparatesFreshnessFromStructuralMismatch() {
        HomeReadModelShadowComparator.Projection baseline = projection(
                List.of("core.workspace.daily-brief:1.0.0:binding:AVAILABLE"),
                List.of("core.workspace.daily-brief:FRESH"));
        HomeReadModelShadowComparator.Projection transientProjection = projection(
                baseline.widgets(), List.of("core.workspace.daily-brief:STALE"));
        HomeReadModelShadowComparator.Projection authorityMismatch = projection(
                List.of("core.workspace.daily-brief:1.0.0:binding:FORBIDDEN"),
                baseline.freshnessClasses());

        assertThat(comparator.compare(baseline, baseline).outcome())
                .isEqualTo(HomeReadModelShadowComparator.ShadowOutcome.MATCH);
        assertThat(comparator.compare(baseline, transientProjection).outcome())
                .isEqualTo(HomeReadModelShadowComparator.ShadowOutcome.EXPECTED_TRANSIENT);
        assertThat(comparator.compare(baseline, authorityMismatch).reasons())
                .contains(HomeReadModelShadowComparator.ShadowReason.AUTHORITY);
    }

    @Test
    void shadowReceiptAcceptsOnlyBoundedStrictJsonAndRecordsNoIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HomeShadowReceiptController controller = new HomeShadowReceiptController(
                new ObjectMapper().findAndRegisterModules(),
                Validation.buildDefaultValidatorFactory().getValidator(), registry);
        byte[] valid = ("{\"schemaVersion\":1,\"outcome\":\"MATCH\","
                + "\"reasons\":[\"MATCH\"],\"mismatchCount\":0,"
                + "\"homeMode\":\"CLASSIC\",\"deviceClass\":\"DESKTOP_STANDARD\","
                + "\"runtimeState\":\"SHADOW_COMPARE\",\"rolloutRing\":\"CONTROL\","
                + "\"rolloutRevision\":\"rollout-17\"}")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(controller.record(
                "SHADOW_COMPARE", "CONTROL", "rollout-17", valid)
                .getStatusCode().value()).isEqualTo(202);
        assertThat(registry.get("dwp.home.runtime.shadow.compare")
                .tags("mode", "CLASSIC", "device", "DESKTOP_STANDARD",
                        "outcome", "MATCH", "reason", "MATCH", "ring", "CONTROL")
                .counter().count()).isEqualTo(1);
        assertThatThrownBy(() -> controller.record(
                "SHADOW_COMPARE", "CONTROL", "rollout-17",
                (new String(valid, StandardCharsets.UTF_8)
                .replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"))
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> controller.record(
                "SHADOW_COMPARE", "CONTROL", "rollout-17",
                (new String(valid, StandardCharsets.UTF_8)
                .replace("\"rolloutRevision\":\"rollout-17\"",
                        "\"rolloutRevision\":\"rollout-17\",\"userId\":82"))
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(RuntimeException.class);
    }

    private HomeReadModelShadowComparator.Projection projection(
            List<String> widgets,
            List<String> freshness) {
        return new HomeReadModelShadowComparator.Projection(
                "CLASSIC", "DEFAULT", false, "layout-1",
                List.of("group:WORK_START", "app:work:AVAILABLE:none"),
                widgets, List.of("source:/work"), List.of(), List.of(), freshness);
    }
}
