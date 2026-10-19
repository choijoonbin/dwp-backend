package com.dwp.platform.contract;

import com.dwp.contracts.hris.xcon.v1.Xcon023WorkerDependentEligibilityChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon024WorkerTaxIdentityChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon025WorkerBankAccountTokenChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon026EmploymentContractIssuedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon026EmploymentContractIssuedV1.DocumentReference;
import com.dwp.platform.contracts.hris.generated.EmploymentContractTemplateSnapshotV1;
import com.dwp.platform.contracts.hris.generated.EmploymentContractTemplateSnapshotV1.TemplateContentReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrisHrm011CanonicalContractTest {

    private static final String DIGEST = "a".repeat(64);
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-19T00:00:00Z");
    private static final LocalDate FROM = LocalDate.parse("2026-10-19");
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void exposesStrictMinimalInvalidationEventsWithoutRestrictedValues() throws Exception {
        Object[] values = {dependentEvent(), taxEvent(), bankEvent()};
        Class<?>[] types = {
                Xcon023WorkerDependentEligibilityChangedV1.class,
                Xcon024WorkerTaxIdentityChangedV1.class,
                Xcon025WorkerBankAccountTokenChangedV1.class
        };

        for (int index = 0; index < values.length; index++) {
            String json = mapper.writeValueAsString(values[index]);
            assertThat(json)
                    .doesNotContain("eligibilityFacts")
                    .doesNotContain("taxIdentifierToken")
                    .doesNotContain("bankAccountToken")
                    .doesNotContain("maskedAccountDisplay");
            assertThat(mapper.readValue(json, types[index])).isEqualTo(values[index]);
            for (String forbidden : new String[]{
                    "rawTaxIdentifier", "bankAccountToken", "dependentProfile"}) {
                String drift = json.substring(0, json.length() - 1)
                        + ",\"" + forbidden + "\":\"forbidden\"}";
                int current = index;
                assertThatThrownBy(() -> mapper.readValue(drift, types[current]))
                        .isInstanceOf(Exception.class);
            }
        }
    }

    @Test
    void enforcesFactIdentityVersionEqualityAndHalfOpenPeriods() {
        Xcon023WorkerDependentEligibilityChangedV1 dependent = dependentEvent();
        assertThatThrownBy(() -> new Xcon023WorkerDependentEligibilityChangedV1(
                id(99), dependent.aggregateVersion(), dependent.causationId(),
                dependent.correlationId(), dependent.dependentPublicId(), FROM, null,
                dependent.eventId(), dependent.newAggregateVersion(), OCCURRED_AT,
                DIGEST, 1, 7L, dependent.workerId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateId");
        assertThatThrownBy(() -> new Xcon024WorkerTaxIdentityChangedV1(
                id(20), 3L, id(2), id(3), FROM, null, id(4), 4L, OCCURRED_AT,
                DIGEST, 1, id(20), 7L, id(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateVersion");
        assertThatThrownBy(() -> new Xcon025WorkerBankAccountTokenChangedV1(
                id(30), 3L, id(2), id(3), FROM, FROM, id(4), 3L, OCCURRED_AT,
                DIGEST, id(30), 1, 7L, id(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveTo");
    }

    @Test
    void sealsEmploymentContractDocumentReferenceAndEnvelope() throws Exception {
        Xcon026EmploymentContractIssuedV1 value = contractEvent();
        assertRecordFields(value.getClass(),
                "aggregateId", "aggregateVersion", "causationId", "contractPublicId",
                "contractType", "correlationId", "documentReference", "eventId",
                "occurredAt", "payloadDigest", "schemaVersion", "tenantId",
                "validFrom", "validTo", "workerPublicId");
        assertRecordFields(value.documentReference().getClass(),
                "objectPublicId", "objectVersion", "contentSha256");

        String json = mapper.writeValueAsString(value);
        assertThat(json).doesNotContain("bytes").doesNotContain("url");
        assertThat(mapper.readValue(json, Xcon026EmploymentContractIssuedV1.class))
                .isEqualTo(value);
        assertThatThrownBy(() -> mapper.readValue(
                json.substring(0, json.length() - 1) + ",\"downloadUrl\":\"x\"}",
                Xcon026EmploymentContractIssuedV1.class))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> new Xcon026EmploymentContractIssuedV1(
                id(99), value.aggregateVersion(), value.causationId(),
                value.contractPublicId(), value.contractType(), value.correlationId(),
                value.documentReference(), value.eventId(), value.occurredAt(),
                value.payloadDigest(), value.schemaVersion(), value.tenantId(),
                value.validFrom(), value.validTo(), value.workerPublicId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateId");
    }

    @Test
    void requiresPublishedEffectiveScopedEmploymentContractTemplateSnapshot()
            throws Exception {
        EmploymentContractTemplateSnapshotV1 value = templateSnapshot();
        assertRecordFields(value.getClass(),
                "approvalReceiptId", "contentSchemaVersion", "contractType",
                "effectiveFrom", "effectiveTo", "locale", "payloadDigest",
                "publishedAt", "scopeId", "scopeType", "sourceRevision", "state",
                "templateContentReference", "templatePublicId", "templateType",
                "templateVersion", "tenantId");
        assertRecordFields(value.templateContentReference().getClass(),
                "objectPublicId", "objectVersion", "contentSha256");

        String json = mapper.writeValueAsString(value);
        assertThat(mapper.readValue(json, EmploymentContractTemplateSnapshotV1.class))
                .isEqualTo(value);
        assertThatThrownBy(() -> mapper.readValue(
                json.replace("\"PUBLISHED\"", "\"DRAFT\""),
                EmploymentContractTemplateSnapshotV1.class))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> new EmploymentContractTemplateSnapshotV1(
                id(50), "EmploymentContractTemplateContent.v1", "REGULAR",
                FROM, null, "ko-KR", DIGEST, OCCURRED_AT, null,
                "LEGAL_ENTITY", 1L, "PUBLISHED", value.templateContentReference(),
                id(51), "EMPLOYMENT_CONTRACT", 1L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scopeId");
        assertThatThrownBy(() -> new EmploymentContractTemplateSnapshotV1(
                id(50), "EmploymentContractTemplateContent.v1", "REGULAR",
                FROM, FROM, "ko-KR", DIGEST, OCCURRED_AT, id(52),
                "LEGAL_ENTITY", 1L, "PUBLISHED", value.templateContentReference(),
                id(51), "EMPLOYMENT_CONTRACT", 1L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveTo");
        assertThatThrownBy(() -> new EmploymentContractTemplateSnapshotV1(
                id(50), "employment-contract", "REGULAR", FROM, null, "ko-KR",
                DIGEST, OCCURRED_AT, id(52), "LEGAL_ENTITY", 1L, "PUBLISHED",
                value.templateContentReference(), id(51), "EMPLOYMENT_CONTRACT",
                1L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentSchemaVersion");
        assertThatThrownBy(() -> new EmploymentContractTemplateSnapshotV1(
                id(50), "EmploymentContractTemplateContent.v1", "REGULAR",
                FROM, null, "ko-KR", DIGEST, OCCURRED_AT, id(52),
                "LEGAL_ENTITY", 0L, "PUBLISHED", value.templateContentReference(),
                id(51), "EMPLOYMENT_CONTRACT", 1L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceRevision");
        assertThatThrownBy(() -> new EmploymentContractTemplateSnapshotV1(
                id(50), "EmploymentContractTemplateContent.v1", "REGULAR",
                FROM, null, "ko-KR", DIGEST, OCCURRED_AT, id(52),
                "LEGAL_ENTITY", 1L, "PUBLISHED", value.templateContentReference(),
                id(51), "EMPLOYMENT_CONTRACT", 1L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> new EmploymentContractTemplateSnapshotV1(
                id(50), "EmploymentContractTemplateContent.v1", "REGULAR",
                FROM, null, "ko-KR", DIGEST, null, id(52), "LEGAL_ENTITY", 1L,
                "PUBLISHED", value.templateContentReference(), id(51),
                "EMPLOYMENT_CONTRACT", 1L, 7L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("publishedAt");
    }

    @Test
    void bindsContentSchemaVersionToTheExactTypedPdx001SchemaVersion() throws Exception {
        Path direct = Path.of(
                "contracts/hris/canonical/schemas/platform-dependency-canonical-schemas.v1.json");
        Path schemaPath = Files.exists(direct) ? direct : Path.of("..").resolve(direct);
        JsonNode schema = mapper.readTree(schemaPath.toFile());

        String pdx001SchemaVersionRef = schema
                .at("/$defs/PDX_001/properties/payloadSchemaVersion/$ref").asText();
        String pdx015SchemaVersionRef = schema
                .at("/$defs/PDX_015/properties/contentSchemaVersion/$ref").asText();

        assertThat(pdx015SchemaVersionRef)
                .isEqualTo("#/$defs/SchemaVersion")
                .isEqualTo(pdx001SchemaVersionRef);
        assertThat(schema.at("/$defs/SchemaVersion/type").asText()).isEqualTo("string");
        assertThat(schema.at("/$defs/SchemaVersion/pattern").asText())
                .isEqualTo("^[A-Za-z][A-Za-z0-9._-]{0,127}\\.v[1-9][0-9]*$");
        assertThat(schema.at("/$defs/PDX_015/x-invariants/contentBinding").asText())
                .isEqualTo("CONTENT_REFERENCE_DIGEST_AND_CONTENT_SCHEMA_VERSION_MATCH_PUBLISHED_TEMPLATE");
        assertThat(Arrays.stream(EmploymentContractTemplateSnapshotV1.class.getRecordComponents())
                .filter(component -> component.getName().equals("contentSchemaVersion"))
                .map(component -> component.getType().getName())
                .toList())
                .containsExactly(String.class.getName());
    }

    private static Xcon023WorkerDependentEligibilityChangedV1 dependentEvent() {
        return new Xcon023WorkerDependentEligibilityChangedV1(
                id(10), 3L, id(2), id(3), id(10), FROM, null, id(4), 3L,
                OCCURRED_AT, DIGEST, 1, 7L, id(5));
    }

    private static Xcon024WorkerTaxIdentityChangedV1 taxEvent() {
        return new Xcon024WorkerTaxIdentityChangedV1(
                id(20), 3L, id(2), id(3), FROM, null, id(4), 3L, OCCURRED_AT,
                DIGEST, 1, id(20), 7L, id(5));
    }

    private static Xcon025WorkerBankAccountTokenChangedV1 bankEvent() {
        return new Xcon025WorkerBankAccountTokenChangedV1(
                id(30), 3L, id(2), id(3), FROM, null, id(4), 3L, OCCURRED_AT,
                DIGEST, id(30), 1, 7L, id(5));
    }

    private static Xcon026EmploymentContractIssuedV1 contractEvent() {
        return new Xcon026EmploymentContractIssuedV1(
                id(40), 1L, id(2), id(40), "REGULAR", id(3),
                new DocumentReference(id(41), 1L, DIGEST), id(4), OCCURRED_AT,
                DIGEST, 1, 7L, FROM, null, id(5));
    }

    private static EmploymentContractTemplateSnapshotV1 templateSnapshot() {
        return new EmploymentContractTemplateSnapshotV1(
                id(50), "EmploymentContractTemplateContent.v1", "REGULAR",
                FROM, null, "ko-KR", DIGEST, OCCURRED_AT, id(52),
                "LEGAL_ENTITY", 1L, "PUBLISHED",
                new TemplateContentReference(id(53), 1L, DIGEST), id(51),
                "EMPLOYMENT_CONTRACT", 1L, 7L);
    }

    private static void assertRecordFields(Class<?> type, String... fields) {
        assertThat(Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(fields);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
