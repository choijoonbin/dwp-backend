package com.dwp.services.provider.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only projection of the signed CORE-006 pilot fixture for PROVIDER_SUPPORT tests.
 *
 * <p>The adapter selects canonical records only. It deliberately has no allow,
 * grant, scope, relationship, or challenge builder API.</p>
 */
public final class PilotAuthorizationFixtureAdapter {

    private static final String RESOURCE =
            "product-authorization/pilot-fixtures.v1.generated.json";
    public static final String EXPECTED_FIXTURE_CHECKSUM = readCanonicalFixtureChecksum();
    private static final List<String> CATALOG_NAMES = List.of(
            "scopes",
            "targetPopulations",
            "objects",
            "payloads",
            "relationships",
            "supportSessions",
            "stepUpChallenges");

    private final ObjectMapper objectMapper;
    private final ObjectNode fixture;
    private final Map<String, JsonNode> components;
    private final Map<String, CatalogRecord> catalogs;

    private static String readCanonicalFixtureChecksum() {
        try (InputStream input = PilotAuthorizationFixtureAdapter.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Canonical fixture is unavailable.");
            return new ObjectMapper().readTree(input).path("fixtureChecksum").asText();
        } catch (IOException exception) {
            throw new IllegalStateException("Canonical fixture checksum could not be read.", exception);
        }
    }

