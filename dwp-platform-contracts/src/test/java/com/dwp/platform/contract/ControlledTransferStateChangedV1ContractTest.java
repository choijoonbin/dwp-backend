package com.dwp.platform.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.transfer.v1.ControlledTransferStateChangedV1;
import com.dwp.platform.contracts.hris.transfer.v1.ControlledTransferStateChangedV1.OwnerModule;
import com.dwp.platform.contracts.hris.transfer.v1.ControlledTransferStateChangedV1.State;
import com.dwp.platform.contracts.hris.transfer.v1.ControlledTransferStateChangedV1.TransferKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ControlledTransferStateChangedV1ContractTest {

    private static final String TYPE =
            "dwp.hris.controlled-transfer.state-changed.v1";
    private static final String DIGEST = "a".repeat(64);

    @Test
    void typedPayloadAndAsyncApiSealTheExactSysEvt008Fields() throws Exception {
        JsonNode document = new ObjectMapper(new YAMLFactory()).readTree(
                rootFile("contracts/asyncapi/domain-events.yaml").toFile());
        JsonNode data = document.at(
                "/components/schemas/ControlledTransferStateChangedData");
        List<String> fields = Arrays.stream(
                        ControlledTransferStateChangedV1.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(data.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(data.path("properties")))
                .containsExactlyElementsOf(fields);
        assertThat(textValues(data.path("required")))
                .containsExactlyElementsOf(fields);
        assertThat(document.at(
                "/components/messages/controlledTransferStateChanged/name").asText())
                .isEqualTo(TYPE);
        assertThat(document.at(
                "/components/schemas/ControlledTransferStateChangedEnvelope/allOf/1"
                        + "/properties/type/const").asText()).isEqualTo(TYPE);
        assertThat(document.at(
                "/components/schemas/ControlledTransferStateChangedEnvelope/allOf/1"
                        + "/properties/schemaVersion/const").asInt()).isOne();
        assertThat(document.at(
                "/components/schemas/ControlledTransferStateChangedEnvelope/allOf/1"
                        + "/properties/aggregateType/const").asText())
                .isEqualTo("CONTROLLED_TRANSFER");
        assertThat(textValues(document.at("/operations/publishDomainEvent/messages")))
                .contains("#/channels/domainEvents/messages/controlledTransferStateChanged");
    }

    @Test
    void typedPayloadEnforcesDirectionDigestReceiptAndMediaBoundaries() {
        ControlledTransferStateChangedV1 outbound = event(
                TransferKind.EXPORT, State.COMPLETED, 7L, DIGEST);
        assertThat(outbound.egressPolicyRevision()).isEqualTo(7L);

        assertThat(event(TransferKind.INGEST, State.READY, null, null)
                .egressPolicyRevision()).isNull();
        assertThatThrownBy(() -> event(
                TransferKind.INGEST, State.READY, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INGEST");
        assertThatThrownBy(() -> event(
                TransferKind.EXPORT, State.READY, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Outbound");
        assertThatThrownBy(() -> event(
                TransferKind.EXPORT, State.COMPLETED, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("receipt digest");
    }

    private static ControlledTransferStateChangedV1 event(
            TransferKind kind, State state, Long egressRevision, String receiptDigest) {
        return new ControlledTransferStateChangedV1(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                OwnerModule.SYS, kind, "HRIS_INTEGRATION_ADMIN", state,
                "version-1", DIGEST, 42L, "application/json", DIGEST,
                egressRevision, receiptDigest, Instant.parse("2026-10-16T00:00:00Z"));
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> values = new ArrayList<>();
        object.fieldNames().forEachRemaining(values::add);
        return values;
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.has("$ref")
                ? value.path("$ref").asText() : value.asText()));
        return values;
    }

    private static Path rootFile(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("..").resolve(relative);
    }
}
