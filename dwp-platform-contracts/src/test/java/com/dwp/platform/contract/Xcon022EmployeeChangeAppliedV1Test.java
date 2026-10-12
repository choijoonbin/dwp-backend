package com.dwp.platform.contract;

import com.dwp.contracts.hris.xcon.v1.Xcon022EmployeeChangeAppliedV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Xcon022EmployeeChangeAppliedV1Test {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void exposesTheExactCanonicalEnvelopeAndEmployeeChangePayload() throws Exception {
        Xcon022EmployeeChangeAppliedV1 value = event();
        String json = mapper.writeValueAsString(value);

        assertThat(Arrays.stream(value.getClass().getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "aggregateId", "aggregateVersion", "appliedTargetVersion",
                        "causationId", "changedFieldGroups", "correlationId",
                        "effectiveDate", "eventId", "occurredAt", "payloadDigest",
                        "requestPublicId", "schemaVersion", "targetAggregatePublicId",
                        "targetAggregateType", "tenantId");
        assertThat(mapper.readTree(json).size()).isEqualTo(15);
        assertThat(mapper.readValue(json, Xcon022EmployeeChangeAppliedV1.class))
                .isEqualTo(value);
    }

    @Test
    void rejectsEnvelopeDriftUnknownFieldsAndInvalidFieldGroups() throws Exception {
        String json = mapper.writeValueAsString(event());
        assertThatThrownBy(() -> mapper.readValue(
                json.replaceFirst(",\"tenantId\":7", ""),
                Xcon022EmployeeChangeAppliedV1.class))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue(
                json.replaceFirst(",\"correlationId\":\"[^\"]+\"", ""),
                Xcon022EmployeeChangeAppliedV1.class))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue(
                json.substring(0, json.length() - 1) + ",\"legacyRequestId\":true}",
                Xcon022EmployeeChangeAppliedV1.class))
                .isInstanceOf(Exception.class);

        Xcon022EmployeeChangeAppliedV1 value = event();
        assertThatThrownBy(() -> copy(value, List.of("PERSONAL", "PERSONAL"), 1, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> copy(value, List.of("personal"), 1, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format");
        assertThatThrownBy(() -> copy(value, value.changedFieldGroups(), 2, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> copy(value, value.changedFieldGroups(), 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    private static Xcon022EmployeeChangeAppliedV1 event() {
        return new Xcon022EmployeeChangeAppliedV1(
                uuid(1),
                3L,
                8L,
                uuid(2),
                List.of("ASSIGNMENT", "ORGANIZATION"),
                uuid(3),
                LocalDate.parse("2026-10-12"),
                uuid(4),
                Instant.parse("2026-10-12T00:00:00Z"),
                "a".repeat(64),
                uuid(5),
                1,
                uuid(6),
                "EMPLOYEE",
                7L);
    }

    private static Xcon022EmployeeChangeAppliedV1 copy(
            Xcon022EmployeeChangeAppliedV1 value,
            List<String> changedFieldGroups,
            int schemaVersion,
            long tenantId) {
        return new Xcon022EmployeeChangeAppliedV1(
                value.aggregateId(), value.aggregateVersion(),
                value.appliedTargetVersion(), value.causationId(), changedFieldGroups,
                value.correlationId(), value.effectiveDate(), value.eventId(),
                value.occurredAt(), value.payloadDigest(), value.requestPublicId(),
                schemaVersion, value.targetAggregatePublicId(),
                value.targetAggregateType(), tenantId);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
