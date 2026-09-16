package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        assertThat(controller.record(
                "SHADOW_COMPARE", "CONTROL", "rollout-17", valid)
                .getStatusCode().value()).isEqualTo(202);
        assertThat(registry.get("dwp.home.shadow.compare")
                .tags("mode", "CLASSIC", "device_class", "DESKTOP",
                        "outcome", "MATCH", "reason", "MATCH",
                        "release_ring", "CONTROL")
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

    @Test
    void shadowReceiptValidatesTheDerivedCurrentDecisionInsteadOfTheRawGatewayRevision() {
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        when(readModels.resolveCurrentDecision(any(), any(), eq("CLASSIC"))).thenReturn(
                new HomeRuntimeRolloutDecision(
                        HomeRuntimeRolloutDecision.State.SHADOW_COMPARE,
                        "CLASSIC",
                        HomeRuntimeRolloutDecision.Ring.CONTROL,
                        "derived-decision-revision",
                        false,
                        Set.of(), Set.of(), Set.of(),
                        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5)));
        HomeShadowReceiptController controller = new HomeShadowReceiptController(
                new ObjectMapper().findAndRegisterModules(),
                Validation.buildDefaultValidatorFactory().getValidator(),
                new SimpleMeterRegistry(),
                readModels);
        byte[] body = ("{\"schemaVersion\":1,\"outcome\":\"MATCH\","
                + "\"reasons\":[\"MATCH\"],\"mismatchCount\":0,"
                + "\"homeMode\":\"CLASSIC\",\"deviceClass\":\"MOBILE_STANDARD\","
                + "\"runtimeState\":\"SHADOW_COMPARE\",\"rolloutRing\":\"CONTROL\","
                + "\"rolloutRevision\":\"derived-decision-revision\"}")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(controller.record(
                71L, 82L, null, "APP.WORK:VIEW", "MEMBER", "team-a",
                "authority-17", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString(),
                "SHADOW_COMPARE", "CONTROL", "raw-gateway-revision", "ko-KR", body)
                .getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void shadowReceiptRejectsJsonNullAsInvalidInput() {
        HomeShadowReceiptController controller = new HomeShadowReceiptController(
                new ObjectMapper().findAndRegisterModules(),
                Validation.buildDefaultValidatorFactory().getValidator(),
                new SimpleMeterRegistry());

        assertThatThrownBy(() -> controller.record(
                "SHADOW_COMPARE", "CONTROL", "rollout-17",
                "null".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void shadowReceiptBoundsUniqueSamplesPerRecipientAndDecisionWindow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HomeShadowReceiptController controller = new HomeShadowReceiptController(
                new ObjectMapper().findAndRegisterModules(),
                Validation.buildDefaultValidatorFactory().getValidator(), registry);

        for (int mismatchCount = 1; mismatchCount <= 61; mismatchCount++) {
            byte[] body = ("{\"schemaVersion\":1,\"outcome\":\"MISMATCH\","
                    + "\"reasons\":[\"AUTHORITY\"],\"mismatchCount\":" + mismatchCount + ","
                    + "\"homeMode\":\"CLASSIC\",\"deviceClass\":\"DESKTOP_STANDARD\","
                    + "\"runtimeState\":\"SHADOW_COMPARE\",\"rolloutRing\":\"CONTROL\","
                    + "\"rolloutRevision\":\"rollout-rate-17\"}")
                    .getBytes(StandardCharsets.UTF_8);

            assertThat(controller.record(
                    "SHADOW_COMPARE", "CONTROL", "rollout-rate-17", body)
                    .getStatusCode().value()).isEqualTo(202);
        }

        assertThat(registry.get("dwp.home.shadow.compare")
                .tags("mode", "CLASSIC", "device_class", "DESKTOP",
                        "outcome", "MISMATCH", "reason", "AUTHORITY",
                        "release_ring", "CONTROL")
                .counter().count()).isEqualTo(60);
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
