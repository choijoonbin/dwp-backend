package com.dwp.contracts.hris.xcon.v1;

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

class Xcon028WorkerSeparatedAsyncApiContractTest {

    private static final String EVENT = "WorkerSeparated.v1";
    private static final String MESSAGE_KEY = "workerSeparated";
    private static final String ENVELOPE = "WorkerSeparatedEnvelope";
    private static final String DATA = "WorkerSeparatedData";
    private static final String DEFINITION = "XCON_028";
    private static final String CANONICAL_REF =
            "../hris/canonical/schemas/cross-module-canonical-schemas.v1.json#/$defs/"
                    + DEFINITION;
    private static final List<String> FIELDS = List.of(
            "aggregateId", "aggregateVersion", "causationId", "casePublicId",
            "correlationId", "effectiveDate", "eventId", "occurredAt",
            "payloadDigest", "relationshipPublicId", "schemaVersion",
            "separationType", "tenantId", "workerPublicId");

    @Test
    void bindsTheExactClosedCanonicalSchemaAndDesignConsumers() throws Exception {
        JsonNode canonicalDocument = json(
                "contracts/hris/canonical/schemas/cross-module-canonical-schemas.v1.json");
        JsonNode canonical = canonicalDocument.at("/$defs/" + DEFINITION);
        List<String> runtimeFields = Arrays.stream(
                        Xcon028WorkerSeparatedV1.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(canonical.path("title").asText()).isEqualTo(EVENT);
        assertThat(canonical.path("type").asText()).isEqualTo("object");
        assertThat(canonical.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(canonical.path("properties")))
                .containsExactlyElementsOf(FIELDS);
        assertThat(textValues(canonical.path("required")))
                .containsExactlyElementsOf(FIELDS);
        assertThat(runtimeFields).containsExactlyElementsOf(FIELDS);
        assertThat(textValues(canonical.at("/properties/separationType/enum")))
                .containsExactly(
                        "RESIGNATION", "TERMINATION", "RETIREMENT", "END_OF_CONTRACT");
        assertThat(fieldNames(canonical.path("properties"))).doesNotContain(
                "reasonCode", "lastWorkingDate", "taskDetails", "callbackDetails",
                "documentUrl", "personalIdentifier", "authorizationToken");

        JsonNode manifest = json(
                "contracts/hris/canonical/hris-contract-codegen-manifest.v1.json");
        JsonNode source = findBy(manifest.path("sources"), "sourceId", "xcon-v1");
        assertThat(source.path("expectedContractCount").asInt()).isEqualTo(28);
        JsonNode mapping = findBy(manifest.path("contracts"), "schemaDefinition", DEFINITION);
        assertThat(mapping.path("javaType").asText())
                .isEqualTo("com.dwp.contracts.hris.xcon.v1.Xcon028WorkerSeparatedV1");
        assertThat(mapping.path("producer").asText()).isEqualTo("HRIS-HRM");
        assertThat(textValues(mapping.path("consumers")))
                .containsExactly("HRIS-PER", "HRIS-TIM", "HRIS-PAY");
    }

    @Test
    void publishesOnlyTheTypedCatalogContractWithExactEnvelopeEqualities()
            throws Exception {
        JsonNode document = asyncApi();
        JsonNode envelope = schema(document, ENVELOPE);

        assertThat(schema(document, DATA).path("$ref").asText())
                .isEqualTo(CANONICAL_REF);
        assertThat(envelope.path("x-dwp-partition-key").asText())
                .isEqualTo("tenantId|source|aggregateType|aggregateId");
        assertThat(envelope.path("x-dwp-event-id-derivation").asText())
                .isEqualTo("UUID_NAME_FROM_BYTES_TENANT_ID_COLON_CASE_PUBLIC_ID_COLON_"
                        + "AGGREGATE_VERSION_COLON_WorkerSeparated.v1_UTF8");
        assertThat(envelope.path("x-dwp-aggregate-id-source").asText())
                .isEqualTo("casePublicId");
        assertThat(envelope.path("x-dwp-aggregate-sequence-source").asText())
                .isEqualTo("aggregateVersion");

        JsonNode equality = envelope.path("x-dwp-envelope-data-equality");
        assertThat(fieldNames(equality)).containsExactly(
                "id", "tenantId", "aggregateId", "aggregateSequence",
                "correlationId", "causationId", "time", "schemaVersion");
        assertThat(equality.path("id").asText()).isEqualTo("eventId");
        assertThat(equality.path("tenantId").asText()).isEqualTo("tenantId");
        assertThat(equality.path("aggregateId").asText()).isEqualTo("casePublicId");
        assertThat(equality.path("aggregateSequence").asText())
                .isEqualTo("aggregateVersion");
        assertThat(equality.path("correlationId").asText())
                .isEqualTo("correlationId");
        assertThat(equality.path("causationId").asText()).isEqualTo("causationId");
        assertThat(equality.path("time").asText()).isEqualTo("occurredAt");
        assertThat(equality.path("schemaVersion").asText()).isEqualTo("schemaVersion");

        assertThat(envelope.at("/allOf/0/$ref").asText())
                .isEqualTo("#/components/schemas/DomainEventEnvelope");
        JsonNode typed = envelope.at("/allOf/1");
        assertThat(textValues(typed.path("required"))).containsExactly(
                "type", "schemaVersion", "source", "tenantId", "aggregateType",
                "correlationId", "causationId", "data");
        assertThat(typed.at("/properties/type/const").asText()).isEqualTo(EVENT);
        assertThat(typed.at("/properties/schemaVersion/const").asInt()).isOne();
        assertThat(typed.at("/properties/source/const").asText())
                .isEqualTo("dwp-people-server");
        assertThat(typed.at("/properties/aggregateType/const").asText())
                .isEqualTo("SeparationCase");
        assertThat(typed.at("/properties/data/$ref").asText())
                .isEqualTo("#/components/schemas/" + DATA);

        JsonNode message = document.at("/components/messages/" + MESSAGE_KEY);
        assertThat(message.path("name").asText()).isEqualTo(EVENT);
        assertThat(message.at("/payload/$ref").asText())
                .isEqualTo("#/components/schemas/" + ENVELOPE);
        assertThat(document.at(
                        "/channels/domainEvents/messages/" + MESSAGE_KEY + "/$ref")
                .asText()).isEqualTo("#/components/messages/" + MESSAGE_KEY);
        assertThat(textValues(document.at("/operations/publishDomainEvent/messages")))
                .contains("#/channels/domainEvents/messages/" + MESSAGE_KEY);

        document.path("operations").properties().forEach(entry -> {
            if ("receive".equals(entry.getValue().path("action").asText())) {
                assertThat(entry.getValue().toString()).doesNotContain(MESSAGE_KEY);
            }
        });
    }

    private static JsonNode findBy(JsonNode array, String field, String expected) {
        for (JsonNode item : array) {
            if (expected.equals(item.path(field).asText())) {
                return item;
            }
        }
        throw new AssertionError("missing " + field + "=" + expected);
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
