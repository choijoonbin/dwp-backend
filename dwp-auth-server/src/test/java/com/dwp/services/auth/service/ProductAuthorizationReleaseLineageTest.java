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
    void preservesV33ThroughV49AndAcceptsOnlyThePinnedTim007V50() throws Exception {
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
                     .getInputStream();
             var v39Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v39.generated.json")
                     .getInputStream();
             var v40Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v40.generated.json")
                     .getInputStream();
             var v41Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v41.generated.json")
                     .getInputStream();
             var v42Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v42.generated.json")
                     .getInputStream();
             var v43Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v43.generated.json")
                     .getInputStream();
             var v44Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v44.generated.json")
                     .getInputStream();
             var v45Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v45.generated.json")
                     .getInputStream();
             var v46Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v46.generated.json")
                     .getInputStream();
             var v47Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v47.generated.json")
                     .getInputStream();
             var v48Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v48.generated.json")
                     .getInputStream();
             var v49Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v49.generated.json")
                     .getInputStream();
             var v50Input = new ClassPathResource(
                     "product-authorization/product-surfaces-v1.bundle-v50.generated.json")
                     .getInputStream()) {
            var v33 = validator.validateDocument(v33Input);
            var v34 = validator.validateDocument(v34Input);
            var v35 = validator.validateDocument(v35Input);
            var v36 = validator.validateDocument(v36Input);
            var v37 = validator.validateDocument(v37Input);
            var v38 = validator.validateDocument(v38Input);
            var v39 = validator.validateDocument(v39Input);
            var v40 = validator.validateDocument(v40Input);
            var v41 = validator.validateDocument(v41Input);
            var v42 = validator.validateDocument(v42Input);
            var v43 = validator.validateDocument(v43Input);
            var v44 = validator.validateDocument(v44Input);
            var v45 = validator.validateDocument(v45Input);
            var v46 = validator.validateDocument(v46Input);
            var v47 = validator.validateDocument(v47Input);
            var v48 = validator.validateDocument(v48Input);
            var v49 = validator.validateDocument(v49Input);
            var v50 = validator.validateDocument(v50Input);

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
            assertThat(v39.checksum()).isEqualTo(
                    "86e796c95e45c02892d4c4ae76e7a5b36114ab09554ffc1be3a450066fe8e34c");
            assertThat(v40.checksum()).isEqualTo(
                    "4dca145cbc9169c2d1fde71771b83a1a0635679a7190a39d0a21b4e76bbb9329");
            assertThat(v41.checksum()).isEqualTo(
                    "026157acf598ebabf156b40e3c084c40d52346ebfd2019e2476f30ecdd2b8d93");
            assertThat(v42.checksum()).isEqualTo(
                    "f73f9a64816ac8f5567a2a7c292821f4a40e6d675915ebc3c3ac8aad7d1fd983");
            assertThat(v42.capabilities()).hasSize(255);
            assertThat(v42.routes()).hasSize(1028);
            assertThat(v43.checksum()).isEqualTo(
                    "2222319502c362caf247a7a47fdacfc63c774dbf156f6272cafae797627e2400");
            assertThat(v43.capabilities()).hasSize(256);
            assertThat(v43.routes()).hasSize(1032);
            assertThat(v44.checksum()).isEqualTo(
                    "8b4e05e5974a8a25fb9edf318bb3cd609105f2e6353677b69c2eab1cd7038a1b");
            assertThat(v44.capabilities()).hasSize(258);
            assertThat(v44.routes()).hasSize(1034);
            assertThat(v45.checksum()).isEqualTo(
                    "6f41e17159104cd0fd721a13877611b6225bd6458cb2831f0f30e51573992e64");
            assertThat(v45.capabilities()).hasSize(258);
            assertThat(v45.routes()).hasSize(1046);
            assertThat(v46.checksum()).isEqualTo(
                    "3499e37653587feed433f84a79fbe7d0e80a1a7c0634d858a13f4e09d4bd015e");
            assertThat(v46.capabilities()).hasSize(258);
            assertThat(v46.routes()).hasSize(1048);
            assertThat(v47.checksum()).isEqualTo(
                    "925e8bfc406bc7e4ee62aee08582fdd7c67c629ffde470051704eaec5404800e");
            assertThat(v47.capabilities()).hasSize(258);
            assertThat(v47.routes()).hasSize(1053);
            assertThat(v48.checksum()).isEqualTo(
                    "9fd324cd67ede1f02cc8ac33c08ad880ec6e283f99f5d7aa50e0e514545aa043");
            assertThat(v48.capabilities()).hasSize(258);
            assertThat(v48.routes()).hasSize(1063);
            assertThat(v49.checksum()).isEqualTo(
                    "5c1dccdc27d8b260fbf001de5695e983a7107072824a8087038340d0d84cbc60");
            assertThat(v49.capabilities()).hasSize(262);
            assertThat(v49.routes()).hasSize(1079);
            assertThat(v50.checksum()).isEqualTo(
                    "c52222caad2749c4048c694f6582e157f6ab1184f73237f0944c3d88807a2760");
            assertThat(v50.capabilities()).hasSize(262);
            assertThat(v50.routes()).hasSize(1079);
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

    @Test
    void rejectsAnArbitraryResponsibilityForTheV43ShellCapability() throws Exception {
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v43.generated.json")
                .getInputStream()) {
            ObjectNode document = (ObjectNode) mapper.readTree(input);
            for (var capability : document.path("capabilities")) {
                if ("hcm.app.shell.view".equals(
                        capability.path("contractKey").asText())) {
                    ((ObjectNode) capability).put(
                            "requiredResponsibilityCode", "APP_CONFIG_ADMIN");
                }
            }
            document.put("checksum", validator.checksum(document));
            assertThatThrownBy(() -> validator.validateDocument(document))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(
                            "hcm.app.shell.view: exact required responsibility code");
        }
    }
}
