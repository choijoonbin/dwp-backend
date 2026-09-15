package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine generated release10 descriptors, not fabricated StoredBundle authority. */
class ProductAuthorizationRelease10ProjectionSchemaTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ProductAuthorizationContractValidator validator = new ProductAuthorizationContractValidator(json);
    private ObjectNode bundle() throws Exception {
        try (var stream = new ClassPathResource("product-authorization/product-surfaces-v1.bundle-v10.generated.json").getInputStream()) {
            return (ObjectNode) json.readTree(stream);
        }
    }
    private ObjectNode route(ObjectNode bundle, String key) {
        for (var route : bundle.path("routes")) if (key.equals(route.path("routeContractKey").asText())) return (ObjectNode) route;
        throw new AssertionError(key);
    }
    private ObjectNode profile(ObjectNode route) { return (ObjectNode) route.path("accessProfiles").get(0); }
    private ObjectNode projection(ObjectNode route) { return (ObjectNode) profile(route).path("responseProjectionBindings").get(0); }
    private ProductAuthorizationContractDtos.BundleContract typed(JsonNode bundle) throws Exception {
        return json.treeToValue(bundle, ProductAuthorizationContractDtos.BundleContract.class);
    }
    @Test void exactFullImmutableBundleAndIndexKeepTenWhileLatestAdvancesToFourteen() throws Exception {
        var actual = bundle();
        assertEquals(10, validator.validateDocument(actual).version());
        assertEquals(318, actual.path("routes").size());
        try (var stream = new ClassPathResource("product-authorization/product-surfaces-v1.index.generated.json").getInputStream()) {
            assertEquals(14, validator.validateSeedIndexDocument(json.readTree(stream)).latestVersion());
        }
        assertTrue(ProductAuthorizationReleaseLineage.supportsDescriptorStructure(11));
        assertTrue(ProductAuthorizationReleaseLineage.supportsDescriptorStructure(12));
        assertTrue(ProductAuthorizationReleaseLineage.supportsDescriptorStructure(13));
        assertTrue(ProductAuthorizationReleaseLineage.supportsDescriptorStructure(14));
        assertFalse(ProductAuthorizationReleaseLineage.supportsDescriptorStructure(15));
        actual.put("version", 15); actual.put("checksum", validator.checksum(actual));
        assertThrows(IllegalArgumentException.class, () -> validator.validateDocument(actual));
    }
    @Test void allEightActualDataDescriptorsMatchAndInheritedBinaryRemainsNullableBase() throws Exception {
        var actual = bundle(); int count = 0;
        for (var route : typed(actual).routes()) if (ProductAuthorizationRelease10ProjectionSchema.isRelease10DataRoute(route.routeContractKey())) {
            var profile = route.accessProfiles().getFirst();
            assertTrue(ProductAuthorizationRelease10ProjectionSchema.matches(route, profile.profileKey(), profile.responseProjectionBindings().getFirst()));
            count++;
        }
        assertEquals(8, count);
        assertDoesNotThrow(() -> ProductAuthorizationRelease10ProjectionSchema.validateCoverage(typed(actual)));
        assertEquals(3, projection(route(actual, "route.approvals.work.attachment-download-content.data")).size());
    }
    @Test void exactProfileSchemaTypePathPredicatesAndPlanningAllCannotDriftEvenWithRecomputedChecksum() throws Exception {
        String key = "route.approvals.admin.workflow-planning-simulation.data";
        for (String mutation : new String[]{"hash","schema","profile","readonly","predicate","path","method","any","missingCap","extraCap","metadataType"}) {
            var actual = bundle(); var route = route(actual, key); var profile = profile(route); var projection = projection(route);
            var access = (ObjectNode) profile.path("requiredAccess");
            switch (mutation) {
                case "hash" -> projection.put("openApiSchemaSha256", "0".repeat(64));
                case "schema" -> projection.put("responseSchemaKey", "ApprovalSignatureCeremony");
                case "profile" -> profile.put("profileKey", "full-work");
                case "readonly" -> profile.put("readOnly", false);
                case "predicate" -> profile.withArray("predicatePolicyKeys").removeAll();
                case "path" -> ((ObjectNode) route.path("servicePepBindings").get(0)).put("path", "/v1/admin/workflows/{workflowId}/simulation");
                case "method" -> ((ObjectNode) route.path("servicePepBindings").get(0)).put("method", "GET");
                case "any" -> access.put("mode", "ANY");
                case "missingCap" -> access.withArray("capabilityContractKeys").remove(0);
                case "extraCap" -> access.withArray("capabilityContractKeys").add("approvals.design.read");
                case "metadataType" -> projection.put("additionalProperties", true);
                default -> throw new AssertionError();
            }
            assertThrows(IllegalArgumentException.class, () -> ProductAuthorizationRelease10ProjectionSchema.validateCoverage(typed(actual)), mutation);
            actual.put("checksum", validator.checksum(actual));
            assertThrows(IllegalArgumentException.class, () -> validator.validateDocument(actual), mutation);
        }
    }
    @Test void missingDuplicateAndAliasDataNeverPassCoverage() throws Exception {
        String key = "route.approvals.work.signature-command-receipt.data";
        for (String mutation : new String[]{"missing","duplicate","alias","projection"}) {
            var actual = bundle(); var route = route(actual, key);
            switch (mutation) {
                case "missing" -> actual.withArray("routes").remove(java.util.stream.IntStream.range(0, actual.path("routes").size())
                        .filter(i -> key.equals(actual.path("routes").get(i).path("routeContractKey").asText())).findFirst().orElseThrow());
                case "duplicate" -> actual.withArray("routes").add(route.deepCopy());
                case "alias" -> { var alias = route.deepCopy(); alias.put("routeContractKey", key.replace(".data",".read")); actual.withArray("routes").add(alias); }
                case "projection" -> profile(route).withArray("responseProjectionBindings").add(projection(route).deepCopy());
                default -> throw new AssertionError();
            }
            assertThrows(IllegalArgumentException.class, () -> ProductAuthorizationRelease10ProjectionSchema.validateCoverage(typed(actual)), mutation);
        }
    }
}
