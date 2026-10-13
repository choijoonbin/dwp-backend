package com.dwp.services.people.hris.performance.contracts.xcon;

import com.dwp.contracts.hris.xcon.v1.Xcon021ApprovedCompensationPlanSnapshotPublishedV2;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Xcon021ApprovedCompensationPlanSnapshotPublishedV2ProviderContractTest {

    private static final UUID CYCLE =
            UUID.fromString("00000000-0000-4000-8000-000000000021");
    private static final UUID APPROVAL =
            UUID.fromString("00000000-0000-4000-8000-000000000022");
    private static final UUID SNAPSHOT =
            UUID.fromString("00000000-0000-4000-8000-000000000023");
    private static final UUID CORRELATION =
            UUID.fromString("00000000-0000-4000-8000-000000000024");

    @Test
    void exposesOnlyTheExactSixteenFieldV2PublicationEnvelope() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var event = event();
        var json = mapper.readTree(mapper.writeValueAsString(event));

        assertThat(Arrays.stream(event.getClass().getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "aggregateId", "aggregateVersion", "approvalReceiptId",
                        "approvalRevision", "correlationId", "cycleId", "effectiveDate",
                        "fromState", "lineCount", "occurredAt", "payloadDigest", "planId",
                        "snapshotId", "snapshotRevision", "sourceVersion", "toState");
        assertThat(json.size()).isEqualTo(16);
        assertThat(json.fieldNames()).toIterable().doesNotContain(
                "lines", "workerId", "assignmentId", "approvedAmount");
        assertThat(event.aggregateId()).isEqualTo(CYCLE);
        assertThat(event.cycleId()).isEqualTo(CYCLE);
        assertThat(event.planId()).isEqualTo(CYCLE);
        assertThat(event.aggregateVersion()).isEqualTo(8L);
        assertThat(event.sourceVersion()).isEqualTo(7L);
        assertThat(event.approvalRevision()).isEqualTo(7L);
        assertThat(event.fromState()).isEqualTo("APPROVED");
        assertThat(event.toState()).isEqualTo("PUBLISHED");
    }

    @Test
    void productionSourcePublishesOnlyTheV2SnapshotEvent() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dwp/services/people/hr/performance/"
                        + "compensationplanning/CompensationPlanningService.java"),
                StandardCharsets.UTF_8);
        String repository = Files.readString(Path.of(
                "src/main/java/com/dwp/services/people/hr/performance/"
                        + "compensationplanning/CompensationPlanningRepository.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains("Xcon021ApprovedCompensationPlanSnapshotPublishedV2")
                .doesNotContain("CompensationPlanApproved.v1")
                .doesNotContain("ApprovedCompensationPlanSnapshotPublished.v1");
        assertThat(repository)
                .doesNotContain("CompensationPlanApproved.v1")
                .doesNotContain("ApprovedCompensationPlanSnapshotPublished.v1");
        assertThat(repository.lines()
                .filter(line -> line.contains(
                        "ApprovedCompensationPlanSnapshotPublished.v2")))
                .hasSize(1);
    }

    @Test
    void rejectsInvalidCardinalityAndPredecessorWireShapes() throws Exception {
        assertThatThrownBy(() -> new Xcon021ApprovedCompensationPlanSnapshotPublishedV2(
                CYCLE, 8L, APPROVAL, 7L, CORRELATION, CYCLE,
                LocalDate.parse("2027-01-01"), "APPROVED", 0,
                Instant.parse("2027-01-02T00:00:00Z"), "a".repeat(64),
                CYCLE, SNAPSHOT, 1L, 7L, "PUBLISHED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lineCount");

        var mapper = new ObjectMapper().findAndRegisterModules();
        var json = (com.fasterxml.jackson.databind.node.ObjectNode)
                mapper.readTree(mapper.writeValueAsString(event()));
        json.remove("sourceVersion");
        assertThatThrownBy(() -> mapper.treeToValue(
                json, Xcon021ApprovedCompensationPlanSnapshotPublishedV2.class))
                .isInstanceOf(Exception.class);
    }

    private static Xcon021ApprovedCompensationPlanSnapshotPublishedV2 event() {
        return new Xcon021ApprovedCompensationPlanSnapshotPublishedV2(
                CYCLE, 8L, APPROVAL, 7L, CORRELATION, CYCLE,
                LocalDate.parse("2027-01-01"), "APPROVED", 2,
                Instant.parse("2027-01-02T00:00:00Z"), "a".repeat(64),
                CYCLE, SNAPSHOT, 1L, 7L, "PUBLISHED");
    }
}
