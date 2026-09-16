package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.HomeExperienceDtos;
import com.dwp.services.platform.home.HomeExperienceService;
import com.dwp.services.platform.home.personalization.EffectiveHomeViewQuery;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.widgetregistry.WidgetCatalogService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class HomeReadModelOwnerProviderRoutingTest {

    private static final Path FIXTURE = Path.of(
            "../contracts/widget-registry/wave4-owner-widget-manifests.v1.json");

    @Test
    void v2HomeRoutesElevenPlacedAliasesAndTheCompositionBadgeProjectionToSixOwners()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        List<WidgetCatalogService.RuntimeDefinition> definitions = definitions(mapper);
        List<HomePreferenceDtos.WidgetPreference> placed = definitions.stream()
                .filter(definition -> !"notification.app-badges".equals(definition.definitionKey()))
                .map(definition -> new HomePreferenceDtos.WidgetPreference(
                        definition.legacyWidgetKey(), true, "medium", "standard"))
                .toList();

        HomeExperienceService experiences = mock(HomeExperienceService.class);
        HomeExperienceDtos.HomeExperienceResponse experience = mock(
                HomeExperienceDtos.HomeExperienceResponse.class);
        when(experience.effectiveExperienceVariant()).thenReturn("CLASSIC");
        when(experience.contentAlignment()).thenReturn("LEFT");
        when(experience.launchpadConfiguration()).thenReturn(
                new HomeExperienceDtos.HomeLaunchpadConfiguration(1, List.of(), List.of()));
        when(experience.version()).thenReturn(4L);
        when(experiences.get(71L)).thenReturn(experience);

        EffectiveHomeViewQuery views = mock(EffectiveHomeViewQuery.class);
        EffectiveHomeViewQuery.EffectiveView view = mock(
                EffectiveHomeViewQuery.EffectiveView.class);
        when(view.viewId()).thenReturn(UUID.fromString(
                "65000000-0000-0000-0000-000000000001"));
        when(view.revision()).thenReturn(7L);
        when(view.mode()).thenReturn("CLASSIC");
        when(view.deviceClass()).thenReturn("DESKTOP_STANDARD");
        when(view.source()).thenReturn("HOME_VIEW");
        when(view.layout()).thenReturn(new HomePreferenceDtos.HomeLayoutPayload(
                null, "balanced", placed));
        when(view.widgetConfigurations()).thenReturn(Map.of());
        when(views.resolve(71L, 82L, "CLASSIC", "DESKTOP_STANDARD")).thenReturn(view);

        WidgetCatalogService catalog = mock(WidgetCatalogService.class);
        when(catalog.runtimeCatalog(any(), any(), any(), any(), any(), any()))
                .thenReturn(new WidgetCatalogService.RuntimeCatalog(
                        "SHADOW", "23", "binding-23", "23", "1", "decision-23",
                        definitions));

        WidgetRuntimeBroker broker = mock(WidgetRuntimeBroker.class);
        when(broker.read(any(), any(), any())).thenAnswer(invocation -> {
            List<WidgetProviderPort.Request> requests = invocation.getArgument(2);
            return requests.stream().map(this::available).toList();
        });
        HomeReadModelService service = new HomeReadModelService(
                experiences, views, catalog, broker, mapper, new HomeCanonicalJson(mapper));
        HomeRuntimeProperties properties = new HomeRuntimeProperties(
                true, true, false,
                Duration.ofMillis(900), Duration.ofMillis(400), Duration.ofSeconds(30),
                Duration.ofMinutes(5), 100, 262_144);
        MockMvc mvc = standaloneSetup(new HomeReadModelController(
                service, mock(HomeWidgetCommandService.class), properties)).build();

        mvc.perform(get("/v2/home")
                        .queryParam("mode", "CLASSIC")
                        .queryParam("deviceClass", "DESKTOP_STANDARD")
                        .header("X-DWP-Tenant-ID", "71")
                        .header("X-DWP-User-ID", "82")
                        .header("X-DWP-Permissions", permissions(definitions))
                        .header("X-DWP-Roles", "MEMBER")
                        .header("X-DWP-Current-Decision-Revision", "decision-23")
                        .header("X-DWP-Current-Revalidate-At",
                                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-DWP-Widget-Registry-Authoritative", "false"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WidgetProviderPort.Request>> requests = ArgumentCaptor.forClass(
                (Class<List<WidgetProviderPort.Request>>) (Class<?>) List.class);
        org.mockito.Mockito.verify(broker).read(any(), any(), requests.capture());
        assertThat(requests.getValue()).hasSize(12);
        Map<String, Long> providerCounts = requests.getValue().stream().collect(
                java.util.stream.Collectors.groupingBy(
                        request -> WidgetRuntimeBroker.providerKey(
                                request.definition().sourceAppResourceKey()),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
        assertThat(providerCounts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "approval", 2L,
                "meeting", 2L,
                "notification", 2L,
                "space", 2L,
                "messaging", 2L,
                "people", 2L));
        assertThat(requests.getValue()).extracting(
                        request -> request.definition().definitionKey())
                .containsExactlyInAnyOrderElementsOf(definitions.stream()
                        .map(WidgetCatalogService.RuntimeDefinition::definitionKey).toList());
    }

    private List<WidgetCatalogService.RuntimeDefinition> definitions(ObjectMapper mapper)
            throws Exception {
        JsonNode fixture = mapper.readTree(Files.readString(FIXTURE));
        return java.util.stream.StreamSupport.stream(
                        fixture.path("fixtures").spliterator(), false)
                .map(item -> definition(item, mapper)).toList();
    }

    private WidgetCatalogService.RuntimeDefinition definition(
            JsonNode item, ObjectMapper mapper) {
        JsonNode manifest = item.path("manifest");
        List<String> authorities = java.util.stream.StreamSupport.stream(
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

    private HomeWidgetProviderContract.WidgetResult available(
            WidgetProviderPort.Request request) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean badge = "notification.app-badges".equals(
                request.definition().definitionKey());
        Map<String, Object> payload = badge
                ? Map.of("counterVersion", "17", "items", List.of()) : Map.of();
        return new HomeWidgetProviderContract.WidgetResult(
                request.instanceId(), request.definition().definitionKey(),
                request.definition().manifestHash(),
                request.definition().rendererBindingRevision(),
                HomeWidgetProviderContract.State.AVAILABLE,
                new HomeWidgetProviderContract.SourceState(
                        WidgetRuntimeBroker.providerKey(
                                request.definition().sourceAppResourceKey()).toUpperCase()
                                + "_HOME",
                        now, now.plusSeconds(30), now, null, false, "17"),
                payload, List.of(), List.of());
    }

    private String permissions(List<WidgetCatalogService.RuntimeDefinition> definitions) {
        return definitions.stream().flatMap(definition ->
                        definition.requiredAuthorities().stream())
                .distinct().sorted().collect(java.util.stream.Collectors.joining(","));
    }
}
