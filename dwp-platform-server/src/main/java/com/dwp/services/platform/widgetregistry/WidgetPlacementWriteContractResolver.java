package com.dwp.services.platform.widgetregistry;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WidgetPlacementWriteContractResolver {
    private WidgetPlacementWriteContractResolver() {
    }

    static <T> Map<String, T> resolve(
            WidgetRegistryDtos.EffectiveCatalogResponse evaluated,
            WidgetDefinitionVersionRepository versions,
            PlacementWriteContractFactory<T> factory) {
        Map<String, T> contracts = new LinkedHashMap<>();
        evaluated.contexts().stream()
                .filter(context -> context.placementContext().endsWith("_PERSONAL"))
                .flatMap(context -> context.items().stream())
                .filter(item -> item.effectiveState()
                        == WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE)
                .filter(item -> item.resolvedVersionId() != null)
                .forEach(item -> versions.findById(item.resolvedVersionId())
                        .map(WidgetDefinitionVersion::getManifest)
                        .map(manifest -> contract(manifest, factory))
                        .ifPresent(contract -> contracts.putIfAbsent(
                                item.definitionKey(), contract)));
        return Map.copyOf(contracts);
    }

    private static <T> T contract(
            JsonNode manifest,
            PlacementWriteContractFactory<T> factory) {
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
        return factory.create(
                placement.path("canHide").asBoolean(false),
                defaultSize, allowedSizes, defaultHeight, allowedHeights);
    }

    @FunctionalInterface
    interface PlacementWriteContractFactory<T> {
        T create(
                boolean canHide,
                String defaultSize,
                Set<String> allowedSizes,
                String defaultHeight,
                Set<String> allowedHeights);
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
