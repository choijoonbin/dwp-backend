package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.dwp.services.platform.widgetregistry.WidgetCatalogService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetRuntimeBrokerIsolationSecurityTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void oneUnavailableProviderCannotEraseASuccessfulSiblingResult() {
        HomeRuntimeContext context = TestFixtures.context();
        WidgetProviderPort.Request platformRequest = request(
                "core.work.focus", "APP.WORK", "home.focus");
        WidgetProviderPort.Request meetingRequest = request(
                "meetings.next-prep", "APP.MEETINGS", "home.meeting-prep");
        WidgetProviderPort platform = new FixedProvider(
                "platform", response(context, platformRequest));
        WidgetProviderPort meeting = new FailingProvider("meeting");
        HomeRuntimeProperties properties = properties();
        WidgetRuntimeBroker broker = new WidgetRuntimeBroker(
                List.of(platform, meeting), new RecipientBoundWidgetCache(properties),
                new ProviderResultValidator(mapper, properties), properties,
                new HomeRuntimeTelemetry(new SimpleMeterRegistry()),
                new HomeCanonicalJson(mapper), executor);

        List<HomeWidgetProviderContract.WidgetResult> results = broker.read(
                context,
                new WidgetRuntimeBroker.Revisions(
                        "CLASSIC", "DESKTOP_STANDARD", "view-1", "catalog-1",
                        "policy-1", "safety-1"),
                List.of(platformRequest, meetingRequest));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).instanceId()).isEqualTo(platformRequest.instanceId());
        assertThat(results.get(0).state()).isEqualTo(HomeWidgetProviderContract.State.AVAILABLE);
        assertThat(results.get(0).payload()).containsEntry("count", 1);
        assertThat(results.get(1).instanceId()).isEqualTo(meetingRequest.instanceId());
        assertThat(results.get(1).state()).isEqualTo(HomeWidgetProviderContract.State.UNAVAILABLE);
        assertThat(results.get(1).source().reasonCode()).isEqualTo("PROVIDER_HTTP_5XX");
        assertThat(results.get(1).payload()).isEmpty();
        assertThat(results.get(1).actions()).isEmpty();
    }

    @Test
    void crossScopeProviderResponseIsBlockedAndEmittedExactlyOnce() {
        HomeRuntimeContext context = TestFixtures.context();
        WidgetProviderPort.Request request = request(
                "core.work.focus", "APP.WORK", "home.focus");
        HomeWidgetProviderContract.BatchResponse valid = response(context, request);
        WidgetProviderPort provider = new FixedProvider(
                "platform",
                new HomeWidgetProviderContract.BatchResponse(
                        valid.schemaVersion(), valid.tenantId(), valid.userId() + 1,
                        valid.authorityDecisionRevision(), valid.results()));
        HomeRuntimeProperties properties = properties();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WidgetRuntimeBroker broker = new WidgetRuntimeBroker(
                List.of(provider), new RecipientBoundWidgetCache(properties),
                new ProviderResultValidator(mapper, properties), properties,
                new HomeRuntimeTelemetry(meters),
                new HomeCanonicalJson(mapper), executor);

        List<HomeWidgetProviderContract.WidgetResult> results = broker.read(
                context,
                new WidgetRuntimeBroker.Revisions(
                        "CLASSIC", "DESKTOP_STANDARD", "view-1", "catalog-1",
                        "policy-1", "safety-1", "rollout-1", "INTERNAL"),
                List.of(request));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.state()).isEqualTo(HomeWidgetProviderContract.State.UNAVAILABLE);
            assertThat(result.payload()).isEmpty();
            assertThat(result.actions()).isEmpty();
        });
        assertThat(meters.get("dwp.home.security.violation")
                .tags("release_ring", "INTERNAL", "mode", "CLASSIC",
                        "scope", "PROVIDER", "reason", "CROSS_SCOPE")
                .counter().count()).isEqualTo(1);
    }

    private WidgetProviderPort.Request request(
            String definitionKey,
            String sourceResource,
            String rendererKey) {
        WidgetCatalogService.RuntimeDefinition definition =
                new WidgetCatalogService.RuntimeDefinition(
                        UUID.randomUUID(), definitionKey, definitionKey,
                        UUID.randomUUID(), "1.0.0",
                        "a".repeat(64), "binding-1", rendererKey,
                        "workplace", sourceResource,
                        List.of(sourceResource + ":VIEW"), "INTERNAL", "NONE", 30,
                        WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE, List.of());
        return new WidgetProviderPort.Request(UUID.randomUUID(), definition, Map.of(), 10);
    }

    private HomeWidgetProviderContract.BatchResponse response(
            HomeRuntimeContext context,
            WidgetProviderPort.Request request) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new HomeWidgetProviderContract.BatchResponse(
                HomeWidgetProviderContract.SCHEMA_VERSION,
                context.tenantId(), context.userId(), context.authorityDecisionRevision(),
                List.of(new HomeWidgetProviderContract.WidgetResult(
                        request.instanceId(), request.definition().definitionKey(),
                        request.definition().manifestHash(),
                        request.definition().rendererBindingRevision(),
                        HomeWidgetProviderContract.State.AVAILABLE,
                        new HomeWidgetProviderContract.SourceState(
                                "PLATFORM_HOME", now, now.plusSeconds(30), now,
                                null, false, "result-1"),
                        Map.of("count", 1), List.of(), List.of())));
    }

    private HomeRuntimeProperties properties() {
        return new HomeRuntimeProperties(
                true, false, Duration.ofMillis(900), Duration.ofMillis(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);
    }

    private record FixedProvider(
            String providerKey,
            HomeWidgetProviderContract.BatchResponse response) implements WidgetProviderPort {
        @Override
        public HomeWidgetProviderContract.BatchResponse readBatch(
                HomeRuntimeContext context,
                List<Request> requests,
                OffsetDateTime deadline) {
            return response;
        }
    }

    private record FailingProvider(String providerKey) implements WidgetProviderPort {
        @Override
        public HomeWidgetProviderContract.BatchResponse readBatch(
                HomeRuntimeContext context,
                List<Request> requests,
                OffsetDateTime deadline) {
            throw new WidgetProviderException(
                    WidgetProviderException.Kind.UNAVAILABLE,
                    "PROVIDER_HTTP_5XX",
                    "simulated owner outage");
        }
    }
}
