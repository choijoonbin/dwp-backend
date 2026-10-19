package com.dwp.platform.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.contracts.hris.xcon.v1.Xcon007CompensationBasisChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon023WorkerDependentEligibilityChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon024WorkerTaxIdentityChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon025WorkerBankAccountTokenChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon026EmploymentContractIssuedV1;
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

class HrisHrm011AsyncApiContractTest {

    private static final String CANONICAL_PREFIX =
            "../hris/canonical/schemas/cross-module-canonical-schemas.v1.json#/$defs/";
    private static final List<String> ENVELOPE_REQUIRED = List.of(
            "type", "schemaVersion", "source", "tenantId", "aggregateType",
            "correlationId", "causationId", "data");

    @Test
    void publishesExactPeopleEventEnvelopesBackedByCanonicalXconSchemas()
            throws Exception {
        JsonNode asyncApi = asyncApi();
        JsonNode canonical = json(
                "contracts/hris/canonical/schemas/cross-module-canonical-schemas.v1.json");

        for (EventContract event : events()) {
            assertCanonicalDataShape(asyncApi, canonical, event);
            assertTypedMessage(asyncApi, event);
        }

        assertThat(schema(asyncApi, "DomainEventEnvelope")
                .path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(canonical.at("/$defs/DocumentReference/properties")))
                .containsExactly("objectPublicId", "objectVersion", "contentSha256");
        assertThat(textValues(canonical.at("/$defs/DocumentReference/required")))
                .containsExactly("objectPublicId", "objectVersion", "contentSha256");
    }

    @Test
    void invalidationEventsDoNotExposeRestrictedSourceValues() throws Exception {
        JsonNode canonical = json(
                "contracts/hris/canonical/schemas/cross-module-canonical-schemas.v1.json");

        for (String definition : List.of("XCON_023", "XCON_024", "XCON_025")) {
            JsonNode properties = canonical.at("/$defs/" + definition + "/properties");
            assertThat(fieldNames(properties)).doesNotContain(
                    "eligibilityFacts",
                    "taxIdentifierToken",
                    "bankAccountToken",
                    "maskedAccountDisplay",
                    "rawTaxIdentifier",
                    "dependentProfile");
        }
    }

