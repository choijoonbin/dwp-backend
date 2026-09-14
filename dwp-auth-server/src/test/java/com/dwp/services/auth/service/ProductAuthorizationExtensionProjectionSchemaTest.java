package com.dwp.services.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Actual Root response-only source structure; this is not a provisional release installation or activation. */
class ProductAuthorizationExtensionProjectionSchemaTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String BINARY = "route.approvals.work.attachment-download-content.data";
    private static final String IMPACT = "route.approvals.admin.policy-impact.data";
    private static Path root() { return Files.isDirectory(Path.of("contracts")) ? Path.of(".") : Path.of(".."); }
    private static JsonNode source(String name) throws Exception {
        try (var input = Files.newInputStream(root().resolve("contracts/product-authorization/" + name))) { return MAPPER.readTree(input); }
    }
    private static ObjectNode bundle() throws Exception { return (ObjectNode) source("product-surfaces-v1.bundle-v9.json"); }
    private static ProductAuthorizationContractDtos.BundleContract typed(JsonNode value) throws Exception {
        return MAPPER.treeToValue(value, ProductAuthorizationContractDtos.BundleContract.class);
    }
    private static ObjectNode route(ObjectNode bundle, String key) {
        for (var value : bundle.withArray("routes")) if (key.equals(value.path("routeContractKey").asText())) return (ObjectNode) value;
        throw new AssertionError("Missing actual route " + key);
    }
    private static ObjectNode profile(ObjectNode route) { return (ObjectNode) route.withArray("accessProfiles").get(0); }
    private static ObjectNode projection(ObjectNode route) { return (ObjectNode) profile(route).withArray("responseProjectionBindings").get(0); }
    private static boolean matches(ObjectNode route) throws Exception {
        var typed = MAPPER.treeToValue(route, ProductAuthorizationContractDtos.GovernedRoute.class);
        var profile = typed.accessProfiles().getFirst();
        return ProductAuthorizationExtensionProjectionSchema.matches(typed, profile.profileKey(), profile.responseProjectionBindings().getFirst());
    }
    static Stream<String> actualRouteKeys() throws Exception {
        return java.util.stream.StreamSupport.stream(source("approval-extension-projections-v9.generated.json").path("bindings").spliterator(), false)
                .map(value -> value.path("routeContractKey").asText());
    }
    @ParameterizedTest @MethodSource("actualRouteKeys") void actualTwelveProjectionDescriptorsMatch(String key) throws Exception {
        assertTrue(matches(route(bundle(), key))); assertTrue(ProductAuthorizationExtensionProjectionSchema.isExtensionDataRoute(key));
    }
    @Test void actualSidecarHasExactTwelveBindingsTwentyFourSchemasAndBinaryClosure() throws Exception {
        var sidecar = source("approval-extension-projections-v9.generated.json");
        assertEquals(12, sidecar.path("bindingCount").intValue()); assertEquals(12, sidecar.path("bindings").size());
        assertEquals(24, sidecar.path("schemas").size()); assertEquals("4a65ce626e1d22f79b955ed5d384396066a21d2fd06e0f12dedab2c54a6c8ec4", sidecar.path("schemaClosureSha256").asText());
        for (var binding : sidecar.path("bindings")) {
            var route = route(bundle(), binding.path("routeContractKey").asText()); var projection = projection(route);
            for (String key : new String[]{"apiBindingKey", "projectionPolicyKey", "responseSchemaKey"}) assertEquals(binding.get(key), projection.get(key));
            if (BINARY.equals(route.path("routeContractKey").asText())) {
                assertEquals("RAW_BINARY", binding.path("schemaKind").asText());
                assertEquals(MAPPER.readTree("[\"application/octet-stream\"]"), binding.path("contentTypes"));
                assertEquals("d9726a8a1015a05c5062b02bf0dbd5fe455824f7da3ab3700db95708d4c5c2bd", binding.path("openApiSchemaSha256").asText());
                assertEquals(MAPPER.readTree("{\"format\":\"binary\",\"type\":\"string\"}"), sidecar.path("binaryResponses").path(BINARY).path("application/octet-stream").path("schema"));
            } else for (String key : new String[]{"schemaVersion", "additionalProperties", "openApiSchemaSha256"}) assertEquals(binding.get(key), projection.get(key));
        }
        assertDoesNotThrow(() -> ProductAuthorizationExtensionProjectionSchema.validateCoverage(typed(bundle())));
    }
    @Test void schemaHashKeyProfileAndBindingPathDriftFailClosed() throws Exception {
        for (String mutation : new String[]{"hash", "schema", "profile", "policy", "binding", "path", "method", "gateway", "kind", "readOnly"}) {
            var route = route(bundle(), IMPACT); var projection = projection(route); var service = (ObjectNode) route.withArray("servicePepBindings").get(0);
            switch (mutation) {
                case "hash" -> projection.put("openApiSchemaSha256", "a".repeat(64));
                case "schema" -> projection.put("responseSchemaKey", "ApprovalAttachmentPolicy");
                case "profile" -> profile(route).put("profileKey", "full-work");
                case "policy" -> projection.put("projectionPolicyKey", "fabricated.projection.v1");
                case "binding" -> projection.put("apiBindingKey", IMPACT + ".binding.02");
                case "path" -> service.put("path", "/v1/admin/policies/{policyId}/impact/alias");
                case "method" -> service.put("method", "POST");
                case "gateway" -> ((ObjectNode) route.withArray("gatewayApiBindings").get(0)).put("path", "/api/approvals/v1/admin/aliases");
                case "kind" -> route.put("routeKind", "ACTION");
                case "readOnly" -> profile(route).put("readOnly", false);
                default -> throw new AssertionError();
            }
            assertFalse(matches(route), mutation);
        }
    }
    @Test void binaryBaseAllowsOnlyDeclaredNullableMetadataAtTheExactBinaryRoute() throws Exception {
        var route = route(bundle(), BINARY); var projection = projection(route);
        assertTrue(matches(route));
        for (String key : new String[]{"schemaVersion", "additionalProperties", "openApiSchemaSha256"}) projection.putNull(key);
        assertTrue(matches(route));
        for (String key : new String[]{"schemaVersion", "additionalProperties", "openApiSchemaSha256"}) {
            var changed = route.deepCopy(); var metadata = projection(changed);
            switch (key) {
                case "schemaVersion" -> metadata.put(key, 1);
                case "additionalProperties" -> metadata.put(key, false);
                case "openApiSchemaSha256" -> metadata.put(key, "d9726a8a1015a05c5062b02bf0dbd5fe455824f7da3ab3700db95708d4c5c2bd");
                default -> throw new AssertionError();
            }
            assertFalse(matches(changed), key);
        }
        var json = route(bundle(), IMPACT); for (String key : new String[]{"schemaVersion", "additionalProperties", "openApiSchemaSha256"}) projection(json).putNull(key);
        assertFalse(matches(json));
        projection(json).put("responseSchemaKey", "ApprovalAttachmentDownloadBytesV1"); assertFalse(matches(json));
    }
    @Test void aliasesExtraMissingAndDuplicateProjectionCountsFailEvenWithoutReleasePin() throws Exception {
        for (String mutation : new String[]{"missingRoute", "extraRoute", "alias", "missingProjection", "extraProjection", "extraBinding"}) {
            var bundle = bundle(); var route = route(bundle, BINARY);
            switch (mutation) {
                case "missingRoute" -> bundle.withArray("routes").remove(java.util.stream.IntStream.range(0, bundle.withArray("routes").size())
                        .filter(index -> BINARY.equals(bundle.withArray("routes").get(index).path("routeContractKey").asText())).findFirst().orElseThrow());
                case "extraRoute" -> bundle.withArray("routes").add(route.deepCopy());
                case "alias" -> { var alias = route.deepCopy(); alias.put("routeContractKey", BINARY.replace(".data", ".read")); bundle.withArray("routes").add(alias); }
                case "missingProjection" -> profile(route).withArray("responseProjectionBindings").removeAll();
                case "extraProjection" -> profile(route).withArray("responseProjectionBindings").add(projection(route).deepCopy());
                case "extraBinding" -> route.withArray("servicePepBindings").add(route.withArray("servicePepBindings").get(0).deepCopy());
                default -> throw new AssertionError();
            }
            assertThrows(IllegalArgumentException.class, () -> ProductAuthorizationExtensionProjectionSchema.validateCoverage(typed(bundle)), mutation);
        }
    }
    @Test void policyImpactRequiresExactThreeExistingViewCapabilitiesNotFourthOrAny() throws Exception {
        for (String mutation : new String[]{"fourth", "missing", "duplicate", "any", "borrowed"}) {
            var route = route(bundle(), IMPACT); var access = (ObjectNode) profile(route).get("requiredAccess"); var capabilities = access.withArray("capabilityContractKeys");
            switch (mutation) {
                case "fourth" -> capabilities.add("approvals.work.request.read");
                case "missing" -> capabilities.remove(0);
                case "duplicate" -> capabilities.set(0, capabilities.get(1).deepCopy());
                case "any" -> access.put("mode", "ANY");
                case "borrowed" -> access.put("capabilityContractKey", "approvals.design.read");
                default -> throw new AssertionError();
            }
            assertFalse(matches(route), mutation);
        }
    }
    @Test void descriptorStructureClosedTenDoesNotAdmitAnUnsealedOrArbitraryRelease() throws Exception {
        for (long version = 1; version <= 10; version++) assertTrue(ProductAuthorizationReleaseLineage.supportsDescriptorStructure(version));
        assertFalse(ProductAuthorizationReleaseLineage.supportsDescriptorStructure(11));
        var candidate = bundle(); candidate.put("checksum", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> ProductAuthorizationReleaseLineage.validateBundle(typed(candidate)));
    }
}
