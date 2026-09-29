package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

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
    void shadowReceiptAcceptsOnlyBoundedStrictJsonAndRecordsNoIdentifiers() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger admissions = new AtomicInteger();
        HomeShadowReceiptAdmissionGuard admission = (tenantId, userId, revision, request) ->
                admissions.getAndIncrement() == 0
                        ? HomeShadowReceiptAdmissionGuard.Admission.ADMITTED
                        : HomeShadowReceiptAdmissionGuard.Admission.DUPLICATE;
        MockMvc mvc = mvc(registry, readModels("rollout-17"), admission);
        byte[] valid = ("{\"schemaVersion\":1,\"outcome\":\"MATCH\","
                + "\"reasons\":[\"MATCH\"],\"mismatchCount\":0,"
                + "\"homeMode\":\"CLASSIC\",\"deviceClass\":\"DESKTOP_STANDARD\","
                + "\"runtimeState\":\"SHADOW_COMPARE\",\"rolloutRing\":\"CONTROL\","
                + "\"rolloutRevision\":\"rollout-17\"}")
                .getBytes(StandardCharsets.UTF_8);

        mvc.perform(receipt(valid, "rollout-17")).andExpect(status().isAccepted());
        mvc.perform(receipt(valid, "rollout-17")).andExpect(status().isAccepted());
        assertThat(registry.get("dwp.home.shadow.compare")
                .tags("mode", "CLASSIC", "device_class", "DESKTOP",
                        "outcome", "MATCH", "reason", "MATCH",
                        "release_ring", "CONTROL")
                .counter().count()).isEqualTo(1);
        mvc.perform(receipt((new String(valid, StandardCharsets.UTF_8)
                        .replace("\"schemaVersion\":1",
                                "\"schemaVersion\":1,\"schemaVersion\":1"))
                        .getBytes(StandardCharsets.UTF_8), "rollout-17"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(
                        ErrorCode.INVALID_INPUT_VALUE.getCode()));
        mvc.perform(receipt((new String(valid, StandardCharsets.UTF_8)
                        .replace("\"rolloutRevision\":\"rollout-17\"",
                                "\"rolloutRevision\":\"rollout-17\",\"userId\":82"))
                        .getBytes(StandardCharsets.UTF_8), "rollout-17"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(
                        ErrorCode.INVALID_INPUT_VALUE.getCode()));
    }

    @Test
    void shadowReceiptValidatesTheDerivedCurrentDecisionInsteadOfTheRawGatewayRevision()
            throws Exception {
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
        MockMvc mvc = mvc(
                new SimpleMeterRegistry(),
                readModels,
                (tenantId, userId, revision, request) ->
                        HomeShadowReceiptAdmissionGuard.Admission.ADMITTED);
        byte[] body = ("{\"schemaVersion\":1,\"outcome\":\"MATCH\","
                + "\"reasons\":[\"MATCH\"],\"mismatchCount\":0,"
                + "\"homeMode\":\"CLASSIC\",\"deviceClass\":\"MOBILE_STANDARD\","
                + "\"runtimeState\":\"SHADOW_COMPARE\",\"rolloutRing\":\"CONTROL\","
                + "\"rolloutRevision\":\"derived-decision-revision\"}")
                .getBytes(StandardCharsets.UTF_8);

        mvc.perform(receipt(body, "raw-gateway-revision"))
                .andExpect(status().isAccepted());
        ArgumentCaptor<HomeRuntimeRolloutDecision.TrustedInput> trusted =
                ArgumentCaptor.forClass(HomeRuntimeRolloutDecision.TrustedInput.class);
        verify(readModels).resolveCurrentDecision(any(), trusted.capture(), eq("CLASSIC"));
        assertThat(trusted.getValue().revision()).isEqualTo("raw-gateway-revision");
    }

    @Test
    void shadowReceiptRejectsJsonNullAsInvalidInput() throws Exception {
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        MockMvc mvc = mvc(
                new SimpleMeterRegistry(),
                readModels,
                (tenantId, userId, revision, request) ->
                        HomeShadowReceiptAdmissionGuard.Admission.ADMITTED);

        mvc.perform(receipt("null".getBytes(StandardCharsets.UTF_8), "rollout-17"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(
                        ErrorCode.INVALID_INPUT_VALUE.getCode()));
        verifyNoInteractions(readModels);
    }

    @Test
    void shadowReceiptBoundsUniqueSamplesPerRecipientAndDecisionWindow() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger admissions = new AtomicInteger();
        HomeShadowReceiptAdmissionGuard admission = (tenantId, userId, revision, request) ->
                admissions.incrementAndGet() <= 60
                        ? HomeShadowReceiptAdmissionGuard.Admission.ADMITTED
                        : HomeShadowReceiptAdmissionGuard.Admission.RATE_LIMITED;
        MockMvc mvc = mvc(registry, readModels("rollout-rate-17"), admission);

        for (int mismatchCount = 1; mismatchCount <= 61; mismatchCount++) {
            byte[] body = ("{\"schemaVersion\":1,\"outcome\":\"MISMATCH\","
                    + "\"reasons\":[\"AUTHORITY\"],\"mismatchCount\":" + mismatchCount + ","
                    + "\"homeMode\":\"CLASSIC\",\"deviceClass\":\"DESKTOP_STANDARD\","
                    + "\"runtimeState\":\"SHADOW_COMPARE\",\"rolloutRing\":\"CONTROL\","
                    + "\"rolloutRevision\":\"rollout-rate-17\"}")
                    .getBytes(StandardCharsets.UTF_8);

            mvc.perform(receipt(body, "rollout-rate-17"))
                    .andExpect(status().isAccepted());
        }

        assertThat(registry.get("dwp.home.shadow.compare")
                .tags("mode", "CLASSIC", "device_class", "DESKTOP",
                        "outcome", "MISMATCH", "reason", "AUTHORITY",
                        "release_ring", "CONTROL")
                .counter().count()).isEqualTo(60);
    }

    private MockMvc mvc(
            SimpleMeterRegistry registry,
            HomeReadModelService readModels,
            HomeShadowReceiptAdmissionGuard admission) {
        HomeShadowReceiptController controller = new HomeShadowReceiptController(
                new ObjectMapper().findAndRegisterModules(),
                Validation.buildDefaultValidatorFactory().getValidator(),
                registry,
                readModels,
                admission);
        return standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    private HomeReadModelService readModels(String revision) {
        HomeReadModelService readModels = mock(HomeReadModelService.class);
        when(readModels.resolveCurrentDecision(any(), any(), eq("CLASSIC"))).thenReturn(
                new HomeRuntimeRolloutDecision(
                        HomeRuntimeRolloutDecision.State.SHADOW_COMPARE,
                        "CLASSIC",
                        HomeRuntimeRolloutDecision.Ring.CONTROL,
                        revision,
                        false,
                        Set.of(), Set.of(), Set.of(),
                        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5)));
        return readModels;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder receipt(
            byte[] body,
            String gatewayRevision) {
        return post("/v2/home/shadow-receipts")
                .header("X-DWP-Tenant-ID", "71")
                .header("X-DWP-User-ID", "82")
                .header("X-DWP-Permissions", "APP.WORK:VIEW")
                .header("X-DWP-Roles", "MEMBER")
                .header("X-DWP-Group-Refs", "team-a")
                .header("X-DWP-Current-Decision-Revision", "authority-17")
                .header("X-DWP-Current-Revalidate-At",
                        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString())
                .header("X-DWP-Home-Runtime-State", "SHADOW_COMPARE")
                .header("X-DWP-Home-Rollout-Ring", "CONTROL")
                .header("X-DWP-Home-Rollout-Revision", gatewayRevision)
                .header("Accept-Language", "ko-KR")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
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