    private static void assertCanonicalDataShape(
            JsonNode asyncApi,
            JsonNode canonicalDocument,
            EventContract event) {
        JsonNode canonical = canonicalDocument.at("/$defs/" + event.definition());
        List<String> runtimeFields = Arrays.stream(event.runtimeType().getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(schema(asyncApi, event.data()).path("$ref").asText())
                .isEqualTo(CANONICAL_PREFIX + event.definition());
        assertThat(canonical.path("title").asText()).isEqualTo(event.name());
        assertThat(canonical.path("type").asText()).isEqualTo("object");
        assertThat(canonical.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(canonical.path("properties")))
                .containsExactlyElementsOf(event.fields());
        assertThat(textValues(canonical.path("required")))
                .containsExactlyElementsOf(event.fields());
        assertThat(runtimeFields).containsExactlyElementsOf(event.fields());
    }

    private static void assertTypedMessage(JsonNode document, EventContract event) {
        JsonNode envelope = schema(document, event.envelope());
        assertThat(envelope.path("x-dwp-partition-key").asText())
                .isEqualTo("tenantId|source|aggregateType|aggregateId");
        assertThat(envelope.path("x-dwp-aggregate-id-source").asText())
                .isEqualTo(event.aggregateIdSource());
        assertThat(envelope.path("x-dwp-aggregate-sequence-source").asText())
                .isEqualTo(event.aggregateSequenceSource());
        if ("XCON_007".equals(event.definition())) {
            assertThat(envelope.path("x-dwp-envelope-data-equality").isMissingNode())
                    .isTrue();
            assertThat(envelope.path("x-dwp-envelope-data-equality-mode").asText())
                    .isEqualTo("LEGACY_ENVELOPE_OWNED_XCON_007_UNCHANGED");
            assertThat(envelope.path("x-dwp-envelope-data-equality-exception").asText())
                    .isEqualTo("XCON_007_HAS_NO_COMMON_EVENT_DATA_FIELDS_AND_REMAINS_IMMUTABLE");
        } else {
            JsonNode equality = envelope.path("x-dwp-envelope-data-equality");
            assertThat(fieldNames(equality)).containsExactly(
                    "id", "tenantId", "aggregateId", "aggregateSequence",
                    "correlationId", "causationId", "time", "schemaVersion");
            assertThat(equality.path("id").asText()).isEqualTo("eventId");
            assertThat(equality.path("tenantId").asText()).isEqualTo("tenantId");
            assertThat(equality.path("aggregateId").asText()).isEqualTo("aggregateId");
            assertThat(equality.path("aggregateSequence").asText())
                    .isEqualTo("aggregateVersion");
            assertThat(equality.path("correlationId").asText())
                    .isEqualTo("correlationId");
            assertThat(equality.path("causationId").asText()).isEqualTo("causationId");
            assertThat(equality.path("time").asText()).isEqualTo("occurredAt");
            assertThat(equality.path("schemaVersion").asText())
                    .isEqualTo("schemaVersion");
        }
        assertThat(envelope.at("/allOf/0/$ref").asText())
                .isEqualTo("#/components/schemas/DomainEventEnvelope");

        JsonNode typed = envelope.at("/allOf/1");
        assertThat(textValues(typed.path("required")))
                .containsExactlyElementsOf(ENVELOPE_REQUIRED);
        assertThat(typed.at("/properties/type/const").asText()).isEqualTo(event.name());
        assertThat(typed.at("/properties/schemaVersion/const").asInt()).isOne();
        assertThat(typed.at("/properties/source/const").asText())
                .isEqualTo("dwp-people-server");
        assertThat(typed.at("/properties/tenantId/minimum").asLong()).isOne();
        assertThat(typed.at("/properties/aggregateType/const").asText())
                .isEqualTo(event.aggregateType());
        assertThat(typed.at("/properties/correlationId/format").asText())
                .isEqualTo("uuid");
        assertThat(typed.at("/properties/causationId/format").asText())
                .isEqualTo("uuid");
        assertThat(typed.at("/properties/data/$ref").asText())
                .isEqualTo("#/components/schemas/" + event.data());

        JsonNode message = document.at("/components/messages/" + event.messageKey());
        assertThat(message.path("name").asText()).isEqualTo(event.name());
        assertThat(message.path("contentType").asText()).isEqualTo("application/json");
        assertThat(message.at("/payload/$ref").asText())
                .isEqualTo("#/components/schemas/" + event.envelope());
        assertThat(document.at(
                        "/channels/domainEvents/messages/" + event.messageKey() + "/$ref")
                .asText()).isEqualTo("#/components/messages/" + event.messageKey());
        assertThat(textValues(document.at("/operations/publishDomainEvent/messages")))
                .contains("#/channels/domainEvents/messages/" + event.messageKey());
    }

    private static List<EventContract> events() {
        return List.of(
                new EventContract(
                        "compensationBasisChanged",
                        "CompensationBasisChanged.v1",
                        "CompensationBasisChangedEnvelope",
                        "CompensationBasisChangedData",
                        "XCON_007",
                        "CompensationBasis",
                        "compensationBasisPublicId",
                        "newAggregateVersion",
                        Xcon007CompensationBasisChangedV1.class,
                        List.of(
                                "assignmentPublicId", "basisType",
                                "compensationBasisPublicId", "newAggregateVersion",
                                "validFrom", "validTo")),
                new EventContract(
                        "workerDependentEligibilityChanged",
                        "WorkerDependentEligibilityChanged.v1",
                        "WorkerDependentEligibilityChangedEnvelope",
                        "WorkerDependentEligibilityChangedData",
                        "XCON_023",
                        "WorkerDependentEligibility",
                        "dependentPublicId",
                        "newAggregateVersion",
                        Xcon023WorkerDependentEligibilityChangedV1.class,
                        List.of(
                                "aggregateId", "aggregateVersion", "causationId",
                                "correlationId", "dependentPublicId", "effectiveFrom",
                                "effectiveTo", "eventId", "newAggregateVersion",
                                "occurredAt", "payloadDigest", "schemaVersion",
                                "tenantId", "workerId")),
                new EventContract(
                        "workerTaxIdentityChanged",
                        "WorkerTaxIdentityChanged.v1",
                        "WorkerTaxIdentityChangedEnvelope",
                        "WorkerTaxIdentityChangedData",
                        "XCON_024",
                        "WorkerTaxIdentity",
                        "taxIdentityPublicId",
                        "newAggregateVersion",
                        Xcon024WorkerTaxIdentityChangedV1.class,
                        List.of(
                                "aggregateId", "aggregateVersion", "causationId",
                                "correlationId", "effectiveFrom", "effectiveTo",
                                "eventId", "newAggregateVersion", "occurredAt",
                                "payloadDigest", "schemaVersion", "taxIdentityPublicId",
                                "tenantId", "workerId")),
                new EventContract(
                        "workerBankAccountTokenChanged",
                        "WorkerBankAccountTokenChanged.v1",
                        "WorkerBankAccountTokenChangedEnvelope",
                        "WorkerBankAccountTokenChangedData",
                        "XCON_025",
                        "WorkerPaymentDestination",
                        "paymentDestinationPublicId",
                        "newAggregateVersion",
                        Xcon025WorkerBankAccountTokenChangedV1.class,
                        List.of(
                                "aggregateId", "aggregateVersion", "causationId",
                                "correlationId", "effectiveFrom", "effectiveTo",
                                "eventId", "newAggregateVersion", "occurredAt",
                                "payloadDigest", "paymentDestinationPublicId",
                                "schemaVersion", "tenantId", "workerId")),
                new EventContract(
                        "employmentContractIssued",
                        "EmploymentContractIssued.v1",
                        "EmploymentContractIssuedEnvelope",
                        "EmploymentContractIssuedData",
                        "XCON_026",
                        "EmploymentContract",
                        "contractPublicId",
                        "aggregateVersion",
                        Xcon026EmploymentContractIssuedV1.class,
                        List.of(
                                "aggregateId", "aggregateVersion", "causationId",
                                "contractPublicId", "contractType", "correlationId",
                                "documentReference", "eventId", "occurredAt",
                                "payloadDigest", "schemaVersion", "tenantId",
                                "validFrom", "validTo", "workerPublicId")));
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

    private record EventContract(
            String messageKey,
            String name,
            String envelope,
            String data,
            String definition,
            String aggregateType,
            String aggregateIdSource,
            String aggregateSequenceSource,
            Class<?> runtimeType,
            List<String> fields) {
    }
}
