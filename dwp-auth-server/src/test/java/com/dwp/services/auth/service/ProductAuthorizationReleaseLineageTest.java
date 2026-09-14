package com.dwp.services.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductAuthorizationReleaseLineageTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ProductAuthorizationContractValidator validator =
            new ProductAuthorizationContractValidator(mapper);

    @Test
    void rejectsRecomputedChecksumForEveryMutatedBaselineRelease() throws Exception {
        for (int version = 1; version <= 10; version++) {
            try (var input = new ClassPathResource("product-authorization/product-surfaces-v1.bundle-v"
                    + version + ".generated.json").getInputStream()) {
                ObjectNode document = (ObjectNode) mapper.readTree(input);
                document.put("owner", "Valid owner with malicious baseline change");
                document.put("checksum", validator.checksum(document));
                assertThatThrownBy(() -> validator.validateDocument(document))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("immutable release checksum drift");
            }
        }
    }

    @Test
    void rejectsNewWorkProjectionSchemaMutationsEvenWithRecomputedBundleChecksum() throws Exception {
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v7.generated.json").getInputStream()) {
            ObjectNode original = (ObjectNode) mapper.readTree(input);
            for (var route : original.path("routes")) {
                if (!ProductAuthorizationReleaseLineage.isWorkDataRoute(
                        route.path("routeContractKey").asText())) continue;
                for (String field : java.util.Set.of("responseSchemaKey", "openApiSchemaSha256",
                        "schemaVersion", "additionalProperties", "projectionPolicyKey")) {
                    ObjectNode changed = original.deepCopy();
                    for (var changedRoute : changed.path("routes")) {
                        if (!route.path("routeContractKey").equals(changedRoute.path("routeContractKey")))
                            continue;
                        var projection = (ObjectNode) changedRoute.path("accessProfiles").get(0)
                                .path("responseProjectionBindings").get(0);
                        if (field.equals("schemaVersion")) projection.put(field, 2);
                        else if (field.equals("additionalProperties")) projection.put(field, true);
                        else projection.put(field, "mutated");
                    }
                    changed.put("checksum", validator.checksum(changed));
                    assertThatThrownBy(() -> validator.validateDocument(changed))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("invalid v7 work projection schema metadata");
                }
            }
        }
    }

    @Test
    void rejectsMutatedIndexBaselinePinEvenWithCorrectTopLevelChecksum() throws Exception {
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.index.generated.json").getInputStream()) {
            ObjectNode document = (ObjectNode) mapper.readTree(input);
            ((ObjectNode) document.path("versions").get(5)).put("checksum", "0".repeat(64));
            document.put("indexChecksum", validator.indexChecksum(document));
            assertThatThrownBy(() -> validator.validateSeedIndexDocument(document))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("immutable release checksum drift");
        }
    }
}
