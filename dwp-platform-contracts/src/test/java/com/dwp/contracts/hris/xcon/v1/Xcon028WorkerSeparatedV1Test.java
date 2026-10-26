package com.dwp.contracts.hris.xcon.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Xcon028WorkerSeparatedV1Test {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void exposesTheExactClosedCanonicalDataAndRoundTrips() throws Exception {
        Xcon028WorkerSeparatedV1 value = event();
        String encoded = mapper.writeValueAsString(value);

        assertThat(Arrays.stream(value.getClass().getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "aggregateId", "aggregateVersion", "causationId",
                        "casePublicId", "correlationId", "effectiveDate", "eventId",
                        "occurredAt", "payloadDigest", "relationshipPublicId",
                        "schemaVersion", "separationType", "tenantId", "workerPublicId");
        assertThat(mapper.readTree(encoded).size()).isEqualTo(14);
        assertThat(mapper.readValue(encoded, Xcon028WorkerSeparatedV1.class))
                .isEqualTo(value);
    }

    @Test
    void rejectsMissingUnknownAndForbiddenPayloadFields() throws Exception {
        ObjectNode missing = (ObjectNode) mapper.valueToTree(event());
        missing.remove("payloadDigest");
        assertThatThrownBy(() -> mapper.treeToValue(
                missing, Xcon028WorkerSeparatedV1.class))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("missing required property payloadDigest");

        for (String forbidden : new String[] {
                "reasonCode", "lastWorkingDate", "taskDetails", "callbackDetails",
                "documentUrl", "personalIdentifier", "authorizationToken"
        }) {
            ObjectNode payload = (ObjectNode) mapper.valueToTree(event());
            payload.put(forbidden, "forbidden");
            assertThatThrownBy(() -> mapper.treeToValue(
                    payload, Xcon028WorkerSeparatedV1.class))
                    .as(forbidden)
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("unknown property " + forbidden);
        }
    }

    @Test
    void rejectsInvalidIdentityEnumAndCanonicalScalarConstraints() {
        Xcon028WorkerSeparatedV1 value = event();

        assertThatThrownBy(() -> copy(
                value, uuid(99), value.aggregateVersion(), value.payloadDigest(),
                value.schemaVersion(), value.separationType(), value.tenantId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateId must equal casePublicId");
        assertThatThrownBy(() -> copy(
                value, value.casePublicId(), 0L, value.payloadDigest(),
                value.schemaVersion(), value.separationType(), value.tenantId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregateVersion");
        assertThatThrownBy(() -> copy(
                value, value.casePublicId(), value.aggregateVersion(), "A".repeat(64),
                value.schemaVersion(), value.separationType(), value.tenantId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadDigest");
        assertThatThrownBy(() -> copy(
                value, value.casePublicId(), value.aggregateVersion(), value.payloadDigest(),
                2, value.separationType(), value.tenantId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> copy(
                value, value.casePublicId(), value.aggregateVersion(), value.payloadDigest(),
                value.schemaVersion(), "LEAVE", value.tenantId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("separationType");
        assertThatThrownBy(() -> copy(
                value, value.casePublicId(), value.aggregateVersion(), value.payloadDigest(),
                value.schemaVersion(), value.separationType(), 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    private static Xcon028WorkerSeparatedV1 event() {
        UUID casePublicId = uuid(1);
        return new Xcon028WorkerSeparatedV1(
                casePublicId,
                3L,
                uuid(2),
                casePublicId,
                uuid(3),
                LocalDate.parse("2026-10-26"),
                uuid(4),
                Instant.parse("2026-10-26T00:00:00Z"),
                "a".repeat(64),
                uuid(5),
                1,
                "TERMINATION",
                7L,
                uuid(6));
    }

    private static Xcon028WorkerSeparatedV1 copy(
            Xcon028WorkerSeparatedV1 value,
            UUID casePublicId,
            long aggregateVersion,
            String payloadDigest,
            int schemaVersion,
            String separationType,
            long tenantId) {
        return new Xcon028WorkerSeparatedV1(
                value.aggregateId(), aggregateVersion, value.causationId(), casePublicId,
                value.correlationId(), value.effectiveDate(), value.eventId(),
                value.occurredAt(), payloadDigest, value.relationshipPublicId(),
                schemaVersion, separationType, tenantId, value.workerPublicId());
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