    public PilotAuthorizationFixtureAdapter() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    PilotAuthorizationFixtureAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.fixture = readFixture();
        validateFixture();
        this.components = indexComponents();
        this.catalogs = indexCatalogs();
    }

    public ProviderSupportFixture project(String testId) {
        ObjectNode testCase = findTestCase(testId);
        List<SourceRecord> composition = new ArrayList<>();
        for (JsonNode referenceNode : requiredArray(testCase, "composition")) {
            composition.add(resolve(referenceNode.asText()));
        }
        JsonNode sourceRevisionNode = fixture.path("sourceRevisions");
        return new ProviderSupportFixture(
                ProjectionTarget.PROVIDER_SUPPORT,
                fixture.path("schemaVersion").asInt(),
                fixture.path("fixtureBundleKey").asText(),
                fixture.path("fixtureChecksum").asText(),
                Instant.parse(fixture.path("fixedClock").asText()),
                registryReference(testCase),
                new SourceRevisions(
                        sourceRevisionNode.path("auth").asText(),
                        sourceRevisionNode.path("policy").asText(),
                        sourceRevisionNode.path("productRelationship").asText(),
                        sourceRevisionNode.path("targetPopulation").asText(),
                        sourceRevisionNode.path("support").asText()),
                testCase.path("testId").asText(),
                testCase.path("fixtureId").asText(),
                testCase.path("group").asText(),
                testCase.path("expected").asText(),
                textOrNull(testCase, "activeAccessMode"),
                textOrNull(testCase, "testRegistryOverrideRef"),
                List.copyOf(composition),
                canonicalOrNull(testCase.get("delta")));
    }

    private ObjectNode readFixture() {
        ClassLoader loader = PilotAuthorizationFixtureAdapter.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Signed pilot authorization fixture is not on the test classpath.");
            }
            JsonNode document = objectMapper.readTree(input);
            if (!(document instanceof ObjectNode object)) {
                throw new IllegalStateException("Pilot authorization fixture must be an object.");
            }
            return object;
        } catch (IOException exception) {
            throw new IllegalStateException("Pilot authorization fixture could not be read.", exception);
        }
    }

    private void validateFixture() {
        require(fixture.path("schemaVersion").asInt() == 1, "Unsupported fixture schemaVersion.");
        require("pilot-fixtures.v1".equals(fixture.path("fixtureBundleKey").asText()),
                "Unexpected fixture bundle key.");
        require("SHA-256".equals(fixture.path("fixtureChecksumAlgorithm").asText()),
                "Unsupported fixture checksum algorithm.");
        require(EXPECTED_FIXTURE_CHECKSUM.equals(fixture.path("fixtureChecksum").asText()),
                "Unexpected pilot fixture checksum.");
        ObjectNode payload = fixture.deepCopy();
        payload.remove("fixtureChecksum");
        require(EXPECTED_FIXTURE_CHECKSUM.equals(sha256(payload)),
                "Pilot fixture canonical content does not match its checksum.");
        require(!fixture.has("registryRef")
                        && "INFORMATIONAL_ONLY".equals(fixture.path("registryLineage")
                        .path("authority").asText()),
                "Global registry authority is forbidden for pilot fixtures.");
        require(requiredArray(fixture.path("registryLineage"), "versions").size() == 3,
                "Pilot fixture registry lineage must contain exactly v1, v2, and v3.");
        requiredArray(fixture, "testCases").forEach(this::registryReference);
        requiredArray(fixture.path("catalogs"), "stepUpChallenges")
                .forEach(this::registryReference);
        require(requiredArray(fixture, "testCases").size() == 71,
                "Pilot fixture must contain exactly 71 test cases.");
        require(requiredArray(fixture, "negativeCases").size() == 46,
                "Pilot fixture must contain exactly 46 negative cases.");
    }

    private Map<String, JsonNode> indexComponents() {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode value : requiredArray(fixture, "components")) {
            putUnique(result, value.path("key").asText(), value, "component");
        }
        return Map.copyOf(result);
    }

    private Map<String, CatalogRecord> indexCatalogs() {
        JsonNode catalogRoot = fixture.path("catalogs");
        require(catalogRoot.isObject(), "Fixture catalogs must be an object.");
        Map<String, CatalogRecord> result = new LinkedHashMap<>();
        for (String catalogName : CATALOG_NAMES) {
            for (JsonNode value : requiredArray(catalogRoot, catalogName)) {
                String key = value.path("key").asText();
                require(!key.isBlank() && !result.containsKey(key),
                        "Duplicate or empty fixture catalog key: " + key);
                result.put(key, new CatalogRecord(catalogName, value));
            }
        }
        return Map.copyOf(result);
    }

    private ObjectNode findTestCase(String testId) {
        require(testId != null && !testId.isBlank(), "Pilot fixture testId is required.");
        ObjectNode match = null;
        int count = 0;
        for (JsonNode value : requiredArray(fixture, "testCases")) {
            if (testId.equals(value.path("testId").asText())) {
                require(value instanceof ObjectNode, "Pilot fixture test case must be an object.");
                match = (ObjectNode) value;
                count++;
            }
        }
        require(count == 1, "Pilot authorization fixture " + testId
                + " resolved " + count + " records.");
        return match;
    }

    private SourceRecord resolve(String reference) {
        if (reference.startsWith("CASE:")) {
            return new SourceRecord(SourceType.CASE_DIRECTIVE, null, reference, null, null);
        }
        JsonNode component = components.get(reference);
        if (component != null) {
            return new SourceRecord(
                    SourceType.COMPONENT, null, reference, canonicalJson(component), null);
        }
        CatalogRecord catalog = catalogs.get(reference);
        require(catalog != null,
                "Pilot authorization composition references unknown source key " + reference + ".");
        return new SourceRecord(
                SourceType.CATALOG, catalog.catalogName(), reference,
                canonicalJson(catalog.value()),
                "stepUpChallenges".equals(catalog.catalogName())
                        ? registryReference(catalog.value()) : null);
    }

    private RegistryReference registryReference(JsonNode source) {
        JsonNode value = source.path("requiredRegistryRef");
        String bundleKey = value.path("bundleKey").asText();
        long version = value.path("version").asLong();
        String checksum = value.path("sha256").asText();
        require(value.isObject() && value.size() == 3
                        && "product-surfaces".equals(bundleKey)
                        && version >= 1 && version <= 3
                        && checksum.matches("^[0-9a-f]{64}$"),
                "Exact case/challenge registry reference is required.");
        RegistryReference reference = new RegistryReference(bundleKey, version, checksum);
        long lineageMatches = 0;
        for (JsonNode lineage : requiredArray(fixture.path("registryLineage"), "versions")) {
            if (bundleKey.equals(lineage.path("bundleKey").asText())
                    && version == lineage.path("version").asLong()
                    && checksum.equals(lineage.path("sha256").asText())) {
                lineageMatches++;
            }
        }
        require(lineageMatches == 1,
                "Case/challenge registry reference must resolve exactly once in the lineage.");
        if (source.hasNonNull("group")) {
            require(version == expectedCaseVersion(source),
                    source.path("testId").asText() + ": cross-gate registry reference.");
        }
        if (source.hasNonNull("ownerServiceKey")) {
            long expected = switch (source.path("ownerServiceKey").asText()) {
                case "approval" -> 2L;
                case "people" -> 3L;
                default -> throw new IllegalArgumentException(
                        "Unknown step-up challenge owner service.");
            };
            require(version == expected,
                    source.path("key").asText() + ": cross-gate challenge registry reference.");
        }
        return reference;
    }

    private long expectedCaseVersion(JsonNode testCase) {
        return switch (testCase.path("group").asText()) {
            case "CANARY" -> 1L;
            case "APPROVALS" -> 2L;
            case "HCM" -> 3L;
            case "GUARD" -> {
                long version = 1L;
                for (JsonNode reference : requiredArray(testCase, "composition")) {
                    if (reference.asText().startsWith("HCM_")) {
                        version = 3L;
                    } else if (version < 2L && reference.asText().startsWith("AP_")) {
                        version = 2L;
                    }
                }
                yield version;
            }
            default -> throw new IllegalArgumentException("Unknown pilot fixture gate group.");
        };
    }

    private String canonicalOrNull(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode()
                ? null : canonicalJson(value);
    }

    private String canonicalJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(canonical(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Fixture source record could not be serialized.", exception);
        }
    }

    private String sha256(JsonNode value) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical(value));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Fixture checksum could not be computed.", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value.deepCopy();
    }

    private static ArrayNode requiredArray(JsonNode source, String field) {
        JsonNode value = source.get(field);
        require(value instanceof ArrayNode, "Fixture " + field + " must be an array.");
        return (ArrayNode) value;
    }

    private static String textOrNull(ObjectNode source, String field) {
        JsonNode value = source.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static <T> void putUnique(
            Map<String, T> values, String key, T value, String label) {
        require(key != null && !key.isBlank() && values.putIfAbsent(key, value) == null,
                "Duplicate or empty fixture " + label + " key: " + key);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public enum ProjectionTarget {
        PROVIDER_SUPPORT
    }

    public enum SourceType {
        COMPONENT,
        CATALOG,
        CASE_DIRECTIVE
    }

    public record RegistryReference(String bundleKey, long version, String checksum) {
    }

    public record SourceRevisions(
            String auth,
            String policy,
            String productRelationship,
            String targetPopulation,
            String support) {
    }

    public record SourceRecord(
            SourceType source,
            String catalog,
            String reference,
            String canonicalJson,
            RegistryReference requiredRegistryReference) {
        public SourceRecord(
                SourceType source, String catalog, String reference,
                String canonicalJson) {
            this(source, catalog, reference, canonicalJson, null);
        }
    }

    public record ProviderSupportFixture(
            ProjectionTarget projectionTarget,
            int schemaVersion,
            String fixtureBundleKey,
            String fixtureChecksum,
            Instant fixedClock,
            RegistryReference registryReference,
            SourceRevisions sourceRevisions,
            String testId,
            String fixtureId,
            String group,
            String expectedOutcome,
            String activeAccessMode,
            String testRegistryOverrideRef,
            List<SourceRecord> composition,
            String deltaJson) {
    }

    private record CatalogRecord(String catalogName, JsonNode value) {
    }
}
