package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Local deterministic-input release harness. It is not a substitute for Wave 6 production canary
 * latency or Web Vitals evidence.
 */
class HomeRuntimeDeterministicLatencyHarnessTest {

    private static final int SAMPLE_COUNT = 1_000;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void oneThousandBrokerSamplesStayWithinTheWaveFourServerBudget() {
        HomeRuntimeProperties properties = properties(true, false);
        HomeRuntimeContext context = TestFixtures.context();
        WidgetProviderPort.Request request = TestFixtures.request("core.work.focus");
        AtomicInteger providerCalls = new AtomicInteger();
        WidgetProviderPort provider = provider(context, request, providerCalls);
        WidgetRuntimeBroker broker = new WidgetRuntimeBroker(
                List.of(provider), new RecipientBoundWidgetCache(properties),
                new ProviderResultValidator(objectMapper, properties), properties,
                new HomeRuntimeTelemetry(new SimpleMeterRegistry()),
                new HomeCanonicalJson(objectMapper), Runnable::run);
        WidgetRuntimeBroker.Revisions revisions = new WidgetRuntimeBroker.Revisions(
                "CLASSIC", "DESKTOP_STANDARD", "view-1", "catalog-1", "policy-1", "safety-1");
        long[] samples = new long[SAMPLE_COUNT];
        int hardFailures = 0;
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            long started = System.nanoTime();
            try {
                assertThat(broker.read(context, revisions, List.of(request))).hasSize(1);
            } catch (RuntimeException failure) {
                hardFailures++;
            }
            samples[index] = System.nanoTime() - started;
        }

        assertThat(hardFailures).isZero();
        assertThat(providerCalls).hasValue(1);
        assertThat(p95Millis(samples)).isLessThanOrEqualTo(1_000.0);
    }

    @Test
    void oneThousandMockMvcSamplesStayWithinTheWaveFourServerBudget() throws Exception {
        HomeRuntimeProperties properties = properties(false, true);
        HomeReadModelService service = mock(HomeReadModelService.class);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HomeReadModelDtos.HomeReadModel model = new HomeReadModelDtos.HomeReadModel(
                2, "CLASSIC", null, null, List.of(), List.of(), now, now.plusSeconds(30),
                false, List.of(), "change-1", "SHADOW");
        when(service.read(any(), eq("CLASSIC"), eq("DESKTOP_STANDARD")))
                .thenReturn(new HomeReadModelDtos.ReadResult(model, "\"change-1\""));
        MockMvc mvc = standaloneSetup(new HomeReadModelController(
                service, mock(HomeWidgetCommandService.class), properties)).build();
        long[] samples = new long[SAMPLE_COUNT];
        int hardFailures = 0;
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            long started = System.nanoTime();
            int status = mvc.perform(get("/v2/home")
                            .queryParam("mode", "CLASSIC")
                            .queryParam("deviceClass", "DESKTOP_STANDARD")
                            .queryParam("timeZone", "Asia/Seoul")
                            .header("X-DWP-Tenant-ID", "71")
                            .header("X-DWP-User-ID", "82")
                            .header("X-DWP-Permissions", "APP.WORK:VIEW")
                            .header("X-DWP-Roles", "MEMBER")
                            .header("X-DWP-Current-Decision-Revision", "decision-17")
                            .header("X-DWP-Current-Revalidate-At",
                                    now.plusMinutes(5).toString())
                            .header("Accept-Language", "ko-KR"))
                    .andReturn().getResponse().getStatus();
            if (status != 200) hardFailures++;
            samples[index] = System.nanoTime() - started;
        }

        assertThat(hardFailures).isZero();
        assertThat(p95Millis(samples)).isLessThanOrEqualTo(1_000.0);
    }

    private WidgetProviderPort provider(
            HomeRuntimeContext context,
            WidgetProviderPort.Request request,
            AtomicInteger calls) {
        return new WidgetProviderPort() {
            @Override
            public String providerKey() {
                return "platform";
            }

            @Override
            public HomeWidgetProviderContract.BatchResponse readBatch(
                    HomeRuntimeContext ignored,
                    List<Request> requests,
                    OffsetDateTime deadline) {
                calls.incrementAndGet();
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                HomeWidgetProviderContract.WidgetResult result =
                        new HomeWidgetProviderContract.WidgetResult(
                                request.instanceId(), request.definition().definitionKey(),
                                request.definition().manifestHash(),
                                request.definition().rendererBindingRevision(),
                                HomeWidgetProviderContract.State.AVAILABLE,
                                new HomeWidgetProviderContract.SourceState(
                                        "PLATFORM_HOME", now, now.plusSeconds(30), now,
                                        null, false, "result-1"),
                                Map.of("count", 1), List.of(), List.of());
                return new HomeWidgetProviderContract.BatchResponse(
                        HomeWidgetProviderContract.SCHEMA_VERSION,
                        context.tenantId(), context.userId(),
                        context.authorityDecisionRevision(), List.of(result));
            }
        };
    }

    private double p95Millis(long[] samples) {
        long[] ordered = Arrays.copyOf(samples, samples.length);
        Arrays.sort(ordered);
        int index = (int) Math.ceil(ordered.length * 0.95) - 1;
        return ordered[Math.max(0, index)] / 1_000_000.0;
    }

    private HomeRuntimeProperties properties(boolean enabled, boolean shadowEnabled) {
        return new HomeRuntimeProperties(
                enabled, shadowEnabled, false,
                Duration.ofMillis(900), Duration.ofMillis(400), Duration.ofSeconds(30),
                Duration.ofMinutes(5), 10_000, 262_144);
    }
}
