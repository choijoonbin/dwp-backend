package com.dwp.services.platform.widgetregistry.internal.security;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Executable counterpart of widget-manifest.v1.schema.json for command ingress. */
final class WidgetRegistryManifestValidator {

    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "schemaVersion", "definitionKey", "owner", "renderer", "supportedSurfaces",
            "requiredAuthorities", "placement", "configurationContract", "dataCapabilities",
            "actionCapabilities", "sharing", "operations", "privacy");
    private static final Set<String> SIZE_VALUES = Set.of(
            "fifth", "quarter", "compact", "medium", "large", "full");
    private static final Set<String> HEIGHT_VALUES = Set.of(
            "short", "standard", "tall", "expanded");
    private static final Set<String> CONTEXT_VALUES = Set.of(
            "CLASSIC_PERSONAL", "FLOW_PERSONAL", "FLOW_GOVERNED");
    private static final List<String> CONTEXT_RANK = List.of(
            "CLASSIC_PERSONAL", "FLOW_PERSONAL", "FLOW_GOVERNED");
    private static final List<String> SIZE_RANK = List.of(
            "fifth", "quarter", "compact", "medium", "large", "full");
    private static final List<String> HEIGHT_RANK = List.of(
            "short", "standard", "tall", "expanded");
    private static final Set<String> CLASSIFICATION_VALUES = Set.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    private WidgetRegistryManifestValidator() {
    }

    static String validate(JsonNode manifest) throws WidgetRegistryBindingException {
        byte[] canonical = WidgetRegistryCanonicalJson.encode(
                manifest.toString().getBytes(StandardCharsets.UTF_8));
        WidgetRegistryJsonContract.require(canonical.length <= 32 * 1024);
        WidgetRegistryJsonContract.exactObject(manifest, MANIFEST_FIELDS, Set.of());
        WidgetRegistryJsonContract.require(
                WidgetRegistryJsonContract.nonNegativeInteger(manifest, "schemaVersion") == 1);
        WidgetRegistryJsonContract.text(
                manifest, "definitionKey", 3, 128, WidgetRegistryJsonContract.LOWER_KEY);
        String ownerProductKey = validateOwner(
                WidgetRegistryJsonContract.requiredNode(manifest, "owner"));
        validateRenderer(WidgetRegistryJsonContract.requiredNode(manifest, "renderer"));
        WidgetRegistryJsonContract.textArray(
                manifest, "supportedSurfaces", 1, 8, 1, 32, null);
        JsonNode surfaces = manifest.get("supportedSurfaces");
        for (JsonNode surface : surfaces) {
            WidgetRegistryJsonContract.require("workspace-home".equals(surface.textValue()));
        }
        WidgetRegistryJsonContract.textArray(
                manifest,
                "requiredAuthorities",
                1,
                32,
                7,
                160,
                WidgetRegistryJsonContract.AUTHORITY);
        WidgetRegistryJsonContract.requireSorted(manifest, "requiredAuthorities", null);
        validatePlacement(WidgetRegistryJsonContract.requiredNode(manifest, "placement"));
        validateConfiguration(WidgetRegistryJsonContract.requiredNode(manifest, "configurationContract"));
        WidgetRegistryJsonContract.textArray(
                manifest, "dataCapabilities", 1, 32, 1, 128, WidgetRegistryJsonContract.UPPER_KEY);
        WidgetRegistryJsonContract.textArray(
                manifest, "actionCapabilities", 0, 32, 1, 128, WidgetRegistryJsonContract.UPPER_KEY);
        WidgetRegistryJsonContract.requireSorted(manifest, "dataCapabilities", null);
        WidgetRegistryJsonContract.requireSorted(manifest, "actionCapabilities", null);
        validateSharing(WidgetRegistryJsonContract.requiredNode(manifest, "sharing"));
        validateOperations(WidgetRegistryJsonContract.requiredNode(manifest, "operations"));
        validatePrivacy(WidgetRegistryJsonContract.requiredNode(manifest, "privacy"));
        return ownerProductKey;
    }

    private static String validateOwner(JsonNode owner) throws WidgetRegistryBindingException {
        WidgetRegistryJsonContract.exactObject(
                owner, Set.of("productKey", "sourceAppResourceKey"), Set.of());
        String productKey = WidgetRegistryJsonContract.text(
                owner, "productKey", 3, 128, WidgetRegistryJsonContract.LOWER_KEY);
        WidgetRegistryJsonContract.text(
                owner,
                "sourceAppResourceKey",
                5,
                128,
                WidgetRegistryJsonContract.APP_RESOURCE_KEY);
        return productKey;
    }

    private static void validateRenderer(JsonNode renderer) throws WidgetRegistryBindingException {
        WidgetRegistryJsonContract.exactObject(
                renderer,
                Set.of("kind", "rendererKey", "minimumHostApiVersion"),
                Set.of());
        WidgetRegistryJsonContract.require(
                "NATIVE".equals(WidgetRegistryJsonContract.text(renderer, "kind", 6, 6, null)));
        WidgetRegistryJsonContract.text(
                renderer, "rendererKey", 3, 128, WidgetRegistryJsonContract.LOWER_KEY);
        WidgetRegistryJsonContract.integerBetween(
                renderer, "minimumHostApiVersion", 1, 65_535);
    }

    private static void validatePlacement(JsonNode placement) throws WidgetRegistryBindingException {
        Set<String> fields = Set.of(
                "supportedContexts", "policyClass", "canHide", "defaultSize", "allowedSizes",
                "defaultHeight", "allowedHeights");
        WidgetRegistryJsonContract.exactObject(placement, fields, Set.of());
        Set<String> contexts = WidgetRegistryJsonContract.textArray(
                placement, "supportedContexts", 1, 3, 1, 32, null);
        WidgetRegistryJsonContract.require(CONTEXT_VALUES.containsAll(contexts));
        WidgetRegistryJsonContract.requireSorted(placement, "supportedContexts", CONTEXT_RANK);
        String policyClass = WidgetRegistryJsonContract.enumText(
                placement, "policyClass", Set.of("PERSONAL", "GOVERNED"));
        WidgetRegistryJsonContract.bool(placement, "canHide");
        WidgetRegistryJsonContract.require(
                !("PERSONAL".equals(policyClass) && contexts.contains("FLOW_GOVERNED")));
        WidgetRegistryJsonContract.require(
                !("GOVERNED".equals(policyClass)
                        && (!contexts.contains("FLOW_GOVERNED")
                        || contexts.contains("FLOW_PERSONAL"))));
        String defaultSize = WidgetRegistryJsonContract.enumText(
                placement, "defaultSize", SIZE_VALUES);
        Set<String> sizes = WidgetRegistryJsonContract.textArray(
                placement, "allowedSizes", 1, 6, 1, 16, null);
        WidgetRegistryJsonContract.require(SIZE_VALUES.containsAll(sizes));
        WidgetRegistryJsonContract.requireSorted(placement, "allowedSizes", SIZE_RANK);
        WidgetRegistryJsonContract.require(sizes.contains(defaultSize));
        String defaultHeight = WidgetRegistryJsonContract.enumText(
                placement, "defaultHeight", HEIGHT_VALUES);
        Set<String> heights = WidgetRegistryJsonContract.textArray(
                placement, "allowedHeights", 1, 4, 1, 16, null);
        WidgetRegistryJsonContract.require(HEIGHT_VALUES.containsAll(heights));
        WidgetRegistryJsonContract.requireSorted(placement, "allowedHeights", HEIGHT_RANK);
        WidgetRegistryJsonContract.require(heights.contains(defaultHeight));
    }

    private static void validateConfiguration(JsonNode configuration) throws WidgetRegistryBindingException {
        if (configuration.isNull()) return;
        WidgetRegistryJsonContract.exactObject(
                configuration,
                Set.of("sourceKey", "fieldKeys", "filterPresets", "itemLimit"),
                Set.of());
        WidgetRegistryJsonContract.text(
                configuration, "sourceKey", 1, 128, WidgetRegistryJsonContract.UPPER_KEY);
        WidgetRegistryJsonContract.textArray(
                configuration, "fieldKeys", 1, 32, 1, 64, WidgetRegistryJsonContract.FIELD_KEY);
        WidgetRegistryJsonContract.textArray(
                configuration,
                "filterPresets",
                1,
                32,
                1,
                128,
                WidgetRegistryJsonContract.UPPER_KEY);
        JsonNode itemLimit = WidgetRegistryJsonContract.requiredNode(configuration, "itemLimit");
        WidgetRegistryJsonContract.exactObject(itemLimit, Set.of("min", "max"), Set.of());
        long minimum = WidgetRegistryJsonContract.integerBetween(itemLimit, "min", 1, 100);
        long maximum = WidgetRegistryJsonContract.integerBetween(itemLimit, "max", 1, 100);
        WidgetRegistryJsonContract.require(minimum <= maximum);
    }

    private static void validateSharing(JsonNode sharing) throws WidgetRegistryBindingException {
        WidgetRegistryJsonContract.exactObject(sharing, Set.of("presetEligible"), Set.of());
        WidgetRegistryJsonContract.bool(sharing, "presetEligible");
    }

    private static void validateOperations(JsonNode operations) throws WidgetRegistryBindingException {
        WidgetRegistryJsonContract.exactObject(
                operations, Set.of("freshnessSeconds", "analyticsKey"), Set.of());
        WidgetRegistryJsonContract.integerBetween(operations, "freshnessSeconds", 5, 3_600);
        WidgetRegistryJsonContract.text(
                operations, "analyticsKey", 3, 128, WidgetRegistryJsonContract.LOWER_KEY);
    }

    private static void validatePrivacy(JsonNode privacy) throws WidgetRegistryBindingException {
        WidgetRegistryJsonContract.exactObject(
                privacy,
                Set.of("classification", "retention", "recipientContextBinding"),
                Set.of());
        WidgetRegistryJsonContract.enumText(privacy, "classification", CLASSIFICATION_VALUES);
        WidgetRegistryJsonContract.require(
                "NONE".equals(WidgetRegistryJsonContract.text(privacy, "retention", 4, 4, null)));
        WidgetRegistryJsonContract.require(
                WidgetRegistryJsonContract.bool(privacy, "recipientContextBinding"));
    }
}
