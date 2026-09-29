package com.dwp.services.time.workregime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.dwp.services.time.workregime.WorkRegimeApiModels.CreateDraftRequest;
import com.dwp.services.time.workregime.WorkRegimeApiModels.LifecycleRequest;
import com.dwp.services.time.workregime.WorkRegimeApiModels.PolicyTermInput;
import com.dwp.services.time.workregime.WorkRegimeApiModels.SegmentInput;
import com.dwp.services.time.workregime.WorkRegimeApiModels.SimulationRequest;
import com.dwp.services.time.workregime.WorkRegimeModels.ArrangementKind;
import com.dwp.services.time.workregime.WorkRegimeModels.DstOverlapPolicy;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyExtensionKind;
import com.dwp.services.time.workregime.WorkRegimeModels.ScopeType;
import com.dwp.services.time.workregime.WorkRegimeModels.SegmentKind;
import org.junit.jupiter.api.Test;

class WorkRegimeApiModelsBoundsTest {

    private static final UUID RULE_PACK_ID =
            UUID.fromString("81000000-0000-4000-8000-000000000001");
    private static final UUID WORKER_ID =
            UUID.fromString("81000000-0000-4000-8000-000000000002");
    private static final UUID ASSIGNMENT_ID =
            UUID.fromString("81000000-0000-4000-8000-000000000003");

