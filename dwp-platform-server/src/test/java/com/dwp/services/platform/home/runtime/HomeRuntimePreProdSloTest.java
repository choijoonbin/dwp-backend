package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class HomeRuntimePreProdSloTest {

    private static final int SAMPLES = 1_000;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void producesDeterministicBrokerAndMockMvcLatencyEvidence() throws Exception {
        Measurement broker = measureBroker();
        Measurement mockMvc = measureMockMvc();
        double serverP95 = Math.max(broker.p95Ms(), mockMvc.p95Ms());
        int failures = broker.failures() + mockMvc.failures();
        int sampleCount = broker.sampleCount() + mockMvc.sampleCount();
        double failureRate = (double) failures / sampleCount;

        assertThat(broker.sampleCount()).isEqualTo(SAMPLES);
        assertThat(mockMvc.sampleCount()).isEqualTo(SAMPLES);
        assertThat(failures).isZero();
        assertThat(failureRate).isZero();
        assertThat(serverP95).isLessThanOrEqualTo(1_000.0);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1);
        evidence.put("gate", "W4-PREPROD-RUNTIME-SLO");
        evidence.put("status", "PASS");
        evidence.put("environment", "PRE_PROD_DETERMINISTIC");
        evidence.put("broker", broker.asMap());
        evidence.put("mockMvc", mockMvc.asMap());
        evidence.put("sampleCount", sampleCount);
        evidence.put("serverP95Ms", rounded(serverP95));
        evidence.put("hardFailureCount", failures);
        evidence.put("hardFailureRate", failureRate);
        evidence.put("liveProduction", false);
        evidence.put("wave6WebVitalsPending", true);
        Path output = Path.of("build/reports/wave4/home-v2-runtime-slo.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), evidence);
    }

    private Measurement measureBroker() {
        HomeRuntimeContext context = TestFixtures.context();
        WidgetProviderPort.Request request = TestFixtures.request("core.work.slo");
        OffsetDateTime generated = OffsetDateTime.now(ZoneOffset.UTC);
        AtomicInteger calls = new AtomicInteger();
        WidgetProviderPort provider = new WidgetProviderPort() {
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
                return new HomeWidgetProviderContract.BatchResponse(
                        HomeWidgetProviderContract.SCHEMA_VERSION,
                        context.tenantId(), context.userId(),
                        context.authorityDecisionRevision(),
                        List.of(result(requests.getFirst(), generated)));
            }
        };
        HomeRuntimeProperties properties = properties();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            WidgetRuntimeBroker broker = new WidgetRuntimeBroker(
                    List.of(provider), new RecipientBoundWidgetCache(properties),
                    new ProviderResultValidator(mapper, properties), properties,
                    new HomeRuntimeTelemetry(new SimpleMeterRegistry()),
                    new HomeCanonicalJson(mapper), executor);
            WidgetRuntimeBroker.Revisions revisions = new WidgetRuntimeBroker.Revisions(
                    "CLASSIC", "DESKTOP_STANDARD", "view-1", "catalog-1",
                    "policy-1", "safety-1");
            List<Long> samples = new ArrayList<>(SAMPLES);
            int failures = 0;
            for (int index = 0; index < SAMPLES; index++) {
                long started = System.nanoTime();
                try {
                    List<HomeWidgetProviderContract.WidgetResult> results = broker.read(
                            context, revisions, List.of(request));
                    if (results.size() != 1
                            || results.getFirst().state()
                            != HomeWidgetProviderContract.State.AVAILABLE) {
                        failures++;
                    }
                } catch (RuntimeException failure) {
                    failures++;
                }
                samples.add(System.nanoTime() - started);
            }
            assertThat(calls).hasValue(1);
            return measurement(samples, failures, Map.of("providerInvocations", calls.get()));
        } finally {
            executor.shutdownNow();
        }
    }

    private Measurement measureMockMvc() throws Exception {
        HomeReadModelService service = mock(HomeReadModelService.class);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        HomeReadModelDtos.HomeReadModel model = new HomeReadModelDtos.HomeReadModel(
                2, "CLASSIC", null, null, List.of(), List.of(), now,
                now.plusSeconds(30), false, List.of(), "slo-etag", "SHADOW");
        when(service.read(any(), eq("CLASSIC"), eq("DESKTOP_STANDARD")))
                .thenReturn(new HomeReadModelDtos.ReadResult(model, "\"slo-etag\""));
        MockMvc mvc = standaloneSetup(new HomeReadModelController(
                service, mock(HomeWidgetCommandService.class), properties())).build();
        String revalidateAt = now.plusMinutes(5).toString();
        List<Long> samples = new ArrayList<>(SAMPLES);
        int failures = 0;
        for (int index = 0; index < SAMPLES; index++) {
            long started = System.nanoTime();
            try {
                int status = mvc.perform(get("/v2/home")
                                .queryParam("mode", "CLASSIC")
                                .queryParam("deviceClass", "DESKTOP_STANDARD")
                                .header("X-DWP-Tenant-ID", "71")
                                .header("X-DWP-User-ID", "82")
                                .header("X-DWP-Permissions", "APP.WORK:VIEW")
                                .header("X-DWP-Roles", "MEMBER")
                                .header("X-DWP-Current-Decision-Revision", "decision-17")
                                .header("X-DWP-Current-Revalidate-At", revalidateAt))
                        .andReturn().getResponse().getStatus();
                if (status != 200) failures++;
            } catch (RuntimeException failure) {
                failures++;
            }
            samples.add(System.nanoTime() - started);
        }
        return measurement(samples, failures, Map.of());
    }

    private HomeWidgetProviderContract.WidgetResult result(
            WidgetProviderPort.Request request,
            OffsetDateTime generated) {
        return new HomeWidgetProviderContract.WidgetResult(
                request.instanceId(), request.definition().definitionKey(),
                request.definition().manifestHash(),
                request.definition().rendererBindingRevision(),
                HomeWidgetProviderContract.State.AVAILABLE,
                new HomeWidgetProviderContract.SourceState(
                        "PLATFORM_HOME", generated, generated.plusSeconds(30), generated,
                        null, false, "result-1"),
                Map.of("count", 1), List.of(), List.of());
    }

    private Measurement measurement(
            List<Long> nanoseconds,
            int failures,
            Map<String, Object> details) {
        nanoseconds.sort(Comparator.naturalOrder());
        long p95 = nanoseconds.get((int) Math.ceil(nanoseconds.size() * 0.95) - 1);
        long maximum = nanoseconds.getLast();
        return new Measurement(
                nanoseconds.size(), failures, p95 / 1_000_000.0,
                maximum / 1_000_000.0, details);
    }

    private HomeRuntimeProperties properties() {
        return new HomeRuntimeProperties(
                true, false, false, Duration.ofMillis(900), Duration.ofMillis(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);
    }

    private double rounded(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }

    private record Measurement(
            int sampleCount,
            int failures,
            double p95Ms,
            double maximumMs,
            Map<String, Object> details) {

        Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("sampleCount", sampleCount);
            value.put("p95Ms", Math.round(p95Ms * 1_000.0) / 1_000.0);
            value.put("maximumMs", Math.round(maximumMs * 1_000.0) / 1_000.0);
            value.put("hardFailureCount", failures);
            value.putAll(details);
            return value;
        }
    }
}
