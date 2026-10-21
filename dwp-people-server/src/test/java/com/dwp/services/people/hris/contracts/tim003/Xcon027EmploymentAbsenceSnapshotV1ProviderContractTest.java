package com.dwp.services.people.hris.contracts.tim003;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class Xcon027EmploymentAbsenceSnapshotV1ProviderContractTest {

    private static final List<String> FIELDS = List.of(
            "relationshipPublicId", "relationshipRevision", "workerPublicId",
            "workerStatus", "relationshipType", "relationshipStatus",
            "legalEntityPublicId", "relationshipEffectiveFrom",
            "relationshipEffectiveTo", "effectiveOn", "assignments",
            "ownerRevision", "asOf", "validUntil", "ownerContractId",
            "ownerService", "purposeCode", "scopeDigest", "contentSha256");
    private static final List<String> ASSIGNMENT_FIELDS = List.of(
            "assignmentPublicId", "assignmentRevision", "assignmentStatus",
            "legalEntityPublicId", "organizationPublicId", "locationPublicId",
            "timeZone", "effectiveFrom", "effectiveTo");
    private static final UUID RELATIONSHIP_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID WORKER_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID LEGAL_ENTITY_ID =
            UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID LOCATION_ID =
            UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final LocalDate EFFECTIVE_ON = LocalDate.parse("2026-10-21");
    private static final Instant AS_OF = Instant.parse("2026-10-21T01:00:00.123456Z");
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void exposesTheExactClosedProviderAndNestedWireShapes() throws Exception {
        assertThat(recordFields(Xcon027EmploymentAbsenceSnapshotV1.class))
                .containsExactlyElementsOf(FIELDS);
        assertThat(recordFields(EmploymentAbsenceAssignment.class))
                .containsExactlyElementsOf(ASSIGNMENT_FIELDS);

        JsonNode canonical = canonical();
        JsonNode schema = canonical.at("/$defs/XCON_027");
        JsonNode assignmentSchema = canonical.at("/$defs/EmploymentAbsenceAssignment");
        assertThat(schema.path("title").asText())
                .isEqualTo("EmploymentAbsenceSnapshot.v1");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(schema.path("properties"))).containsExactlyElementsOf(FIELDS);
        assertThat(textValues(schema.path("required"))).containsExactlyElementsOf(FIELDS);
        assertThat(assignmentSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fieldNames(assignmentSchema.path("properties")))
                .containsExactlyElementsOf(ASSIGNMENT_FIELDS);
        assertThat(textValues(assignmentSchema.path("required")))
                .containsExactlyElementsOf(ASSIGNMENT_FIELDS);
        assertThat(schema.at("/properties/ownerContractId/const").asText())
                .isEqualTo("SVC-PEP-HRM-007/TIME_ABSENCE_V1");
        assertThat(schema.at("/properties/ownerService/const").asText())
                .isEqualTo("dwp-people-server");
        assertThat(schema.at("/properties/purposeCode/const").asText())
                .isEqualTo("TIM_LEAVE_EMPLOYMENT_RECOVERY");
    }

    @Test
    void roundTripsTheExactWireContractAndRejectsShapeDrift() throws Exception {
        EmploymentAbsenceAssignment assignment = assignment("Asia/Seoul");
        Xcon027EmploymentAbsenceSnapshotV1 value = snapshot(List.of(assignment), 9, 9);

        JsonNode encoded = MAPPER.valueToTree(value);
        assertThat(fieldNames(encoded)).containsExactlyElementsOf(FIELDS);
        assertThat(fieldNames(encoded.at("/assignments/0")))
                .containsExactlyElementsOf(ASSIGNMENT_FIELDS);
        assertThat(encoded.has("relationshipEffectiveTo")).isTrue();
        assertThat(encoded.at("/assignments/0").has("organizationPublicId")).isTrue();
        assertThat(MAPPER.treeToValue(encoded, Xcon027EmploymentAbsenceSnapshotV1.class))
                .isEqualTo(value);

        assertThatThrownBy(() -> {
            com.fasterxml.jackson.databind.node.ObjectNode unexpected = encoded.deepCopy();
            unexpected.put("providerExtension", true);
            MAPPER.treeToValue(unexpected, Xcon027EmploymentAbsenceSnapshotV1.class);
        }).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> {
            com.fasterxml.jackson.databind.node.ObjectNode missing = encoded.deepCopy();
            missing.remove("relationshipEffectiveTo");
            MAPPER.treeToValue(missing, Xcon027EmploymentAbsenceSnapshotV1.class);
        }).isInstanceOf(Exception.class);
    }

    @Test
    void permitsOnlyUtcAsTheFixedOffsetException() {
        assertThat(assignment("UTC").timeZone()).isEqualTo("UTC");
        assertThat(assignment("Asia/Seoul").timeZone()).isEqualTo("Asia/Seoul");

        for (String fixedOffsetAlias : List.of("GMT", "Etc/GMT+5", "+09:00")) {
            assertThatThrownBy(() -> assignment(fixedOffsetAlias))
                    .as("fixed-offset zone %s", fixedOffsetAlias)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeZone must be UTC");
        }
    }

    @Test
    void enforcesTheSealedOwnerAndSnapshotInvariants() {
        EmploymentAbsenceAssignment assignment = assignment("UTC");

        assertThatThrownBy(() -> snapshot(List.of(assignment), 9, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerRevision must equal relationshipRevision");
        assertThatThrownBy(() -> new EmploymentAbsenceAssignment(
                assignment.assignmentPublicId(), -1, "ACTIVE", LEGAL_ENTITY_ID,
                null, LOCATION_ID, "UTC", LocalDate.parse("2026-01-01"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Xcon027EmploymentAbsenceSnapshotV1(
                RELATIONSHIP_ID, 9, WORKER_ID, "ACTIVE", "EMPLOYEE", "ACTIVE",
                LEGAL_ENTITY_ID, LocalDate.parse("2026-01-01"), null, EFFECTIVE_ON,
                List.of(assignment), 9, AS_OF, AS_OF.plusSeconds(299),
                "SVC-PEP-HRM-007/TIME_ABSENCE_V1", "dwp-people-server",
                "TIM_LEAVE_EMPLOYMENT_RECOVERY", "a".repeat(64), "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validUntil must equal asOf plus 300 seconds");
    }

    private static EmploymentAbsenceAssignment assignment(String timeZone) {
        return new EmploymentAbsenceAssignment(
                UUID.fromString("50000000-0000-4000-8000-000000000001"),
                3, "ACTIVE", LEGAL_ENTITY_ID, null, LOCATION_ID, timeZone,
                LocalDate.parse("2026-01-01"), null);
    }

    private static Xcon027EmploymentAbsenceSnapshotV1 snapshot(
            List<EmploymentAbsenceAssignment> assignments,
            long relationshipRevision,
            long ownerRevision) {
        return new Xcon027EmploymentAbsenceSnapshotV1(
                RELATIONSHIP_ID, relationshipRevision, WORKER_ID, "ACTIVE",
                "EMPLOYEE", "ACTIVE", LEGAL_ENTITY_ID,
                LocalDate.parse("2026-01-01"), null, EFFECTIVE_ON, assignments,
                ownerRevision, AS_OF, AS_OF.plusSeconds(300),
                "SVC-PEP-HRM-007/TIME_ABSENCE_V1", "dwp-people-server",
                "TIM_LEAVE_EMPLOYMENT_RECOVERY", "a".repeat(64), "b".repeat(64));
    }

    private static JsonNode canonical() throws Exception {
        return json("contracts/hris/canonical/schemas/cross-module-canonical-schemas.v1.json");
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
