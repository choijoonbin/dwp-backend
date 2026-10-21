package com.dwp.services.people.hris.contracts.tim003;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.contracts.hris.xcon.v1.Xcon004EmploymentChangedV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Xcon004EmploymentChangedV1ProviderContractTest {

    private static final List<String> FIELDS = List.of(
            "changeType", "effectiveDate", "newAggregateVersion",
            "relationshipPublicId");
    private static final UUID RELATIONSHIP_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void remainsTheClosedFourFieldEmploymentChangedV1Contract() throws Exception {
        assertThat(recordFields(Xcon004EmploymentChangedV1.class))
                .containsExactlyElementsOf(FIELDS);

        JsonNode canonical = canonical();
        JsonNode schema = canonical.at("/$defs/XCON_004");
        assertThat(schema.path("title").asText()).isEqualTo("EmploymentChanged.v1");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(schema.path("properties"))).containsExactlyElementsOf(FIELDS);
        assertThat(textValues(schema.path("required"))).containsExactlyElementsOf(FIELDS);

        JsonNode manifest = json(
                "contracts/hris/canonical/hris-contract-codegen-manifest.v1.json");
        JsonNode mapping = findContract(manifest, "XCON_004");
        assertThat(mapping.path("normalizedSchemaSha256").asText())
                .isEqualTo("42a913558210550a24550e55a2719d0f6002193a54a701feb135f6a0da3979c7");
    }

    @Test
    void preservesTheExactProviderWireShapeAndRevisionSemantics() throws Exception {
        Xcon004EmploymentChangedV1 value = new Xcon004EmploymentChangedV1(
                "RELATIONSHIP_UPDATED", LocalDate.parse("2026-10-21"), 7,
                RELATIONSHIP_ID);

        JsonNode encoded = MAPPER.valueToTree(value);
        assertThat(fieldNames(encoded)).containsExactlyElementsOf(FIELDS);
        assertThat(encoded.path("changeType").asText()).isEqualTo("RELATIONSHIP_UPDATED");
        assertThat(encoded.path("effectiveDate").asText()).isEqualTo("2026-10-21");
        assertThat(encoded.path("newAggregateVersion").asLong()).isEqualTo(7);
        assertThat(encoded.path("relationshipPublicId").asText())
                .isEqualTo(RELATIONSHIP_ID.toString());
        assertThat(MAPPER.treeToValue(encoded, Xcon004EmploymentChangedV1.class))
                .isEqualTo(value);

        assertThatThrownBy(() -> new Xcon004EmploymentChangedV1(
                "RELATIONSHIP_UPDATED", LocalDate.parse("2026-10-21"), 0,
                RELATIONSHIP_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> {
            com.fasterxml.jackson.databind.node.ObjectNode unexpected = encoded.deepCopy();
            unexpected.put("semanticExtension", "not-allowed");
            MAPPER.treeToValue(unexpected, Xcon004EmploymentChangedV1.class);
        }).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> {
            com.fasterxml.jackson.databind.node.ObjectNode missing = encoded.deepCopy();
            missing.remove("effectiveDate");
            MAPPER.treeToValue(missing, Xcon004EmploymentChangedV1.class);
        }).isInstanceOf(Exception.class);
    }

    private static JsonNode canonical() throws Exception {
        return json("contracts/hris/canonical/schemas/cross-module-canonical-schemas.v1.json");
    }

    private static JsonNode findContract(JsonNode manifest, String definition) {
        for (JsonNode contract : manifest.path("contracts")) {
            if (definition.equals(contract.path("schemaDefinition").asText())) {
                return contract;
            }
        }
        throw new AssertionError("missing contract " + definition);
    }

    private static List<String> recordFields(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static JsonNode json(String relative) throws Exception {
        return MAPPER.readTree(rootFile(relative).toFile());
    }

    private static Path rootFile(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("..").resolve(relative);
    }
}
