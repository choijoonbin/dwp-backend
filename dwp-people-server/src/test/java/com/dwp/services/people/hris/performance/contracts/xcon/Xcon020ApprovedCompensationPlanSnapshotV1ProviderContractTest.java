package com.dwp.services.people.hris.performance.contracts.xcon;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import com.dwp.services.people.hr.performance.compensationplanning.CompensationPlanningCanonicalizer;
import com.dwp.services.people.hr.performance.compensationplanning.CompensationPlanningDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Xcon020ApprovedCompensationPlanSnapshotV1ProviderContractTest {

    private static final UUID WORKER =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ASSIGNMENT =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID LINE =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID APPROVAL =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID CYCLE =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID SNAPSHOT =
            UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final LocalDate EFFECTIVE = LocalDate.parse("2027-01-01");
    private static final String LINE_DIGEST =
            "7a495f41045d7cee2a826359a4864f8c31caf27559933b0fca7b036bf19aeb7d";
    private static final String PAYLOAD_DIGEST =
            "7a87697291020bd5a1cd62b989e8f82ecc9c25cc444f111c206d41e22976416a";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CompensationPlanningCanonicalizer canonicalizer =
            new CompensationPlanningCanonicalizer(mapper);

    @Test
    void exposesTheExactElevenHeaderAndTenLineFields() throws Exception {
        Xcon020ApprovedCompensationPlanSnapshotV1 payload = payload();

        assertThat(Arrays.stream(payload.getClass().getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "approvalReceiptId", "cycleId", "effectiveFrom", "effectiveTo",
                        "lineCount", "lines", "payloadDigest", "planId", "snapshotId",
                        "snapshotRevision", "sourceVersion");
        assertThat(Arrays.stream(payload.lines().getFirst().getClass()
                        .getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "lineId", "lineSequence", "workerId", "assignmentId",
                        "componentCode", "currency", "approvedAmount",
                        "effectiveFrom", "effectiveTo", "lineDigest");
        assertThat(mapper.readTree(mapper.writeValueAsString(payload)).size()).isEqualTo(11);
        assertThat(mapper.readTree(mapper.writeValueAsString(payload))
                .path("lines").get(0).size()).isEqualTo(10);
    }

    @Test
    void preservesTheSealedKnownAnswerDigestsAndCanonicalMoney() {
        assertThat(canonicalizer.lineDigest(
                LINE, 1, WORKER, ASSIGNMENT, "BASE", "USD", "1234.5",
                EFFECTIVE, null)).isEqualTo(LINE_DIGEST);
        CompensationPlanningDtos.SnapshotLine line = line();
        assertThat(canonicalizer.payloadDigest(
                APPROVAL, CYCLE, EFFECTIVE, null, List.of(line),
                SNAPSHOT, 1L, 7L)).isEqualTo(PAYLOAD_DIGEST);
        assertThat(payload().lines().getFirst().approvedAmount())
                .isEqualByComparingTo(new BigDecimal("1234.5"));
    }

    @Test
    void rejectsDuplicateIdentitiesAndNonContiguousOrder() {
        var valid = payload().lines().getFirst();
        assertThatThrownBy(() -> new Xcon020ApprovedCompensationPlanSnapshotV1(
                APPROVAL, CYCLE, EFFECTIVE, null, 2, List.of(valid, valid),
                PAYLOAD_DIGEST, CYCLE, SNAPSHOT, 1L, 7L))
                .isInstanceOf(IllegalArgumentException.class);

        var second = new Xcon020ApprovedCompensationPlanSnapshotV1
                .ApprovedCompensationPlanLine(
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                3, WORKER, ASSIGNMENT, "BONUS", "USD", BigDecimal.ONE,
                EFFECTIVE, null, "b".repeat(64));
        assertThatThrownBy(() -> new Xcon020ApprovedCompensationPlanSnapshotV1(
                APPROVAL, CYCLE, EFFECTIVE, null, 2, List.of(valid, second),
                PAYLOAD_DIGEST, CYCLE, SNAPSHOT, 1L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contiguous");
    }

    private Xcon020ApprovedCompensationPlanSnapshotV1 payload() {
        var line = new Xcon020ApprovedCompensationPlanSnapshotV1
                .ApprovedCompensationPlanLine(
                LINE, 1, WORKER, ASSIGNMENT, "BASE", "USD",
                new BigDecimal("1234.5"), EFFECTIVE, null, LINE_DIGEST);
        return new Xcon020ApprovedCompensationPlanSnapshotV1(
                APPROVAL, CYCLE, EFFECTIVE, null, 1, List.of(line),
                PAYLOAD_DIGEST, CYCLE, SNAPSHOT, 1L, 7L);
    }

    private CompensationPlanningDtos.SnapshotLine line() {
        return new CompensationPlanningDtos.SnapshotLine(
                LINE, 1, UUID.fromString("88888888-8888-4888-8888-888888888888"),
                WORKER, ASSIGNMENT, "BASE", "USD", "1234.5",
                EFFECTIVE, null, LINE_DIGEST);
    }
}
