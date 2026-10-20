package com.dwp.services.platform.hrisconfiguration.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class Sys006ClosedTransportSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchemaFactory SCHEMAS = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012);

    @Test
    void sealedSourceAndAllSixClosedSchemasRemainExactAndExecutable() throws Exception {
        JsonNode closedSchemas = frozenReferenceFixture();

        assertThat(closedSchemas.isObject()).isTrue();
        assertThat(sha256(canonical(closedSchemas)))
                .isEqualTo(Sys006ClosedTransportSchemaReference.CANONICAL_SHA256);
        Optional<Path> sourcePath = sourcePath();
        if (sourcePath.isPresent()) {
            JsonNode authoritative = JSON.readTree(Files.readAllBytes(sourcePath.get())).at(
                    Sys006ClosedTransportSchemaReference.JSON_POINTER);
            assertThat(sha256(canonical(authoritative)))
                    .isEqualTo(Sys006ClosedTransportSchemaReference.CANONICAL_SHA256);
            assertThat(canonical(authoritative)).isEqualTo(canonical(closedSchemas));
        }
        assertThat(apiSchemaNames(closedSchemas))
                .containsExactlyInAnyOrderElementsOf(
                        Sys006ClosedTransportSchemaReference.API_SCHEMAS);

        for (Map.Entry<String, JsonNode> fixture : validFixtures().entrySet()) {
            String schemaName = fixture.getKey();
            JsonSchema schema = schema(closedSchemas, schemaName);
            JsonNode valid = fixture.getValue();
            assertThat(schema.validate(valid))
                    .as("valid fixture for %s", schemaName)
                    .isEmpty();

            ObjectNode unknown = valid.deepCopy();
            unknown.put("unsealedProperty", true);
            assertThat(schema.validate(unknown))
                    .as("additional property for %s", schemaName)
                    .isNotEmpty();

            String firstRequired = closedSchemas.path(schemaName)
                    .path("required").path(0).asText();
            ObjectNode missingRequired = valid.deepCopy();
            missingRequired.remove(firstRequired);
            assertThat(schema.validate(missingRequired))
                    .as("missing required property for %s", schemaName)
                    .isNotEmpty();
        }
    }

    private static JsonSchema schema(JsonNode closedSchemas, String schemaName) {
        ObjectNode root = JSON.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$ref", "#" + Sys006ClosedTransportSchemaReference.JSON_POINTER
                + "/" + schemaName);
        ObjectNode contractDecision = JSON.createObjectNode();
        contractDecision.set("closedTransportSchemas", closedSchemas);
        root.set("contractDecision", contractDecision);
        return SCHEMAS.getSchema(root);
    }

    private static Map<String, JsonNode> validFixtures() throws Exception {
        Map<String, JsonNode> fixtures = new LinkedHashMap<>();
        fixtures.put("SYS_API_017_GET_QUERY", JSON.readTree("""
                {
                  "asOf":"2026-10-20T00:00:00Z",
                  "fields":[],
                  "freshness":"FRESH_ONLY",
                  "limit":20
                }
                """));
        fixtures.put("SYS_API_017_GET_RESPONSE", JSON.readTree("""
                {
                  "asOf":"2026-10-20T00:00:00Z",
                  "items":[],
                  "nextCursor":null,
                  "freshUntil":"2026-10-20T00:05:00Z",
                  "staleAfter":"2026-10-20T00:01:00Z",
                  "collectionActions":[],
                  "fieldDecisionSummary":{
                    "visible":[],
                    "masked":[],
                    "omitted":[],
                    "fieldPolicyRevision":1
                  },
                  "authorizationRevision":1,
                  "correlationId":"00000000-0000-4000-8000-000000000611"
                }
                """));
        fixtures.put("SYS_API_017_POST_REQUEST", JSON.readTree("""
                {
                  "automationKey":"payroll-close",
                  "displayName":"Payroll close",
                  "description":null,
                  "ownerDomain":"PAY"
                }
                """));
        fixtures.put("SYS_API_017_POST_RESPONSE", JSON.readTree("""
                {
                  "receiptId":"00000000-0000-4000-8000-000000000612",
                  "commandType":"CREATE_AUTOMATION_DEFINITION",
                  "originatingAction":"hris.action.sys.post.platform.v1.admin.hris.automations",
                  "subjectPrincipalPublicId":"00000000-0000-4000-8000-000000000613",
                  "populationScopeDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "fieldPolicyRevision":1,
                  "purposeCode":"HRIS_SYSTEM_ADMIN",
                  "authorizationRevision":1,
                  "status":"ACCEPTED",
                  "correlationId":"00000000-0000-4000-8000-000000000614",
                  "requestDigest":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "createdAt":"2026-10-20T00:00:00Z"
                }
                """));
        fixtures.put("SYS_API_019_GET_QUERY", JSON.readTree("""
                {
                  "asOf":"2026-10-20T00:00:00Z",
                  "freshness":"FRESH_ONLY",
                  "limit":20
                }
                """));
        fixtures.put("SYS_API_019_GET_RESPONSE", JSON.readTree("""
                {
                  "runPublicId":"00000000-0000-4000-8000-000000000615",
                  "definitionPublicId":"00000000-0000-4000-8000-000000000616",
                  "versionNumber":1,
                  "state":"QUEUED",
                  "objectRevision":1,
                  "selectionMode":"ALL_ITEMS",
                  "itemTotal":0,
                  "itemSucceeded":0,
                  "itemFailed":0,
                  "outputDigest":null,
                  "retryBudgetRemaining":1,
                  "retryEligibleItemCount":0,
                  "cancelEligibility":"ELIGIBLE_NO_EFFECT",
                  "attempts":[],
                  "items":[],
                  "checkpoints":[],
                  "nextAttemptCursor":null,
                  "nextItemCursor":null,
                  "nextCheckpointCursor":null,
                  "availableActions":[],
                  "authorizationRevision":1,
                  "correlationId":"00000000-0000-4000-8000-000000000617"
                }
                """));
        return Map.copyOf(fixtures);
    }

    private static Set<String> apiSchemaNames(JsonNode closedSchemas) {
        Set<String> names = new TreeSet<>();
        closedSchemas.fieldNames().forEachRemaining(name -> {
            if (name.startsWith("SYS_API_")) names.add(name);
        });
        return names;
    }

    private static JsonNode frozenReferenceFixture() throws Exception {
        try (InputStream input = Sys006ClosedTransportSchemaTest.class
                .getResourceAsStream(
                        "/contracts/hris/sys006-closed-transport-schemas.v1.json")) {
            if (input == null) {
                throw new IllegalStateException(
                        "Frozen SYS-006 closed-transport test fixture is unavailable");
            }
            return JSON.readTree(input);
        }
    }

    private static Optional<Path> sourcePath() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            for (Path repository : List.of(
                    current.resolve("dwp-frontend"),
                    current.resolveSibling("dwp-frontend"))) {
                Path candidate = repository.resolve(
                        Sys006ClosedTransportSchemaReference.SOURCE_PATH);
                if (Files.isRegularFile(candidate)) return Optional.of(candidate);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static String sha256(JsonNode node) throws Exception {
        byte[] compact = JSON.writeValueAsBytes(node);
        byte[] canonical = java.util.Arrays.copyOf(compact, compact.length + 1);
        canonical[compact.length] = '\n';
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical));
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            Set<String> names = new TreeSet<>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, canonical(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node.deepCopy();
    }
}
