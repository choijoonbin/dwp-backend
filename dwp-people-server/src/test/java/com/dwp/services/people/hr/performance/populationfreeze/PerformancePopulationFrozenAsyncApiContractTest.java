package com.dwp.services.people.hr.performance.populationfreeze;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerformancePopulationFrozenAsyncApiContractTest {

    private static final String EVENT = "PerformancePopulationFrozen.v1";
    private static final String MESSAGE_KEY = "performancePopulationFrozen";
    private static final String ENVELOPE = "PerformancePopulationFrozenEnvelope";
    private static final String DATA = "PerformancePopulationFrozenData";
    private static final String DATA_REF = "#/components/schemas/" + DATA;

    @Test
    void providerPayloadAndOutboxDiscriminatorMatchTheTypedAsyncApiContract()
            throws Exception {
        JsonNode document = asyncApi();
        JsonNode schema = schema(document, DATA);
        List<String> providerFields = Arrays.stream(Class.forName(
                        PopulationFreezeRepository.class.getName() + "$PopulationFrozenEvent")
                .getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").isBoolean()).isTrue();
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(schema.path("properties"))).containsExactlyElementsOf(providerFields);
        assertThat(textValues(schema.path("required"))).containsExactlyElementsOf(providerFields);

        assertUuid(schema, "cycleVersionId");
        assertUuid(schema, "populationFreezeId");
        assertUuid(schema, "workforceSnapshotId");
        assertThat(schema.at("/properties/workforceSnapshotRevision/minimum").asLong()).isOne();
        assertThat(schema.at("/properties/participantCount/minimum").asLong()).isZero();
        assertThat(schema.at("/properties/reviewerAssignmentCount/minimum").asLong()).isZero();
        assertThat(schema.at("/properties/contentHash/pattern").asText())
                .isEqualTo("^[0-9a-f]{64}$");

        assertTypedMessage(document);
        String repository = source(
                "dwp-people-server/src/main/java/com/dwp/services/people/hr/performance/"
                        + "populationfreeze/PopulationFreezeRepository.java");
        assertThat(repository)
                .contains("'PERFORMANCE_POPULATION_FREEZE'")
                .contains("'PerformancePopulationFrozen.v1'");
        String migration = source(
                "dwp-people-server/src/main/resources/db/performance-migration/"
                        + "V2__per_create_population_freeze.sql");
        assertThat(migration)
                .contains("participant_count >= 0 AND reviewer_assignment_count >= 0")
                .contains("workforce_snapshot_revision > 0")
                .contains("content_hash ~ '^[0-9a-f]{64}$'");
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
                .isEqualTo("PERFORMANCE_POPULATION_FREEZE");
        assertThat(typed.at("/properties/data/$ref").asText()).isEqualTo(DATA_REF);
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

    private static void assertUuid(JsonNode schema, String field) {
        assertThat(schema.at("/properties/" + field + "/type").asText()).isEqualTo("string");
        assertThat(schema.at("/properties/" + field + "/format").asText()).isEqualTo("uuid");
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