    @Test
    void createDraftAcceptsExactDdlWidthsAndRejectsEveryOverWidthValue() {
        CreateDraftRequest boundary = draft(
                "R".repeat(128),
                "D".repeat(240),
                "S".repeat(128),
                "Asia/Seoul",
                "ABCDEFGHIJKL",
                1L,
                1,
                0L,
                List.of(),
                List.of());

        assertThat(boundary.regimeKey()).hasSize(128);
        assertThat(boundary.displayName()).hasSize(240);
        assertThat(boundary.scopeRef()).hasSize(128);
        assertThat(boundary.jurisdictionSubdivision()).hasSize(12);

        assertThrows(IllegalArgumentException.class, () -> draft(
                "R".repeat(129), "D", "S", "Asia/Seoul", "", 1L, 1, 0L,
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> draft(
                "R", "D".repeat(241), "S", "Asia/Seoul", "", 1L, 1, 0L,
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> draft(
                "R", "D", "S".repeat(129), "Asia/Seoul", "", 1L, 1, 0L,
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> draft(
                "R", "D", "S", "Z".repeat(81), "", 1L, 1, 0L,
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> draft(
                "R", "D", "S", "Asia/Seoul", "ABCDEFGHIJKLM", 1L, 1, 0L,
                List.of(), List.of()));
    }

    @Test
    void createDraftAcceptsOnlyRegionZonesAndDdlSafeRevisionRanges() {
        assertDoesNotThrow(() -> draft(
                "R", "D", "S", "Asia/Seoul", "", Long.MAX_VALUE,
                Integer.MAX_VALUE, Long.MAX_VALUE, List.of(), List.of()));

        assertThrows(IllegalArgumentException.class, () -> draft(
                "R", "D", "S", "+09:00", "", 1L, 1, 0L,
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> draft(
                "R", "D", "S", "Not/A_Zone", "", 1L, 1, 0L,
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> draft(
                "R", "D", "S", "Asia/Seoul", "", 0L, 1, 0L,
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> draft(
                "R", "D", "S", "Asia/Seoul", "", 1L, 0, 0L,
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> draft(
                "R", "D", "S", "Asia/Seoul", "", 1L, 1, -1L,
                List.of(), List.of()));
    }

    @Test
    void createDraftBoundsPolicyTermsAndSegmentsBeforeCommandExecution() {
        List<PolicyTermInput> maximumTerms = terms(WorkRegimeApiModels.MAX_POLICY_TERMS);
        List<SegmentInput> maximumSegments = segments(WorkRegimeApiModels.MAX_SEGMENTS);

        CreateDraftRequest boundary = draft(
                "R", "D", "S", "Asia/Seoul", "", 1L, 1, 0L,
                maximumTerms, maximumSegments);
        assertThat(boundary.terms()).hasSize(WorkRegimeApiModels.MAX_POLICY_TERMS);
        assertThat(boundary.segments()).hasSize(WorkRegimeApiModels.MAX_SEGMENTS);

        assertThrows(IllegalArgumentException.class, () -> draft(
                "R", "D", "S", "Asia/Seoul", "", 1L, 1, 0L,
                terms(WorkRegimeApiModels.MAX_POLICY_TERMS + 1), List.of()));
        assertThrows(IllegalArgumentException.class, () -> draft(
                "R", "D", "S", "Asia/Seoul", "", 1L, 1, 0L,
                List.of(), segments(WorkRegimeApiModels.MAX_SEGMENTS + 1)));
    }

    @Test
    void policyTermEnforcesDdlTextAndNumericBoundsWithoutWeakeningOneOfTyping() {
        PolicyTermInput boundaryName = stringTerm("A" + "B".repeat(99), "value");
        PolicyTermInput boundaryString = stringTerm("STRING_VALUE", "v".repeat(1_024));
        PolicyTermInput boundaryDecimal = decimalTerm(
                "DECIMAL_VALUE", new BigDecimal("1234567890123456.12345678"));

        assertThat(boundaryName.parameterName()).hasSize(100);
        assertThat(boundaryString.stringValue()).hasSize(1_024);
        assertThat(boundaryDecimal.decimalValue()).isEqualByComparingTo(
                new BigDecimal("1234567890123456.12345678"));

        assertThrows(IllegalArgumentException.class,
                () -> stringTerm("A" + "B".repeat(100), "value"));
        assertThrows(IllegalArgumentException.class,
                () -> stringTerm("STRING_VALUE", "v".repeat(1_025)));
        assertThrows(IllegalArgumentException.class,
                () -> decimalTerm("DECIMAL_VALUE", new BigDecimal(
                        "12345678901234567.1234567")));
        assertThrows(IllegalArgumentException.class,
                () -> decimalTerm("DECIMAL_VALUE", new BigDecimal("0.123456789")));
        assertThrows(IllegalArgumentException.class, () -> new PolicyTermInput(
                PolicyExtensionKind.FLEXIBLE,
                "STRING_VALUE",
                "STRING",
                "value",
                1L,
                null,
                null,
                null));
    }

    @Test
    void segmentEnforcesKeyWidthAndExistingTemporalShapeAtTheApiBoundary() {
        SegmentInput boundary = segment("K".repeat(160));

        assertThat(boundary.key()).hasSize(160);
        assertThrows(IllegalArgumentException.class, () -> segment("K".repeat(161)));
        assertThrows(IllegalArgumentException.class, () -> new SegmentInput(
                "WITH_SECONDS",
                DayOfWeek.MONDAY,
                SegmentKind.WORK,
                LocalTime.of(9, 0, 1),
                LocalTime.of(17, 0),
                0,
                DstOverlapPolicy.REJECT));
        assertThrows(IllegalArgumentException.class, () -> new SegmentInput(
                "BAD_OFFSET",
                DayOfWeek.MONDAY,
                SegmentKind.WORK,
                LocalTime.of(9, 0),
                LocalTime.of(17, 0),
                2,
                DstOverlapPolicy.REJECT));
    }

    @Test
    void lifecycleAndSimulationRevisionsStayWithinCanonicalBigintRange() {
        assertDoesNotThrow(() -> new LifecycleRequest(Long.MAX_VALUE));
        SimulationRequest boundary = new SimulationRequest(
                Long.MAX_VALUE,
                Long.toString(Long.MAX_VALUE),
                Long.toString(Long.MAX_VALUE),
                WorkRegimeApiModels.SIMULATION_PURPOSE);
        assertThat(boundary.assignmentSnapshotRevision()).isEqualTo("9223372036854775807");
        assertThat(boundary.policyRevision()).isEqualTo("9223372036854775807");

        assertThrows(IllegalArgumentException.class, () -> new LifecycleRequest(0L));
        assertThrows(IllegalArgumentException.class, () -> new SimulationRequest(
                0L, "0", "1", WorkRegimeApiModels.SIMULATION_PURPOSE));
        assertThrows(IllegalArgumentException.class, () -> simulation("-1", "1"));
        assertThrows(IllegalArgumentException.class, () -> simulation("0", "0"));
        assertThrows(IllegalArgumentException.class, () -> simulation("9223372036854775808", "1"));
        assertThrows(IllegalArgumentException.class, () -> simulation("0", "9223372036854775808"));
        assertThrows(IllegalArgumentException.class, () -> simulation("01", "1"));
        assertThrows(IllegalArgumentException.class, () -> simulation("0", "01"));
        assertThrows(IllegalArgumentException.class, () -> new SimulationRequest(
                1L, "0", "1", "OTHER_PURPOSE"));
        assertThrows(IllegalArgumentException.class, () -> new SimulationRequest(
                1L, "0", "1", "P".repeat(81)));
    }

    private static CreateDraftRequest draft(
            String regimeKey,
            String displayName,
            String scopeRef,
            String timeZone,
            String jurisdictionSubdivision,
            long policyRevision,
            int templateSchemaVersion,
            long assignmentSnapshotRevision,
            List<PolicyTermInput> terms,
            List<SegmentInput> segments) {
        return new CreateDraftRequest(
                regimeKey,
                displayName,
                ArrangementKind.FIXED,
                null,
                ScopeType.TENANT,
                scopeRef,
                Integer.MAX_VALUE,
                LocalDate.parse("2026-01-01"),
                LocalDate.parse("2027-01-01"),
                timeZone,
                RULE_PACK_ID,
                "KR",
                jurisdictionSubdivision,
                policyRevision,
                templateSchemaVersion,
                WORKER_ID,
                ASSIGNMENT_ID,
                assignmentSnapshotRevision,
                terms,
                segments);
    }

    private static List<PolicyTermInput> terms(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> stringTerm("P" + index, "value"))
                .toList();
    }

    private static List<SegmentInput> segments(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> segment("segment-" + index))
                .toList();
    }

    private static PolicyTermInput stringTerm(String name, String value) {
        return new PolicyTermInput(
                PolicyExtensionKind.FLEXIBLE,
                name,
                "STRING",
                value,
                null,
                null,
                null,
                null);
    }

    private static PolicyTermInput decimalTerm(String name, BigDecimal value) {
        return new PolicyTermInput(
                PolicyExtensionKind.FLEXIBLE,
                name,
                "DECIMAL",
                null,
                null,
                value,
                null,
                null);
    }

    private static SegmentInput segment(String key) {
        return new SegmentInput(
                key,
                DayOfWeek.MONDAY,
                SegmentKind.WORK,
                LocalTime.of(9, 0),
                LocalTime.of(17, 0),
                0,
                DstOverlapPolicy.REJECT);
    }

    private static SimulationRequest simulation(
            String assignmentSnapshotRevision, String policyRevision) {
        return new SimulationRequest(
                1L,
                assignmentSnapshotRevision,
                policyRevision,
                WorkRegimeApiModels.SIMULATION_PURPOSE);
    }
}
