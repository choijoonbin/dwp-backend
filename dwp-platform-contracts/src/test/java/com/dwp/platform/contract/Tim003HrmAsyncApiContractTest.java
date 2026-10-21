package com.dwp.platform.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Tim003HrmAsyncApiContractTest {

    private static final String MESSAGE_KEY = "employmentChanged";
    private static final String EVENT_TYPE = "dwp.hris.people.employment-changed.v1";

    @Test
    void publishesTheExactXcon004WorkRelationshipTransportContract() throws Exception {
        JsonNode document = new ObjectMapper(new YAMLFactory()).readTree(
                rootFile("contracts/asyncapi/domain-events.yaml").toFile());
        JsonNode envelope = document.at("/components/schemas/EmploymentChangedEnvelope");
        JsonNode typed = envelope.at("/allOf/1");

        assertThat(document.at("/info/version").asText()).isEqualTo("1.3.0");
        assertThat(envelope.at("/allOf/0/$ref").asText())
                .isEqualTo("#/components/schemas/DomainEventEnvelope");
        assertThat(envelope.path("x-dwp-partition-key").asText())
                .isEqualTo("tenantId|source|aggregateType|aggregateId");
        assertThat(envelope.path("x-dwp-event-id-derivation").asText()).isEqualTo(
                "UUID_NAME_FROM_BYTES_LOWERCASE_EMPLOYMENT_EVENT_PUBLIC_ID_COLON_EmploymentChanged.v1_UTF8");
        assertThat(fieldNames(envelope.path("x-dwp-profile-metadata")))
                .containsExactly("xcon", "profile");
        assertThat(envelope.at("/x-dwp-profile-metadata/xcon").asText())
                .isEqualTo("XCON-004");
        assertThat(envelope.at("/x-dwp-profile-metadata/profile").asText())
                .isEqualTo("EmploymentChanged.v1");
        assertThat(envelope.path("x-dwp-subject-template").asText())
                .isEqualTo("WORK_RELATIONSHIP/{relationshipPublicId}");
        assertThat(envelope.path("x-dwp-aggregate-id-source").asText())
                .isEqualTo("relationshipPublicId");
        assertThat(envelope.path("x-dwp-aggregate-sequence-source").asText())
                .isEqualTo("newAggregateVersion");
        assertThat(fieldNames(envelope.path("x-dwp-envelope-data-equality")))
                .containsExactly("aggregateId", "aggregateSequence");
        assertThat(envelope.at("/x-dwp-envelope-data-equality/aggregateId").asText())
                .isEqualTo("relationshipPublicId");
        assertThat(envelope.at("/x-dwp-envelope-data-equality/aggregateSequence").asText())
                .isEqualTo("newAggregateVersion");

        assertThat(textValues(typed.path("required"))).containsExactly(
                "type", "schemaVersion", "source", "subject", "tenantId",
                "aggregateType", "aggregateId", "aggregateSequence",
                "correlationId", "causationId", "data");
        assertThat(typed.at("/properties/type/const").asText()).isEqualTo(EVENT_TYPE);
        assertThat(typed.at("/properties/schemaVersion/const").asInt()).isOne();
        assertThat(typed.at("/properties/source/const").asText())
                .isEqualTo("urn:dwp:people:hris:employment");
        assertThat(typed.at("/properties/subject/pattern").asText()).isEqualTo(
                "^WORK_RELATIONSHIP/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(typed.at("/properties/tenantId/minimum").asLong()).isOne();
        assertThat(typed.at("/properties/aggregateType/const").asText())
                .isEqualTo("WORK_RELATIONSHIP");
        assertThat(typed.at("/properties/aggregateId/format").asText())
                .isEqualTo("uuid");
        assertThat(typed.at("/properties/aggregateSequence/minimum").asLong()).isOne();
        assertThat(typed.at("/properties/correlationId/format").asText())
                .isEqualTo("uuid");
        assertThat(typed.at("/properties/causationId/format").asText())
                .isEqualTo("uuid");
        assertThat(typed.at("/properties/data/$ref").asText())
                .isEqualTo("#/components/schemas/EmploymentChangedData");
        assertThat(document.at("/components/schemas/EmploymentChangedData/$ref").asText())
                .isEqualTo("../hris/canonical/schemas/cross-module-canonical-schemas.v1.json#/$defs/XCON_004");
    }

    @Test
    void registersOneTypedMessageOnTheSharedDomainEventChannel() throws Exception {
        JsonNode document = new ObjectMapper(new YAMLFactory()).readTree(
                rootFile("contracts/asyncapi/domain-events.yaml").toFile());
        JsonNode message = document.at("/components/messages/" + MESSAGE_KEY);

        assertThat(message.path("name").asText()).isEqualTo(EVENT_TYPE);
        assertThat(message.path("contentType").asText()).isEqualTo("application/json");
        assertThat(message.at("/payload/$ref").asText())
                .isEqualTo("#/components/schemas/EmploymentChangedEnvelope");
        assertThat(document.at("/channels/domainEvents/address").asText())
                .isEqualTo("dwp.domain-events.v1");
        assertThat(document.at("/channels/domainEvents/messages/employmentChanged/$ref")
                .asText()).isEqualTo("#/components/messages/employmentChanged");
        assertThat(textValues(document.at("/operations/publishDomainEvent/messages")))
                .filteredOn("#/channels/domainEvents/messages/employmentChanged"::equals)
                .hasSize(1);
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.has("$ref")
                ? value.path("$ref").asText()
                : value.asText()));
        return values;
    }

    private static Path rootFile(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("..").resolve(relative);
    }
}
