package com.dwp.services.platform.hrisconfiguration.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class Sys008ClosedTransportSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchemaFactory SCHEMAS = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012);

    @Test
    void frozenG2ArtifactAndAllEighteenOperationsRemainExact() throws Exception {
        byte[] bytes = frozenBytes();
        assertThat(bytes).hasSize((int) Sys008ClosedTransportSchemaReference.SOURCE_BYTES);
        assertThat(sha256(bytes))
                .isEqualTo(Sys008ClosedTransportSchemaReference.SOURCE_SHA256);

        JsonNode root = JSON.readTree(bytes);
        Set<String> actual = StreamSupport.stream(
                        root.path("operations").spliterator(), false)
                .filter(operation -> operation.path("path").asText()
                        .matches("^/api/platform/v1/admin/hris/(?:connectors|connector-executions).*"))
                .map(operation -> operation.path("method").asText() + " "
                        + operation.path("path").asText())
                .collect(Collectors.toUnmodifiableSet());
        Set<String> expected = Sys008ClosedTransportSchemaReference.OPERATIONS.stream()
                .map(operation -> operation.method() + " " + operation.browserPath())
                .collect(Collectors.toUnmodifiableSet());

        assertThat(Sys008ClosedTransportSchemaReference.OPERATIONS).hasSize(18);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual).hasSize(18);
    }

    @Test
    void fourteenClosedSchemasAreExecutableAndRejectUnknownProperties()
            throws Exception {
        JsonNode root = JSON.readTree(frozenBytes());
        Map<String, JsonNode> fixtures = validFixtures();
        assertThat(fixtures).hasSize(14);
        assertThat(Sys008ClosedTransportSchemaReference.SEMANTIC_SCHEMAS)
                .hasSize(14);
        assertThat(fixtures.keySet()).containsExactlyInAnyOrderElementsOf(
                Sys008ClosedTransportSchemaReference.SEMANTIC_TO_G2_SCHEMA.values());

        fixtures.forEach((schemaName, fixture) -> {
            JsonSchema schema = schema(root, schemaName);
            assertThat(schema.validate(fixture))
                    .as("valid %s fixture", schemaName).isEmpty();
            ObjectNode unknown = fixture.deepCopy();
            unknown.put("unsealedProperty", true);
            assertThat(schema.validate(unknown))
                    .as("closed %s schema", schemaName).isNotEmpty();
        });
    }

    private static JsonSchema schema(JsonNode frozen, String schemaName) {
        ObjectNode root = frozen.deepCopy();
        root.put("$ref", "#/$defs/" + schemaName);
        return SCHEMAS.getSchema(root);
    }

    private static Map<String, JsonNode> validFixtures() {
        Map<String, JsonNode> fixtures = new LinkedHashMap<>();
        fixtures.put("ConnectorCommand", json("""
                {"connectorKey":"workday.hcm","type":"HTTPS",
                 "endpoint":"https://connector.example.test/v1",
                 "secretRef":"secret_1234567890abcdef",
                 "egressPolicyKey":"hris-egress-v1"}
                """));
        fixtures.put("ConnectorTestCommand", json("""
                {"testMode":"CONNECTIVITY","sampleRef":"ref:sample:008",
                 "sampleDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """));
        fixtures.put("MappingDryRunCommand", json("""
                {"sampleRef":"ref:sample:008",
                 "sampleDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "maxRows":100,"asOf":"2026-10-26T00:00:00Z"}
                """));
        fixtures.put("ExecutionCommand", json("""
                {"operation":"IMPORT","payloadRef":"ref:payload:008",
                 "payloadDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "asOf":"2026-10-26T00:00:00Z"}
                """));
        fixtures.put("ConnectorValidationCommand", json("""
                {"definitionDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "endpointPolicyRevision":1,"secretRevision":1}
                """));
        fixtures.put("ConnectorMappingCommand", json("""
                {"sourceSchemaVersion":1,"targetSchemaVersion":1,
                 "mappingRef":"ref_1234567890abcdef",
                 "mappingDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "compatibilityMode":"EXACT","effectiveFrom":"2026-10-26T00:00:00Z",
                 "effectiveTo":null}
                """));
        fixtures.put("MappingValidationCommand", json("""
                {"mappingDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "sourceSchemaDigest":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                 "targetSchemaDigest":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}
                """));
        fixtures.put("ApprovalRequestCommand", json("""
                {"approvalWorkflowKey":"hris.integration","subjectRevision":1,
                 "payloadDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """));
        fixtures.put("ApprovalDecisionCommand", json("""
                {"approvalReceiptId":"00000000-0000-4000-8000-000000000806",
                 "decisionDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """));
        fixtures.put("ActivationCommand", json("""
                {"approvalReceiptId":"00000000-0000-4000-8000-000000000806",
                 "effectiveAt":"2026-10-26T00:00:00Z"}
                """));
        fixtures.put("ExecutionReconcileCommand", json("""
                {"providerReceiptRef":"ref_1234567890abcdef",
                 "providerReceiptDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "expectedSourceCount":10,"asOf":"2026-10-26T00:00:00Z"}
                """));
        fixtures.put("FailedExecutionItemRetryCommand", json("""
                {"failedItemIds":["00000000-0000-4000-8000-000000000807"],
                 "failedItemSetDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "expectedRejectedCount":1,"reasonCode":"RETRY_FAILED"}
                """));
        fixtures.put("CancelCommand", json("""
                {"reasonCode":"OPERATOR_CANCEL","compensationPolicyRef":null}
                """));
        fixtures.put("ConnectorExecutionReceiptResponse", json("""
                {"executionId":"00000000-0000-4000-8000-000000000805",
                 "mappingVersion":1,"state":"RESULT_UNKNOWN","sourceCount":10,
                 "acceptedCount":7,"rejectedCount":2,"selectedItemCount":9,
                 "selectedItemDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "reconciliationDigest":null,"aggregateVersion":1}
                """));
        return Map.copyOf(fixtures);
    }

    private static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] frozenBytes() throws Exception {
        try (InputStream input = Sys008ClosedTransportSchemaTest.class
                .getResourceAsStream(
                        "/contracts/hris/sys008-g2-transport-schemas.v1.json")) {
            if (input == null) {
                throw new IllegalStateException(
                        "Frozen SYS-008 G2 transport fixture is unavailable");
            }
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
