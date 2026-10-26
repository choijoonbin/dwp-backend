package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.event.DomainEventContractRegistry;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Sys008EventContractTest {

    private static final UUID EXECUTION_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000805");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void acceptsTheClosedEventAndRoundTripsWithStrictJson() throws Exception {
        Sys008EventContract.Payload payload = payload("RECONCILED", DIGEST);
        ObjectMapper mapper = strictMapper();
        String json = mapper.writeValueAsString(payload);

        assertThat(Sys008EventContract.EVENT_ID).isEqualTo("SYS-EVT-005");
        assertThat(Sys008EventContract.EVENT_TYPE)
                .isEqualTo("dwp.hris.connector.execution.state-changed.v1");
        assertThat(mapper.readValue(json, Sys008EventContract.Payload.class))
                .isEqualTo(payload);
        assertThatThrownBy(() -> mapper.readValue(
                json.substring(0, json.length() - 1) + ",\"endpoint\":\"secret\"}",
                Sys008EventContract.Payload.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue(
                json.replace("\"state\":\"RECONCILED\"",
                        "\"state\":\"RECONCILED\",\"state\":\"FAILED\""),
                Sys008EventContract.Payload.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue(
                json + " {}", Sys008EventContract.Payload.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void registersOnlyTheExactSysEvt005VersionWithoutEnablingAProducer() {
        DomainEventContractRegistry registry = new DomainEventContractRegistry();

        new Sys008EventContract(registry);

        assertThat(registry.snapshot()).containsOnlyKeys(
                "dwp.hris.connector.execution.state-changed.v1");
        assertThat(registry.snapshot().get(Sys008EventContract.EVENT_TYPE))
                .isEqualTo(new DomainEventContractRegistry.Contract(1, 1));
        assertThat(Sys008EventContract.AGGREGATE_TYPE)
                .isEqualTo("HRIS_CONNECTOR_EXECUTION");
    }

    @Test
    void executableDraft202012SchemaRejectsUnknownAndMissingTerminalDigest()
            throws Exception {
        ObjectMapper mapper = strictMapper();
        JsonNode schemaNode;
        try (var input = Sys008EventContractTest.class.getResourceAsStream(
                Sys008EventContract.SCHEMA_RESOURCE)) {
            assertThat(input).isNotNull();
            schemaNode = mapper.readTree(input);
        }
        JsonSchema schema = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
        JsonNode valid = mapper.valueToTree(payload("RECONCILED", DIGEST));
        assertThat(schema.validate(valid)).isEmpty();

        JsonNode missingDigest = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingDigest)
                .putNull("reconciliationDigest");
        assertThat(schema.validate(missingDigest)).isNotEmpty();
        JsonNode unknown = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknown)
                .put("secretRef", "forbidden");
        assertThat(schema.validate(unknown)).isNotEmpty();
    }

    @Test
    void enforcesCountsStateAndTerminalDigestWithoutLeakingForbiddenData() {
        assertThatThrownBy(() -> new Sys008EventContract.Payload(
                EXECUTION_ID, "UNKNOWN", 10, 8, 2, 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
        assertThatThrownBy(() -> new Sys008EventContract.Payload(
                EXECUTION_ID, "FAILED", 10, 9, 2, 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed sourceCount");
        assertThatThrownBy(() -> new Sys008EventContract.Payload(
                EXECUTION_ID, "FAILED", 10, 7, 2, 3, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quarantinedCount");
        assertThatThrownBy(() -> payload("RECONCILED", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> payload("PARTIAL", "A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
        assertThat(payload("RESULT_UNKNOWN", null).reconciliationDigest()).isNull();
    }

    private static Sys008EventContract.Payload payload(
            String state, String reconciliationDigest) {
        return new Sys008EventContract.Payload(
                EXECUTION_ID, state, 10, 7, 2, 1, reconciliationDigest);
    }

    private static ObjectMapper strictMapper() {
        return new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
}
