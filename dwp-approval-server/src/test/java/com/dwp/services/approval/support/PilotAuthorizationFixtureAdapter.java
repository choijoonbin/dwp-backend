package com.dwp.services.approval.support;

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
 * Read-only projection of the signed CORE-006 pilot fixture for APPROVAL_PEP tests.
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

    public ApprovalPepFixture project(String testId) {
        ObjectNode testCase = findTestCase(testId);
        List<SourceRecord> composition = new ArrayList<>();
        ApprovalStepUpFixture stepUpChallenge = null;
        for (JsonNode referenceNode : requiredArray(testCase, "composition")) {
            SourceRecord record = resolve(referenceNode.asText());
            composition.add(record);
            if ("stepUpChallenges".equals(record.catalog())) {
                require(stepUpChallenge == null,
                        "Approval fixture may bind only one step-up challenge.");
                stepUpChallenge = stepUp(record.reference(), record.canonicalJson());
            }
        }
        JsonNode sourceRevisionNode = fixture.path("sourceRevisions");
        return new ApprovalPepFixture(
                ProjectionTarget.APPROVAL_PEP,
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
                stepUpChallenge,
                canonicalOrNull(testCase.get("delta")));
    }

    private ApprovalStepUpFixture stepUp(String reference, String canonicalJson) {
        try {
            JsonNode value = objectMapper.readTree(canonicalJson);
            JsonNode verification = fixture.path("stepUpVerification");
            return new ApprovalStepUpFixture(
                    reference,
                    value.path("challengeId").asText(),
                    value.path("policy").asText(),
                    value.path("ownerServiceKey").asText(),
                    value.path("commandContractKey").asText(),
                    value.path("stepUpCommandBindingKey").asText(),
                    value.path("contextKey").asText(),
                    value.path("capabilityContractKey").asText(),
                    value.path("decisionRevision").asText(),
                    value.path("scopeRef").asText(),
                    value.path("targetType").asText(),
                    value.path("targetId").asText(),
                    value.path("targetVersion").asLong(),
                    value.path("targetIdSource").asText(),
                    textOrNull(value, "targetIdPathParameter"),
                    optionalTextArray(value, "targetIdBodyFields"),
                    value.path("expectedObjectVersionSource").asText(),
                    value.path("expectedObjectVersionName").asText(),
                    value.path("method").asText(),
                    value.path("path").asText(),
                    value.path("actorUserId").asLong(),
                    value.path("tenantId").asLong(),
                    value.path("idempotencyKey").asText(),
                    canonicalJson(value.path("payload")),
                    value.path("payloadSha256").asText(),
                    value.path("commandSha256").asText(),
                    value.path("nonce").asText(),
                    value.path("state").asText(),
                    value.path("compactToken").asText(),
                    registryReference(value),
                    new StepUpVerification(
                            verification.path("algorithm").asText(),
                            verification.path("keyId").asText(),
                            verification.path("issuer").asText(),
                            value.path("audience").asText(),
                            verification.path("requiredAcr").asText(),
                            verification.path("publicKeyPem").asText()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Step-up fixture could not be projected.", exception);
        }
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
        requiredArray(fixture.path("catalogs"), "stepUpChallenges").forEach(value -> {
            registryReference(value);
            validateStepUpMetadata(value);
        });
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
            return new SourceRecord(
                    SourceType.CASE_DIRECTIVE, null, reference, null, null);
        }
        JsonNode component = components.get(reference);
        if (component != null) {
            return new SourceRecord(
                    SourceType.COMPONENT, null, reference,
                    canonicalJson(component), null);
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

    private void validateStepUpMetadata(JsonNode value) {
        JsonNode verification = fixture.path("stepUpVerification");
        JsonNode audiences = verification.path("audienceByOwnerService");
        String owner = value.path("ownerServiceKey").asText();
        require(audiences.isObject() && audiences.size() == 2
                        && "dwp-approval-server".equals(audiences.path("approval").asText())
                        && "dwp-people-server".equals(audiences.path("people").asText())
                        && audiences.path(owner).asText().equals(value.path("audience").asText()),
                "Step-up owner audience binding is invalid.");
        require(value.path("stepUpCommandBindingKey").asText()
                        .matches("^route\\..+\\.binding\\.[0-9]+$")
                        && value.path("commandContractKey").asText().startsWith("route."),
                "Step-up command binding metadata is invalid.");
        String targetSource = value.path("targetIdSource").asText();
        if ("PATH_PARAMETER".equals(targetSource)) {
            require(value.path("targetIdPathParameter").isTextual()
                            && !value.has("targetIdBodyFields"),
                    "Path target source must be exclusive.");
        } else if ("COMMAND_BODY".equals(targetSource)) {
            List<String> fields = optionalTextArray(value, "targetIdBodyFields");
            List<String> values = fields.stream()
                    .map(field -> value.path("payload").path(field).asText())
                    .toList();
            require(!value.has("targetIdPathParameter") && !fields.isEmpty()
                            && values.stream().noneMatch(String::isBlank)
                            && values.stream().noneMatch(item -> item.contains(":")
                            || item.contains("\n") || item.contains("\r"))
                            && String.join(":", values).equals(value.path("targetId").asText()),
                    "Body target source must use the ordered colon-joined field values.");
        } else {
            throw new IllegalArgumentException("Unknown step-up target source.");
        }
        String versionSource = value.path("expectedObjectVersionSource").asText();
        String versionName = value.path("expectedObjectVersionName").asText();
        JsonNode payload = value.path("payload");
        if ("COMMAND_BODY".equals(versionSource)) {
            require(payload.path(versionName).isIntegralNumber()
                            && payload.path(versionName).longValue()
                            == value.path("targetVersion").longValue(),
                    "COMMAND_BODY step-up version binding is invalid.");
        } else if ("COMMAND_HEADER".equals(versionSource)) {
            require(!payload.has(versionName) && !payload.has("expectedVersion"),
                    "COMMAND_HEADER step-up version leaked into the payload.");
        } else {
            throw new IllegalArgumentException("Unknown expected-version source.");
        }
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

    private static List<String> optionalTextArray(JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) return List.of();
        require(value instanceof ArrayNode, "Fixture " + field + " must be an array.");
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            require(item.isTextual() && !item.asText().isBlank(),
                    "Fixture " + field + " must contain non-empty strings.");
            result.add(item.asText());
        });
        require(result.stream().distinct().count() == result.size(),
                "Fixture " + field + " must be unique.");
        return List.copyOf(result);
    }

    private static String textOrNull(JsonNode source, String field) {
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
        APPROVAL_PEP
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

    public record ApprovalPepFixture(
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
            ApprovalStepUpFixture stepUpChallenge,
            String deltaJson) {
    }

    public record StepUpVerification(
            String algorithm,
            String keyId,
            String issuer,
            String audience,
            String requiredAcr,
            String publicKeyPem) {
    }

    public record ApprovalStepUpFixture(
            String reference,
            String challengeId,
            String policy,
            String ownerServiceKey,
            String commandContractKey,
            String stepUpCommandBindingKey,
            String contextKey,
            String capabilityContractKey,
            String decisionRevision,
            String scopeRef,
            String targetType,
            String targetId,
            long targetVersion,
            String targetIdSource,
            String targetIdPathParameter,
            List<String> targetIdBodyFields,
            String expectedObjectVersionSource,
            String expectedObjectVersionName,
            String method,
            String path,
            long actorUserId,
            long tenantId,
            String idempotencyKey,
            String payloadCanonicalJson,
            String payloadSha256,
            String commandSha256,
            String nonce,
            String state,
            String compactToken,
            RegistryReference requiredRegistryReference,
            StepUpVerification verification) {
    }

    private record CatalogRecord(String catalogName, JsonNode value) {
    }
}
