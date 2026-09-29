package com.dwp.platform.contract;

import com.dwp.contracts.hris.xcon.v1.Xcon021ApprovedCompensationPlanSnapshotPublishedV2;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Xcon021CompensationSnapshotPublicationV2Test {

    @Test
    void exposesTheExactSixteenFieldV2WireContract() throws Exception {
        var value = event(10);
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(value);

        assertThat(Arrays.stream(value.getClass().getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "aggregateId", "aggregateVersion", "approvalReceiptId",
                        "approvalRevision", "correlationId", "cycleId", "effectiveDate",
                        "fromState", "lineCount", "occurredAt", "payloadDigest", "planId",
                        "snapshotId", "snapshotRevision", "sourceVersion", "toState");
        assertThat(mapper.readTree(json).size()).isEqualTo(16);
        assertThat(mapper.readValue(
                json, Xcon021ApprovedCompensationPlanSnapshotPublishedV2.class))
                .isEqualTo(value);
    }

    @Test
    void rejectsCardinalityDriftAndPredecessorWireShapes() throws Exception {
        assertThatThrownBy(() -> event(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lineCount");
        assertThatThrownBy(() -> event(100_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lineCount");

        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(event(1));
        assertThatThrownBy(() -> mapper.readValue(
                json.replaceFirst(",\"sourceVersion\":1", ""),
                Xcon021ApprovedCompensationPlanSnapshotPublishedV2.class))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue(
                json.substring(0, json.length() - 1) + ",\"legacyApproval\":true}",
                Xcon021ApprovedCompensationPlanSnapshotPublishedV2.class))
                .isInstanceOf(Exception.class);
    }

    private static Xcon021ApprovedCompensationPlanSnapshotPublishedV2 event(int lineCount) {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000021");
        return new Xcon021ApprovedCompensationPlanSnapshotPublishedV2(
                id,
                1L,
                id,
                1L,
                id,
                id,
                LocalDate.parse("2026-09-15"),
                "APPROVED",
                lineCount,
                Instant.parse("2026-09-15T00:00:00Z"),
                "a".repeat(64),
                id,
                id,
                1L,
                1L,
                "PUBLISHED");
    }
}
