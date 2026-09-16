package com.dwp.services.platform.widgetregistry;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WidgetPlacementWriteContractResolver {
    private WidgetPlacementWriteContractResolver() {
    }

    static Map<String, WidgetCatalogService.PlacementWriteContract> resolve(
            WidgetRegistryDtos.EffectiveCatalogResponse evaluated,
            WidgetDefinitionVersionRepository versions) {
        Map<String, WidgetCatalogService.PlacementWriteContract> contracts =
                new LinkedHashMap<>();
        evaluated.contexts().stream()
                .filter(context -> context.placementContext().endsWith("_PERSONAL"))
                .flatMap(context -> context.items().stream())
                .filter(item -> item.effectiveState()
                        == WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE)
                .filter(item -> item.resolvedVersionId() != null)
                .forEach(item -> versions.findById(item.resolvedVersionId())
                        .map(WidgetDefinitionVersion::getManifest)
                        .map(WidgetPlacementWriteContractResolver::contract)
                        .ifPresent(contract -> contracts.putIfAbsent(
                                item.definitionKey(), contract)));
        return Map.copyOf(contracts);
    }

    private static WidgetCatalogService.PlacementWriteContract contract(JsonNode manifest) {
        JsonNode placement = manifest == null ? null : manifest.path("placement");
        if (placement == null || !"PERSONAL".equals(text(manifest, "/placement/policyClass"))) {
            return null;
        }
        String defaultSize = text(manifest, "/placement/defaultSize");
        String defaultHeight = text(manifest, "/placement/defaultHeight");
        Set<String> allowedSizes = Set.copyOf(strings(manifest, "/placement/allowedSizes"));
        Set<String> allowedHeights = Set.copyOf(strings(manifest, "/placement/allowedHeights"));
        if (defaultSize == null || defaultHeight == null
                || !allowedSizes.contains(defaultSize)
                || !allowedHeights.contains(defaultHeight)) return null;
        return new WidgetCatalogService.PlacementWriteContract(
                placement.path("canHide").asBoolean(false),
                defaultSize, allowedSizes, defaultHeight, allowedHeights);
    }

    private static String text(JsonNode node, String pointer) {
        if (node == null) return null;
        JsonNode value = node.at(pointer);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static List<String> strings(JsonNode node, String pointer) {
        JsonNode values = node == null ? null : node.at(pointer);
        if (values == null || !values.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(values.spliterator(), false)
                .filter(JsonNode::isTextual).map(JsonNode::asText).toList();
    }
}
