package com.dwp.services.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductAuthorizationReleaseLineageTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ProductAuthorizationContractValidator validator =
            new ProductAuthorizationContractValidator(mapper);

    @Test
    void preservesV33ThroughV37AndAcceptsOnlyThePinnedHrisWave2V38() throws Exception {
        try (var v33Input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v33.generated.json")
                .getInputStream();
             var v34Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v34.generated.json")
                     .getInputStream();
             var v35Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v35.generated.json")
                     .getInputStream();
             var v36Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v36.generated.json")
                     .getInputStream();
             var v37Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v37.generated.json")
                     .getInputStream();
             var v38Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v38.generated.json")
                     .getInputStream()) {
            var v33 = validator.validateDocument(v33Input);
            var v34 = validator.validateDocument(v34Input);
            var v35 = validator.validateDocument(v35Input);
            var v36 = validator.validateDocument(v36Input);
            var v37 = validator.validateDocument(v37Input);
            var v38 = validator.validateDocument(v38Input);

            assertThat(v33.checksum()).isEqualTo(
                    "9c9a18b44eb83de0e98f4ec16e44c1df0ce216e00bc7075462f4e35f7fb87639");
            assertThat(v33.routes()).hasSize(945);
            assertThat(v34.checksum()).isEqualTo(
                    "852d20e1e639e1a7170f02b5714d21d8c51a9eb8ff5ac32d8b7940b82d6be83b");
            assertThat(v34.capabilities()).hasSize(223);
            assertThat(v34.routes()).hasSize(952);
            assertThat(v35.checksum()).isEqualTo(
                    "38ef33070a33b5da9a7825c764bd24daacd24c8ca4008f3a926727321d3d098d");
            assertThat(v35.capabilities()).hasSize(236);
            assertThat(v35.routes()).hasSize(974);
            assertThat(v36.checksum()).isEqualTo(
                    "12e4b9329ab53ab1efd6c5f6e23ccc7869c4184a79e5a64f614fe9c40a9e704b");
            assertThat(v36.capabilities()).hasSize(237);
            assertThat(v36.routes()).hasSize(974);
            assertThat(v37.checksum()).isEqualTo(
                    "1f23280116bc5b29f1a7ea6cc4ccbd2092a91673979d6bb46164a0dbb95ae10c");
            assertThat(v37.capabilities()).hasSize(237);
            assertThat(v37.routes()).hasSize(974);
            assertThat(v38.checksum()).isEqualTo(
                    "a41145d6a2faefd2e6030a2ca9a070769019d4db5513ea214bf542dda31c374a");
            assertThat(v38.capabilities()).hasSize(242);
            assertThat(v38.routes()).hasSize(991);
        }
    }

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
