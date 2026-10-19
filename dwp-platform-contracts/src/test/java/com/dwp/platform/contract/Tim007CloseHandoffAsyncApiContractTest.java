package com.dwp.platform.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class Tim007CloseHandoffAsyncApiContractTest {

    private static final String TIM007_XCON_NORMALIZED_SHA256 =
            "1d19a5d44969e482ecd9bcbb80b8ecaa264c45a0429dbb5d1f2228336eb45a08";

    @Test
    void publishesOnlyTheThreeSealedTim007EventsAgainstImmutableXcon() throws Exception {
        JsonNode document = new ObjectMapper(new YAMLFactory()).readTree(
                rootFile("contracts/asyncapi/domain-events.yaml").toFile());
        assertThat(tim007XconNormalizedDigest())
                .isEqualTo(TIM007_XCON_NORMALIZED_SHA256);

        assertEvent(document, "timePeriodClosed", "TimePeriodClosed.v1",
                "TimePeriodClosedEnvelope", "TimePeriodClosedData", "ClosePeriod",
                "closePeriodId", "closeRevision", "XCON_009");
        assertEvent(document, "timePeriodReopened", "TimePeriodReopened.v1",
                "TimePeriodReopenedEnvelope", "TimePeriodReopenedData", "ClosePeriod",
                "closePeriodId", "newRevision", "XCON_010");
        assertEvent(document, "payrollTimeHandoffReady", "PayrollTimeHandoffReady.v1",
                "PayrollTimeHandoffReadyEnvelope", "PayrollTimeHandoffReadyData",
                "PayrollHandoff", "handoffId", "constant:1", "XCON_011");

        assertThat(document.toString())
                .doesNotContain("PayrollTimeHandoffOutcome", "payrollTimeHandoffOutcome");
    }

    private static void assertEvent(JsonNode document, String messageKey, String type,
            String envelopeName, String dataName, String aggregateType,
            String aggregateIdSource, String aggregateSequenceSource, String xcon) {
        JsonNode envelope = document.at("/components/schemas/" + envelopeName);
        assertThat(envelope.at("/x-dwp-partition-key").asText())
                .isEqualTo("tenantId|source|aggregateType|aggregateId");
        assertThat(envelope.at("/x-dwp-aggregate-id-source").asText())
                .isEqualTo(aggregateIdSource);
        assertThat(envelope.at("/x-dwp-aggregate-sequence-source").asText())
                .isEqualTo(aggregateSequenceSource);
        assertThat(envelope.at("/allOf/0/$ref").asText())
                .isEqualTo("#/components/schemas/DomainEventEnvelope");
        JsonNode typed = envelope.at("/allOf/1");
        assertThat(textValues(typed.path("required"))).containsExactly(
                "type", "schemaVersion", "source", "tenantId", "aggregateType",
                "correlationId", "causationId", "data");
        assertThat(typed.at("/properties/type/const").asText()).isEqualTo(type);
        assertThat(typed.at("/properties/schemaVersion/const").asInt()).isOne();
        assertThat(typed.at("/properties/source/const").asText())
                .isEqualTo("dwp-time-server");
        assertThat(typed.at("/properties/tenantId/minimum").asLong()).isOne();
        assertThat(typed.at("/properties/aggregateType/const").asText())
                .isEqualTo(aggregateType);
        assertThat(typed.at("/properties/correlationId/format").asText()).isEqualTo("uuid");
        assertThat(typed.at("/properties/causationId/format").asText()).isEqualTo("uuid");
        assertThat(typed.at("/properties/data/$ref").asText())
                .isEqualTo("#/components/schemas/" + dataName);
        assertThat(document.at("/components/schemas/" + dataName + "/$ref").asText())
                .isEqualTo("../hris/canonical/schemas/"
                        + "cross-module-canonical-schemas.v1.json#/$defs/" + xcon);

        assertThat(document.at("/components/messages/" + messageKey + "/name").asText())
                .isEqualTo(type);
        assertThat(document.at("/components/messages/" + messageKey + "/payload/$ref")
                .asText()).isEqualTo("#/components/schemas/" + envelopeName);
        assertThat(document.at("/channels/domainEvents/messages/" + messageKey + "/$ref")
                .asText()).isEqualTo("#/components/messages/" + messageKey);
        assertThat(textValues(document.at("/operations/publishDomainEvent/messages")))
                .contains("#/channels/domainEvents/messages/" + messageKey);
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.has("$ref")
                ? value.path("$ref").asText() : value.asText()));
        return values;
    }

    private static String tim007XconNormalizedDigest() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        JsonNode definitions = mapper.readTree(rootFile(
                "contracts/hris/canonical/schemas/cross-module-canonical-schemas.v1.json")
                .toFile()).path("$defs");
        Map<String, Object> selected = new TreeMap<>();
        for (String name : List.of("XCON_009", "XCON_010", "XCON_011")) {
            selected.put(name, mapper.convertValue(definitions.path(name), Object.class));
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(selected)));
    }

    private static Path rootFile(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("..").resolve(relative);
    }
}
