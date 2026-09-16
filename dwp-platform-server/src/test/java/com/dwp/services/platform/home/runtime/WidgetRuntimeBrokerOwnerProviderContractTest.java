package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.dwp.services.platform.widgetregistry.WidgetCatalogService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetRuntimeBrokerOwnerProviderContractTest {

    private static final Path FIXTURE = Path.of(
            "../contracts/widget-registry/wave4-owner-widget-manifests.v1.json");
    private static final List<String> PROVIDERS = List.of(
            "approval", "meeting", "notification", "space", "messaging", "people");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void allTwelveShadowDefinitionsUseSixProvidersAndPassStrictValidation() throws Exception {
        List<WidgetProviderPort.Request> requests = requests();
        HomeRuntimeContext context = context(requests);
        Map<String, RecordingProvider> providers = providers(Corruption.NONE);

        List<HomeWidgetProviderContract.WidgetResult> results = read(
                context, requests, providers);

        assertThat(results).hasSize(12).allSatisfy(result ->
                assertThat(result.state()).isEqualTo(HomeWidgetProviderContract.State.AVAILABLE));
        assertThat(providers).containsOnlyKeys(PROVIDERS);
        providers.forEach((providerKey, provider) -> {
            assertThat(provider.calls).isEqualTo(1);
            assertThat(provider.definitionKeys).hasSize(2).allSatisfy(definition ->
                    assertThat(providerKey(requests, definition)).isEqualTo(providerKey));
        });
    }

    @Test
    void recipientRevisionAndForeignDefinitionCorruptionFailClosedPerOwner() throws Exception {
        List<WidgetProviderPort.Request> requests = requests();
        HomeRuntimeContext context = context(requests);

        for (Corruption corruption : EnumSet.complementOf(EnumSet.of(Corruption.NONE))) {
            Map<String, RecordingProvider> providers = providers(corruption);

            List<HomeWidgetProviderContract.WidgetResult> results = read(
                    context, requests, providers);

            assertThat(results).hasSize(12);
            assertThat(results).filteredOn(result ->
                            result.state() == HomeWidgetProviderContract.State.UNAVAILABLE)
                    .hasSize(2).allSatisfy(result -> {
                        assertThat(result.definitionKey()).startsWith("meetings.");
                        assertThat(result.source().reasonCode())
                                .isEqualTo("PROVIDER_CONTRACT_INVALID");
                        assertThat(result.payload()).isEmpty();
                    });
            assertThat(results).filteredOn(result ->
                            result.state() == HomeWidgetProviderContract.State.AVAILABLE)
                    .hasSize(10);
            assertThat(providers.get("meeting").calls).isEqualTo(1);
        }
    }

    private List<HomeWidgetProviderContract.WidgetResult> read(
            HomeRuntimeContext context,
            List<WidgetProviderPort.Request> requests,
            Map<String, RecordingProvider> providers) {
        HomeRuntimeProperties properties = new HomeRuntimeProperties(
                true, true, false,
                Duration.ofMillis(900), Duration.ofMillis(400),
                Duration.ofSeconds(30), Duration.ofMinutes(5), 100, 262_144);
        ExecutorService executor = Executors.newFixedThreadPool(PROVIDERS.size());
        try {
            WidgetRuntimeBroker broker = new WidgetRuntimeBroker(
                    new ArrayList<>(providers.values()),
                    new RecipientBoundWidgetCache(properties),
                    new ProviderResultValidator(mapper, properties),
                    properties,
                    new HomeRuntimeTelemetry(new SimpleMeterRegistry()),
                    new HomeCanonicalJson(mapper),
                    executor);
            return broker.read(context, new WidgetRuntimeBroker.Revisions(
                    "CLASSIC", "DESKTOP_STANDARD", "view-1", "catalog-1",
                    "policy-1", "safety-1"), requests);
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<String, RecordingProvider> providers(Corruption meetingCorruption) {
        Map<String, RecordingProvider> result = new LinkedHashMap<>();
        for (String provider : PROVIDERS) {
            result.put(provider, new RecordingProvider(
                    provider,
                    "meeting".equals(provider) ? meetingCorruption : Corruption.NONE));
        }
        return result;
    }

    private List<WidgetProviderPort.Request> requests() throws Exception {
        JsonNode fixture = mapper.readTree(Files.readString(FIXTURE));
        return StreamSupport.stream(fixture.path("fixtures").spliterator(), false)
                .map(item -> new WidgetProviderPort.Request(
                        UUID.randomUUID(), definition(item), Map.of(), 10))
                .toList();
    }

    private WidgetCatalogService.RuntimeDefinition definition(JsonNode item) {
        JsonNode manifest = item.path("manifest");
        List<String> authorities = StreamSupport.stream(
                        manifest.path("requiredAuthorities").spliterator(), false)
                .map(JsonNode::asText).toList();
        return new WidgetCatalogService.RuntimeDefinition(
                UUID.fromString(item.path("definitionId").asText()),
                manifest.path("definitionKey").asText(),
                item.path("legacyWidgetKey").asText(),
                UUID.fromString(item.path("versionId").asText()),
                item.path("semanticVersion").asText(),
                item.path("expectedSha256").asText(),
                "binding-23",
                manifest.path("renderer").path("rendererKey").asText(),
                manifest.path("owner").path("productKey").asText(),
                manifest.path("owner").path("sourceAppResourceKey").asText(),
                authorities,
                manifest.path("privacy").path("classification").asText(),
                manifest.path("privacy").path("retention").asText(),
                manifest.path("operations").path("freshnessSeconds").asInt(),
                WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE,
                List.of(WidgetRegistryDtos.EffectiveCatalogReason.AVAILABLE.name()));
    }

    private HomeRuntimeContext context(List<WidgetProviderPort.Request> requests) {
        String permissions = requests.stream()
                .flatMap(request -> request.definition().requiredAuthorities().stream())
                .distinct().sorted().collect(java.util.stream.Collectors.joining(","));
        return HomeRuntimeContext.create(
                71L, 82L, UUID.fromString("65000000-0000-0000-0000-000000000001"),
                permissions, "MEMBER", "team-a", "decision-23",
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString(),
                "ko-KR", "Asia/Seoul");
    }

    private String providerKey(
            List<WidgetProviderPort.Request> requests,
            String definitionKey) {
        return requests.stream()
                .filter(request -> request.definition().definitionKey().equals(definitionKey))
                .findFirst()
                .map(request -> WidgetRuntimeBroker.providerKey(
                        request.definition().sourceAppResourceKey()))
                .orElseThrow();
    }

    private enum Corruption {
        NONE,
        TENANT,
        USER,
        AUTHORITY_REVISION,
        FOREIGN_DEFINITION
    }

    private static final class RecordingProvider implements WidgetProviderPort {
        private final String providerKey;
        private final Corruption corruption;
        private int calls;
        private List<String> definitionKeys = List.of();

        private RecordingProvider(String providerKey, Corruption corruption) {
            this.providerKey = providerKey;
            this.corruption = corruption;
        }

        @Override
        public String providerKey() {
            return providerKey;
        }

        @Override
        public HomeWidgetProviderContract.BatchResponse readBatch(
                HomeRuntimeContext context,
                List<Request> requests,
                OffsetDateTime deadline) {
            calls++;
            definitionKeys = requests.stream()
                    .map(request -> request.definition().definitionKey()).toList();
            long tenant = corruption == Corruption.TENANT
                    ? context.tenantId() + 1 : context.tenantId();
            long user = corruption == Corruption.USER
                    ? context.userId() + 1 : context.userId();
            String revision = corruption == Corruption.AUTHORITY_REVISION
                    ? context.authorityDecisionRevision() + "-stale"
                    : context.authorityDecisionRevision();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            List<HomeWidgetProviderContract.WidgetResult> results = requests.stream()
                    .map(request -> result(request, now)).toList();
            if (corruption == Corruption.FOREIGN_DEFINITION) {
                HomeWidgetProviderContract.WidgetResult original = results.getFirst();
                HomeWidgetProviderContract.WidgetResult foreign =
                        new HomeWidgetProviderContract.WidgetResult(
                                original.instanceId(), "hr.team-pulse",
                                original.definitionManifestHash(),
                                original.rendererBindingRevision(), original.state(),
                                original.source(), original.payload(),
                                original.actions(), original.redactions());
                results = new ArrayList<>(results);
                results.set(0, foreign);
            }
            return new HomeWidgetProviderContract.BatchResponse(
                    HomeWidgetProviderContract.SCHEMA_VERSION,
                    tenant, user, revision, results);
        }

        private HomeWidgetProviderContract.WidgetResult result(
                Request request,
                OffsetDateTime now) {
            return new HomeWidgetProviderContract.WidgetResult(
                    request.instanceId(), request.definition().definitionKey(),
                    request.definition().manifestHash(),
                    request.definition().rendererBindingRevision(),
                    HomeWidgetProviderContract.State.AVAILABLE,
                    new HomeWidgetProviderContract.SourceState(
                            providerKey.toUpperCase() + "_HOME",
                            now, now.plusSeconds(30), now,
                            null, false, "result-23"),
                    Map.of("count", 1), List.of(), List.of());
        }
    }
}
