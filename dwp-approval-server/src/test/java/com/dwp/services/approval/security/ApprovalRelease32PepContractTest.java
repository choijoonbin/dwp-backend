package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class ApprovalRelease32PepContractTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void release32AdvancesOnlyThePinnedRegistryEnvelope() throws Exception {
        ObjectNode previous = resource(ApprovalPilotPepRegistry.V31_RESOURCE);
        ObjectNode current = resource(ApprovalPilotPepRegistry.V32_RESOURCE);

        assertThat(current.path("registryRef").path("version").asInt()).isEqualTo(32);
        assertThat(current.path("registryRef").path("sha256").asText()).isEqualTo(
                "b620ea86a8310cf23796e3e380b74c39764bdca28f41033496d21887a89da9cc");
        assertThat(current.path("sourceRegistryRouteCount").asInt()).isEqualTo(912);
        assertThat(current.path("projectedRouteContractCount").asInt()).isEqualTo(216);
        assertThat(current.path("bindingPairCount").asInt()).isEqualTo(285);
        assertThat(current.path("projectionChecksum").asText()).isEqualTo(
                "2c3f771da173ca48ac2f32752bcce79f840911417a041194118b4882a6cb041b");

        stripReleaseEnvelope(previous);
        stripReleaseEnvelope(current);
        assertThat(current).isEqualTo(previous);
        assertThat(new ApprovalPilotPepRegistry(json).bindingContracts())
                .containsExactlyElementsOf(new ApprovalPilotPepRegistry(
                        json, java.time.Clock.systemUTC(), 31).bindingContracts());
    }

    private ObjectNode resource(String path) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            JsonNode document = json.readTree(input);
            assertThat(document).isInstanceOf(ObjectNode.class);
            return (ObjectNode) document;
        }
    }

    private static void stripReleaseEnvelope(ObjectNode projection) {
        projection.remove("projectionKey");
        projection.remove("registryRef");
        projection.remove("sourceRegistryRouteCount");
        projection.remove("projectionChecksum");
    }
}
