package com.dwp.platform.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.contracts.hris.xcon.v1.Xcon022EmployeeChangeAppliedV1;
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

class Xcon022EmployeeChangeAppliedAsyncApiContractTest {

    private static final String EVENT = "EmployeeChangeApplied.v1";
    private static final String MESSAGE_KEY = "employeeChangeApplied";
    private static final String ENVELOPE = "EmployeeChangeAppliedEnvelope";
    private static final String DATA = "EmployeeChangeAppliedData";
    private static final String CANONICAL_REF =
            "../hris/canonical/schemas/cross-module-canonical-schemas.v1.json#/$defs/XCON_022";

    @Test
    void asyncApiDelegatesToTheExactCanonicalXcon022Shape() throws Exception {
        JsonNode asyncApi = asyncApi();
        JsonNode canonicalDocument = json(
                "contracts/hris/canonical/schemas/cross-module-canonical-schemas.v1.json");
        JsonNode canonical = canonicalDocument.at("/$defs/XCON_022");
        List<String> runtimeFields = Arrays.stream(
                        Xcon022EmployeeChangeAppliedV1.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(schema(asyncApi, DATA).path("$ref").asText()).isEqualTo(CANONICAL_REF);
        assertThat(canonical.path("title").asText()).isEqualTo(EVENT);
        assertThat(canonical.path("type").asText()).isEqualTo("object");
        assertThat(canonical.path("additionalProperties").isBoolean()).isTrue();
        assertThat(canonical.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(canonical.path("properties")))
                .containsExactlyElementsOf(runtimeFields);
        assertThat(textValues(canonical.path("required")))
                .containsExactlyElementsOf(runtimeFields);

        JsonNode changed = canonical.at("/properties/changedFieldGroups");
        assertThat(changed.path("type").asText()).isEqualTo("array");
        assertThat(changed.path("minItems").asInt()).isOne();
        assertThat(changed.path("maxItems").asInt()).isEqualTo(32);
        assertThat(changed.path("uniqueItems").asBoolean()).isTrue();
        assertThat(changed.at("/items/$ref").asText()).isEqualTo("#/$defs/Code");
        assertThat(canonical.at("/properties/schemaVersion/minimum").asInt()).isOne();
        assertThat(canonical.at("/properties/schemaVersion/maximum").asInt()).isOne();
        assertThat(canonical.at("/properties/tenantId/minimum").asLong()).isOne();
        assertThat(canonicalDocument.at("/$defs/Code/pattern").asText())
                .isEqualTo("^[A-Z0-9][A-Z0-9._-]{0,79}$");
        assertThat(canonicalDocument.at("/$defs/Digest/pattern").asText())
                .isEqualTo("^[0-9a-f]{64}$");

        assertTypedMessage(asyncApi);
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
                .isEqualTo("EmployeeChangeRequest");
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

    private static JsonNode json(String relative) throws IOException {
        return new ObjectMapper().readTree(rootFile(relative).toFile());
    }

    private static Path rootFile(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("..").resolve(relative);
    }
}
