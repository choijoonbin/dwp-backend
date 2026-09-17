package com.dwp.services.platform.widgetregistry.internal.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WidgetRegistryManifestValidatorTest {

    private static final Path FIXTURE = Path.of(
            "../contracts/widget-registry/native-widget-manifests.v1.json");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsExplicitMzPersonalAndGovernedContextsWithoutChangingPublishedHashes()
            throws Exception {
        JsonNode manifest = firstManifest();
        ObjectNode placement = (ObjectNode) manifest.path("placement");
        placement.set("supportedContexts", contexts("CLASSIC_PERSONAL", "MZ_PERSONAL"));

        assertThat(WidgetRegistryManifestValidator.validate(manifest))
                .isEqualTo("core.workspace");

        placement.put("policyClass", "GOVERNED");
        placement.put("canHide", false);
        placement.set("supportedContexts", contexts("MZ_GOVERNED"));
        assertThat(WidgetRegistryManifestValidator.validate(manifest))
                .isEqualTo("core.workspace");
    }

    @Test
    void rejectsGovernedMzPlacementInAPersonalManifest() throws Exception {
        JsonNode manifest = firstManifest();
        ((ObjectNode) manifest.path("placement"))
                .set("supportedContexts", contexts("MZ_GOVERNED"));

        assertThatThrownBy(() -> WidgetRegistryManifestValidator.validate(manifest))
                .isInstanceOf(WidgetRegistryBindingException.class);
    }

    private JsonNode firstManifest() throws Exception {
        return objectMapper.readTree(Files.readString(FIXTURE))
                .path("fixtures").get(0).path("manifest").deepCopy();
    }

    private ArrayNode contexts(String... values) {
        ArrayNode result = objectMapper.createArrayNode();
        for (String value : values) result.add(value);
        return result;
    }
}
