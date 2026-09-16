package com.dwp.services.platform.widgetregistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WidgetPlacementWriteContractResolverTest {
    @Mock private WidgetDefinitionVersionRepository versions;

    @Test
    void resolvesOnlyEffectiveAvailablePersonalDataPlaneDefinitions() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UUID allowedVersion = UUID.randomUUID();
        UUID governedManifestVersion = UUID.randomUUID();
        UUID governedContextVersion = UUID.randomUUID();
        when(versions.findById(allowedVersion)).thenReturn(Optional.of(version(
                allowedVersion, mapper, "PERSONAL", "medium", "medium", "standard")));
        when(versions.findById(governedManifestVersion)).thenReturn(Optional.of(version(
                governedManifestVersion, mapper, "GOVERNED", "medium", "medium", "standard")));
        var response = new WidgetRegistryDtos.EffectiveCatalogResponse(
                1, "SHADOW", "1", "1", "1", "1", null,
                List.of(
                        context("CLASSIC_PERSONAL", List.of(
                                item("partner.allowed", allowedVersion,
                                        WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE),
                                item("partner.denied", UUID.randomUUID(),
                                        WidgetRegistryDtos.EffectiveCatalogState.DENY),
                                item("partner.governed-manifest", governedManifestVersion,
                                        WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE))),
                        context("FLOW_GOVERNED", List.of(
                                item("partner.governed-context", governedContextVersion,
                                        WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE)))));

        var result = WidgetPlacementWriteContractResolver.resolve(response, versions);

        assertThat(result).containsOnlyKeys("partner.allowed");
        assertThat(result.get("partner.allowed").allowedSizes())
                .containsExactly("medium");
        assertThat(result.get("partner.allowed").allowedHeights())
                .containsExactly("standard");
    }

    private WidgetDefinitionVersion version(
            UUID id,
            ObjectMapper mapper,
            String policyClass,
            String defaultSize,
            String allowedSize,
            String defaultHeight) throws Exception {
        return WidgetDefinitionVersion.builder()
                .versionId(id)
                .manifest(mapper.readTree("""
                        {"placement":{"policyClass":"%s","canHide":true,
                        "defaultSize":"%s","allowedSizes":["%s"],
                        "defaultHeight":"%s","allowedHeights":["standard"]}}
                        """.formatted(
                                policyClass, defaultSize, allowedSize, defaultHeight)))
                .build();
    }

    private WidgetRegistryDtos.PlacementContext context(
            String key, List<WidgetRegistryDtos.EffectiveItem> items) {
        return new WidgetRegistryDtos.PlacementContext(
                key,
                new WidgetRegistryDtos.CatalogCapabilities(
                        true, false, false, false, false, false),
                items);
    }

    private WidgetRegistryDtos.EffectiveItem item(
            String key, UUID versionId, WidgetRegistryDtos.EffectiveCatalogState state) {
        return new WidgetRegistryDtos.EffectiveItem(
                UUID.randomUUID(), key, null, versionId, "1.0.0", state, List.of(),
                new WidgetRegistryDtos.PlacementCapabilities(false, false, false, false), 0);
    }
}
