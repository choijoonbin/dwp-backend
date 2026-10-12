package com.dwp.services.time.clockingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ClockEventRecordedAsyncApiContractTest {

    private static final String EVENT = "ClockEventRecorded.v1";
    private static final String MESSAGE_KEY = "clockEventRecorded";
    private static final String ENVELOPE = "ClockEventRecordedEnvelope";
    private static final String DATA = "ClockEventRecordedData";
    private static final Pattern PAYLOAD_FIELD = Pattern.compile("data\\.put\\(\"([^\"]+)\"");

    @Test
    void jdbcOutboxPayloadAndDiscriminatorsMatchTheTypedAsyncApiContract() throws Exception {
        JsonNode document = asyncApi();
        JsonNode schema = schema(document, DATA);
        String repository = source(
                "dwp-time-server/src/main/java/com/dwp/services/time/clockingestion/"
                        + "JdbcClockEventRepository.java");
        String insertOutbox = method(repository, "private void insertOutbox", "private <T> T");
        List<String> providerFields = payloadFields(insertOutbox);

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").isBoolean()).isTrue();
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(schema.path("properties"))).containsExactlyElementsOf(providerFields);
        assertThat(textValues(schema.path("required"))).containsExactlyElementsOf(providerFields);

        assertThat(textValues(schema.at("/properties/eventType/enum")))
                .containsExactlyElementsOf(Arrays.stream(ClockEventType.values())
                        .map(Enum::name)
                        .toList());
        assertThat(schema.at("/properties/sourceOffsetMinutes/minimum").asInt())
                .isEqualTo(-840);
        assertThat(schema.at("/properties/sourceOffsetMinutes/maximum").asInt())
                .isEqualTo(840);
        assertThat(schema.at("/properties/sourceSequence/type").toString())
                .isEqualTo("[\"integer\",\"null\"]");
        assertThat(schema.at("/properties/sourceSequence/minimum").asLong()).isOne();
        assertThat(schema.at("/properties/voidsClockEventId/type").toString())
                .isEqualTo("[\"string\",\"null\"]");
        assertThat(schema.at("/properties/voidsClockEventId/format").asText())
                .isEqualTo("uuid");
        assertThat(schema.at("/properties/payloadDigest/pattern").asText())
                .isEqualTo("^[0-9a-f]{64}$");
        assertThat(schema.at("/properties/sourceProvenanceDigest/pattern").asText())
                .isEqualTo("^[0-9a-f]{64}$");

        assertTypedMessage(document);
        assertThat(insertOutbox)
                .contains("'ClockStream'")
                .contains("'ClockEventRecorded', 1");
        String validator = source(
                "dwp-time-server/src/main/java/com/dwp/services/time/clockingestion/"
                        + "ClockEvidenceValidator.java");
        assertThat(validator)
                .contains("sourceUtcOffsetMinutes() < -840")
                .contains("sourceUtcOffsetMinutes() > 840")
                .contains("sourceSequence() <= 0");
    }

    private static void assertTypedMessage(JsonNode document) {
        JsonNode envelope = schema(document, ENVELOPE);
        assertThat(envelope.at("/allOf/0/$ref").asText())
                .isEqualTo("#/components/schemas/DomainEventEnvelope");
        JsonNode typed = envelope.at("/allOf/1");
        assertThat(textValues(typed.path("required")))
                .containsExactly("type", "schemaVersion", "aggregateType", "data");
        assertThat(typed.at("/properties/type/const").asText()).isEqualTo(EVENT);
        assertThat(typed.at("/properties/schemaVersion/const").asInt()).isOne();
        assertThat(typed.at("/properties/aggregateType/const").asText())
                .isEqualTo("ClockStream");
        assertThat(typed.at("/properties/data/$ref").asText())
                .isEqualTo("#/components/schemas/" + DATA);
        assertThat(schema(document, "DomainEventEnvelope")
                .path("additionalProperties").asBoolean()).isFalse();

        JsonNode message = document.at("/components/messages/" + MESSAGE_KEY);
        assertThat(message.path("name").asText()).isEqualTo(EVENT);
        assertThat(message.at("/payload/$ref").asText())
                .isEqualTo("#/components/schemas/" + ENVELOPE);
        assertThat(document.at("/channels/domainEvents/messages/" + MESSAGE_KEY + "/$ref")
                .asText()).isEqualTo("#/components/messages/" + MESSAGE_KEY);
        assertThat(textValues(document.at("/operations/publishDomainEvent/messages")))
                .contains("#/channels/domainEvents/messages/" + MESSAGE_KEY);
    }

    private static String method(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start);
        assertThat(start).isNotNegative();
        assertThat(end).isGreaterThan(start);
        return source.substring(start, end);
    }

    private static List<String> payloadFields(String source) {
        List<String> fields = new ArrayList<>();
        Matcher matcher = PAYLOAD_FIELD.matcher(source);
        while (matcher.find()) fields.add(matcher.group(1));
        return fields;
    }

    private static JsonNode schema(JsonNode document, String name) {
        return document.at("/components/schemas/" + name);
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> values = new ArrayList<>();
        object.fieldNames().forEachRemaining(values::add);
        return values;
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.has("$ref")
                ? value.path("$ref").asText()
                : value.asText()));
        return values;
    }

    private static JsonNode asyncApi() throws IOException {
        return new ObjectMapper(new YAMLFactory()).readTree(
                rootFile("contracts/asyncapi/domain-events.yaml").toFile());
    }

    private static String source(String relative) throws IOException {
        return Files.readString(rootFile(relative));
    }

    private static Path rootFile(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("..").resolve(relative);
    }
}
