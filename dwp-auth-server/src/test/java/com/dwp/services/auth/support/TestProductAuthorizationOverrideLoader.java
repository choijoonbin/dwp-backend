package com.dwp.services.auth.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test-source-only loader for the two signed CORE-006 contract-test descriptors.
 */
public final class TestProductAuthorizationOverrideLoader {

    static final String RESOURCE =
            "product-authorization/pilot-test-registry-overrides.v1.generated.json";
    static final String EXPECTED_INTEGRITY =
            "21154b1db74160d69a842b6d158e39c607d5c5f7cde77fbeb3d834d8d975d39a";
    private static final String REQUIRED_PROFILE = "contract-test";
    private static final Map<String, ExpectedOverride> EXPECTED = Map.of(
            "PS-G006", new ExpectedOverride(
                    "test.management-and-app.v1",
                    "route.test.management-and-app.page"),
            "PS-G010", new ExpectedOverride(
                    "test.services-catalog-jit.v1",
                    "route.test.services-catalog-jit.page"));

    private final Set<String> activeProfiles;
    private final ClassLoader classLoader;
    private final ObjectMapper objectMapper;

    public TestProductAuthorizationOverrideLoader(Set<String> activeProfiles) {
        this(activeProfiles, TestProductAuthorizationOverrideLoader.class.getClassLoader(),
                new ObjectMapper().findAndRegisterModules());
    }

    TestProductAuthorizationOverrideLoader(
            Set<String> activeProfiles,
            ClassLoader classLoader,
            ObjectMapper objectMapper) {
        this.activeProfiles = activeProfiles == null ? Set.of() : Set.copyOf(activeProfiles);
        this.classLoader = classLoader;
        this.objectMapper = objectMapper;
    }

    public OverrideDescriptor loadForTestId(String testId) {
        requireContractTestProfile();
        List<OverrideDescriptor> descriptors = readAndValidate();
        List<OverrideDescriptor> matches = descriptors.stream()
                .filter(value -> value.testId().equals(testId))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "Contract-test override " + testId + " resolved "
                            + matches.size() + " descriptors.");
        }
        return matches.getFirst();
    }

    public List<OverrideDescriptor> loadAll() {
        requireContractTestProfile();
        return readAndValidate();
    }

    private void requireContractTestProfile() {
        if (!activeProfiles.equals(Set.of(REQUIRED_PROFILE))) {
            throw new IllegalStateException(
                    "Product authorization test overrides require the exclusive contract-test profile.");
        }
    }

    private List<OverrideDescriptor> readAndValidate() {
        ObjectNode document = readDocument();
        require(document.path("schemaVersion").asInt() == 1,
                "Unsupported contract-test override schemaVersion.");
        require(REQUIRED_PROFILE.equals(document.path("profile").asText()),
                "Contract-test override profile mismatch.");
        require(fieldNames(document).equals(Set.of(
                        "schemaVersion", "profile", "overrides", "integrity")),
                "Unexpected contract-test override document field.");

        JsonNode integrity = document.path("integrity");
        require("SHA-256".equals(integrity.path("algorithm").asText()),
                "Unsupported contract-test integrity algorithm.");
        require(EXPECTED_INTEGRITY.equals(integrity.path("sha256").asText()),
                "Unexpected contract-test override integrity value.");
        ObjectNode payload = document.deepCopy();
        payload.remove("integrity");
        require(EXPECTED_INTEGRITY.equals(sha256(payload)),
                "Contract-test override canonical content does not match its integrity value.");

        JsonNode overridesNode = document.get("overrides");
        require(overridesNode instanceof ArrayNode && overridesNode.size() == 2,
                "Contract-test bundle must contain exactly two descriptors.");
        Map<String, OverrideDescriptor> byTestId = new LinkedHashMap<>();
        for (JsonNode node : overridesNode) {
            require(node instanceof ObjectNode, "Contract-test descriptor must be an object.");
            ObjectNode override = (ObjectNode) node;
            require(fieldNames(override).equals(Set.of(
                            "key", "routeContractKey", "testIds", "descriptor")),
                    "Unexpected contract-test descriptor field.");
            JsonNode testIds = override.get("testIds");
            require(testIds instanceof ArrayNode && testIds.size() == 1,
                    "Each contract-test descriptor must own one testId.");
            String testId = testIds.get(0).asText();
            ExpectedOverride expected = EXPECTED.get(testId);
            require(expected != null
                            && expected.key().equals(override.path("key").asText())
                            && expected.routeContractKey().equals(
                                    override.path("routeContractKey").asText()),
                    "Unexpected contract-test descriptor or testId mapping.");
            require(override.path("key").asText().startsWith("test.")
                            && override.path("routeContractKey").asText().startsWith("route.test."),
                    "Contract-test descriptor namespaces are invalid.");
            JsonNode descriptor = override.get("descriptor");
            require(descriptor != null && descriptor.isObject(),
                    "Contract-test descriptor payload is required.");
            OverrideDescriptor value = new OverrideDescriptor(
                    testId,
                    override.path("key").asText(),
                    override.path("routeContractKey").asText(),
                    canonicalJson(descriptor));
            require(byTestId.putIfAbsent(testId, value) == null,
                    "Duplicate contract-test override testId.");
        }
        require(byTestId.keySet().equals(EXPECTED.keySet()),
                "Contract-test override testId closure mismatch.");
        return List.copyOf(byTestId.values());
    }

    private ObjectNode readDocument() {
        try (InputStream input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Signed contract-test override is not on the test classpath.");
            }
            JsonNode value = objectMapper.readTree(input);
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalArgumentException("Contract-test override must be an object.");
            }
            return object;
        } catch (IOException exception) {
            throw new IllegalStateException("Contract-test override could not be read.", exception);
        }
    }

    private String canonicalJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(canonical(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Contract-test descriptor could not be serialized.", exception);
        }
    }

    private String sha256(JsonNode value) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical(value));
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Contract-test override integrity could not be computed.", exception);
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

    private static Set<String> fieldNames(ObjectNode value) {
        Set<String> result = new java.util.HashSet<>();
        value.fieldNames().forEachRemaining(result::add);
        return Set.copyOf(result);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public record OverrideDescriptor(
            String testId,
            String key,
            String routeContractKey,
            String canonicalDescriptorJson) {
    }

    private record ExpectedOverride(String key, String routeContractKey) {
    }
}
