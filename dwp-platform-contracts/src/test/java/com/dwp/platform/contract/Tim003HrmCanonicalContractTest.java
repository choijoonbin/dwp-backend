package com.dwp.platform.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.contracts.hris.xcon.v1.Xcon004EmploymentChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;
import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1.EmploymentAbsenceAssignment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Tim003HrmCanonicalContractTest {

    private static final UUID RELATIONSHIP_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID WORKER_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID LEGAL_ENTITY_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final LocalDate EFFECTIVE_ON = LocalDate.parse("2026-10-21");
    private static final Instant AS_OF = Instant.parse("2026-10-21T01:00:00.123456Z");
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);

    @Test
    void preservesXcon004AsTheClosedFourFieldBusinessContract() throws Exception {
        assertThat(recordFields(Xcon004EmploymentChangedV1.class)).containsExactly(
                "changeType", "effectiveDate", "newAggregateVersion",
                "relationshipPublicId");

        JsonNode canonical = canonical();
        JsonNode schema = canonical.at("/$defs/XCON_004");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(schema.path("properties"))).containsExactly(
                "changeType", "effectiveDate", "newAggregateVersion",
                "relationshipPublicId");
        assertThat(textValues(schema.path("required"))).containsExactly(
                "changeType", "effectiveDate", "newAggregateVersion",
                "relationshipPublicId");

        JsonNode manifest = json(
                "contracts/hris/canonical/hris-contract-codegen-manifest.v1.json");
        JsonNode mapping = findContract(manifest, "XCON_004");
        assertThat(mapping.path("normalizedSchemaSha256").asText())
                .isEqualTo("42a913558210550a24550e55a2719d0f6002193a54a701feb135f6a0da3979c7");
    }

    @Test
    void generatesTheExactClosedXcon027AndNestedAssignmentShape() throws Exception {
        List<String> fields = List.of(
                "relationshipPublicId", "relationshipRevision", "workerPublicId",
                "workerStatus", "relationshipType", "relationshipStatus",
                "legalEntityPublicId", "relationshipEffectiveFrom",
                "relationshipEffectiveTo", "effectiveOn", "assignments",
                "ownerRevision", "asOf", "validUntil", "ownerContractId",
                "ownerService", "purposeCode", "scopeDigest", "contentSha256");
        List<String> assignmentFields = List.of(
                "assignmentPublicId", "assignmentRevision", "assignmentStatus",
                "legalEntityPublicId", "organizationPublicId", "locationPublicId",
                "timeZone", "effectiveFrom", "effectiveTo");

        assertThat(recordFields(Xcon027EmploymentAbsenceSnapshotV1.class))
                .containsExactlyElementsOf(fields);
        assertThat(recordFields(EmploymentAbsenceAssignment.class))
                .containsExactlyElementsOf(assignmentFields);

        JsonNode canonical = canonical();
        JsonNode schema = canonical.at("/$defs/XCON_027");
        JsonNode assignment = canonical.at("/$defs/EmploymentAbsenceAssignment");
        assertThat(schema.path("title").asText())
                .isEqualTo("EmploymentAbsenceSnapshot.v1");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(schema.path("properties"))).containsExactlyElementsOf(fields);
        assertThat(textValues(schema.path("required"))).containsExactlyElementsOf(fields);
        assertThat(assignment.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(assignment.path("properties")))
                .containsExactlyElementsOf(assignmentFields);
        assertThat(textValues(assignment.path("required")))
                .containsExactlyElementsOf(assignmentFields);
        assertThat(schema.at("/properties/assignments/minItems").asInt()).isZero();
        assertThat(schema.at("/properties/assignments/maxItems").asInt()).isEqualTo(1000);

        JsonNode manifest = json(
                "contracts/hris/canonical/hris-contract-codegen-manifest.v1.json");
        JsonNode mapping = findContract(manifest, "XCON_027");
        assertThat(mapping.path("javaType").asText()).isEqualTo(
                "com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1");
        assertThat(mapping.path("producer").asText()).isEqualTo("HRIS-HRM");
        assertThat(textValues(mapping.path("consumers"))).containsExactly("HRIS-TIM");
    }

    @Test
    void acceptsOnlyTheSealedCurrentBusinessDateSnapshotInvariants() {
        EmploymentAbsenceAssignment first = assignment(
                "00000000-0000-0000-0000-000000000001", 0, null, null);
        EmploymentAbsenceAssignment second = assignment(
                "00000000-0000-0000-0000-000000000002", 7,
                UUID.fromString("40000000-0000-0000-0000-000000000001"), "UTC");

        Xcon027EmploymentAbsenceSnapshotV1 snapshot = snapshot(
                List.of(first, second), 9, 9, "ACTIVE", "ACTIVE",
                AS_OF, AS_OF.plusSeconds(300));

        assertThat(snapshot.relationshipRevision()).isEqualTo(9);
        assertThat(snapshot.assignments()).containsExactly(first, second);
        assertThatThrownBy(() -> snapshot.assignments().add(first))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> assignment(
                "00000000-0000-0000-0000-000000000001", -1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assignment(
                "00000000-0000-0000-0000-000000000001", 0, null, "UTC"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assignment(
                "00000000-0000-0000-0000-000000000001", 0,
                UUID.randomUUID(), "+09:00"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(assignment(
                "00000000-0000-0000-0000-000000000001", 0,
                UUID.randomUUID(), "Asia/Seoul").timeZone())
                .isEqualTo("Asia/Seoul");
        assertThatThrownBy(() -> assignment(
                "00000000-0000-0000-0000-000000000001", 0,
                UUID.randomUUID(), "Etc/GMT+5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assignment(
                "00000000-0000-0000-0000-000000000001", 0,
                UUID.randomUUID(), "GMT"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(
                List.of(first), 9, 8, "ACTIVE", "ACTIVE",
                AS_OF, AS_OF.plusSeconds(300)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(
                List.of(first), 9, 9, "TERMINATED", "ACTIVE",
                AS_OF, AS_OF.plusSeconds(300)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(
                List.of(second, first), 9, 9, "ACTIVE", "ACTIVE",
                AS_OF, AS_OF.plusSeconds(300)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(
                List.of(first, first), 9, 9, "ACTIVE", "ACTIVE",
                AS_OF, AS_OF.plusSeconds(300)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(
                List.of(first), 9, 9, "ACTIVE", "ACTIVE",
                AS_OF, AS_OF.plusSeconds(299)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(
                List.of(first), 9, 9, "ACTIVE", "ACTIVE",
                Instant.parse("2026-10-21T01:00:00.123456789Z"),
                Instant.parse("2026-10-21T01:05:00.123456789Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void strictJsonRejectsUnknownAndMissingRequiredNullableFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Xcon027EmploymentAbsenceSnapshotV1 value = snapshot(
                List.of(assignment(
                        "00000000-0000-0000-0000-000000000001", 0, null, null)),
                0, 0, "ACTIVE", "ACTIVE", AS_OF, AS_OF.plusSeconds(300));
        JsonNode encoded = mapper.valueToTree(value);

        assertThat(fieldNames(encoded)).hasSize(19);
        assertThat(encoded.has("relationshipEffectiveTo")).isTrue();
        assertThat(encoded.at("/assignments/0").has("organizationPublicId")).isTrue();
        assertThat(mapper.treeToValue(encoded, Xcon027EmploymentAbsenceSnapshotV1.class))
                .isEqualTo(value);

        assertThatThrownBy(() -> {
            com.fasterxml.jackson.databind.node.ObjectNode unexpected = encoded.deepCopy();
            unexpected.put("unexpected", true);
            mapper.treeToValue(unexpected, Xcon027EmploymentAbsenceSnapshotV1.class);
        }).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> {
            JsonNode missing = encoded.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) missing)
                    .remove("relationshipEffectiveTo");
            mapper.treeToValue(missing, Xcon027EmploymentAbsenceSnapshotV1.class);
        }).isInstanceOf(Exception.class);
    }

    private static EmploymentAbsenceAssignment assignment(
            String id,
            long revision,
            UUID locationId,
            String timeZone) {
        return new EmploymentAbsenceAssignment(
                UUID.fromString(id), revision, "ACTIVE", LEGAL_ENTITY_ID,
                null, locationId, timeZone, LocalDate.parse("2026-01-01"), null);
    }

    private static Xcon027EmploymentAbsenceSnapshotV1 snapshot(
            List<EmploymentAbsenceAssignment> assignments,
            long relationshipRevision,
            long ownerRevision,
            String workerStatus,
            String relationshipStatus,
            Instant asOf,
            Instant validUntil) {
        return new Xcon027EmploymentAbsenceSnapshotV1(
                RELATIONSHIP_ID, relationshipRevision, WORKER_ID, workerStatus,
                "EMPLOYEE", relationshipStatus, LEGAL_ENTITY_ID,
                LocalDate.parse("2026-01-01"), null, EFFECTIVE_ON,
                assignments, ownerRevision, asOf, validUntil,
                "SVC-PEP-HRM-007/TIME_ABSENCE_V1", "dwp-people-server",
                "TIM_LEAVE_EMPLOYMENT_RECOVERY", DIGEST_A, DIGEST_B);
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
        return new ObjectMapper().readTree(rootFile(relative).toFile());
    }

    private static Path rootFile(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("..").resolve(relative);
    }
}
